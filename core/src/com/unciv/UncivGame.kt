package com.unciv

import com.badlogic.gdx.Application
import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.OnlineMultiplayer
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.Translations
import com.unciv.ui.audio.GameSounds
import com.unciv.ui.audio.MusicController
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.Fonts
import com.unciv.ui.components.extensions.center
import com.unciv.ui.crashhandling.CrashScreen
import com.unciv.ui.crashhandling.wrapCrashHandlingUnit
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.LanguagePickerScreen
import com.unciv.ui.screens.LoadingScreen
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.worldscreen.PlayerReadyScreen
import com.unciv.ui.screens.worldscreen.WorldMapHolder
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.utils.DebugUtils
import com.unciv.utils.Display
import com.unciv.utils.Log
import com.unciv.utils.PlatformSpecific
import com.unciv.utils.concurrency.Concurrency
import com.unciv.utils.concurrency.launchOnGLThread
import com.unciv.utils.concurrency.withGLContext
import com.unciv.utils.concurrency.withThreadPoolContext
import com.unciv.utils.debug
import kotlinx.coroutines.CancellationException
import java.io.PrintWriter
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.system.exitProcess

object GUI {

    fun setUpdateWorldOnNextRender() {
        UncivGame.Current.worldScreen?.shouldUpdate = true
    }

    fun pushScreen(screen: BaseScreen) {
        UncivGame.Current.pushScreen(screen)
    }

    fun resetToWorldScreen() {
        UncivGame.Current.resetToWorldScreen()
    }

    fun getSettings(): GameSettings {
        return UncivGame.Current.settings
    }

    fun isWorldLoaded(): Boolean {
        return UncivGame.Current.worldScreen != null
    }

    fun isMyTurn(): Boolean {
        return UncivGame.Current.worldScreen!!.isPlayersTurn
    }

    fun isAllowedChangeState(): Boolean {
        return UncivGame.Current.worldScreen!!.canChangeState
    }

    fun getWorldScreen(): WorldScreen {
        return UncivGame.Current.worldScreen!!
    }

    fun getWorldScreenIfActive(): WorldScreen? {
        return UncivGame.Current.getWorldScreenIfActive()
    }

    fun getMap(): WorldMapHolder {
        return UncivGame.Current.worldScreen!!.mapHolder
    }

    fun getUnitTable(): UnitTable {
        return UncivGame.Current.worldScreen!!.bottomUnitTable
    }

    fun getViewingPlayer(): Civilization {
        return UncivGame.Current.worldScreen!!.viewingCiv
    }

    fun getSelectedPlayer(): Civilization {
        return UncivGame.Current.worldScreen!!.selectedCiv
    }

    private var keyboardAvailableCache: Boolean? = null
    /** Tests availability of a physical keyboard */
    val keyboardAvailable: Boolean
        get() {
            // defer decision if Gdx.input not yet initialized
            if (keyboardAvailableCache == null && Gdx.input != null)
                keyboardAvailableCache = Gdx.input.isPeripheralAvailable(Input.Peripheral.HardwareKeyboard)
            return keyboardAvailableCache ?: false
        }

}

open class UncivGame(val isConsoleMode: Boolean = false) : Game(), PlatformSpecific {

    var deepLinkedMultiplayerGame: String? = null
    var gameInfo: GameInfo? = null

    lateinit var settings: GameSettings
    lateinit var musicController: MusicController
    lateinit var onlineMultiplayer: OnlineMultiplayer
    lateinit var files: UncivFiles

    var isTutorialTaskCollapsed = false

    var worldScreen: WorldScreen? = null
        private set

    var isInitialized = false

    /** A wrapped render() method that crashes to [CrashScreen] on a unhandled exception or error. */
    private val wrappedCrashHandlingRender = { super.render() }.wrapCrashHandlingUnit()
    // Stored here because I imagine that might be slightly faster than allocating for a new lambda every time, and the render loop is possibly one of the only places where that could have a significant impact.

    val translations = Translations()

    val screenStack = ArrayDeque<BaseScreen>()

    override fun create() {
        isInitialized = false // this could be on reload, therefore we need to keep setting this to false
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        if (Gdx.app.type != Application.ApplicationType.Desktop) {
            DebugUtils.VISIBLE_MAP = false
        }
        Current = this
        files = UncivFiles(Gdx.files)

        // If this takes too long players, especially with older phones, get ANR problems.
        // Whatever needs graphics needs to be done on the main thread,
        // So it's basically a long set of deferred actions.

        /** When we recreate the GL context for whatever reason (say - we moved to a split screen on Android),
         * ALL objects that were related to the old context - need to be recreated.
         * So far we have:
         * - All textures (hence the texture atlas)
         * - SpriteBatch (hence BaseScreen uses a new SpriteBatch for each screen)
         * - Skin (hence BaseScreen.setSkin())
         * - Font (hence Fonts.resetFont() inside setSkin())
         */
        settings = files.getGeneralSettings() // needed for the screen
        Display.setScreenMode(settings.screenMode, settings)
        setAsRootScreen(GameStartScreen())  // NOT dependent on any atlas or skin
        GameSounds.init()

        musicController = MusicController()  // early, but at this point does only copy volume from settings
        installAudioHooks()

        onlineMultiplayer = OnlineMultiplayer()

        Concurrency.run {
            // Check if the server is available in case the feature set has changed
            try {
                onlineMultiplayer.checkServerStatus()
            } catch (ex: Exception) {
                debug("Couldn't connect to server: " + ex.message)
            }
        }

        ImageGetter.resetAtlases()
        ImageGetter.setNewRuleset(ImageGetter.ruleset)  // This needs to come after the settings, since we may have default visual mods
        if (settings.tileSet !in ImageGetter.getAvailableTilesets()) { // If one of the tilesets is no longer available, default back
            settings.tileSet = Constants.defaultTileset
        }

        Gdx.graphics.isContinuousRendering = settings.continuousRendering

        Concurrency.run("LoadJSON") {
            RulesetCache.loadRulesets()
            translations.tryReadTranslationForCurrentLanguage()
            translations.loadPercentageCompleteOfLanguages()
            TileSetCache.loadTileSetConfigs()
            SkinCache.loadSkinConfigs()

            val vanillaRuleset = RulesetCache.getVanillaRuleset()

            if (settings.multiplayer.userId.isEmpty()) { // assign permanent user id
                settings.multiplayer.userId = UUID.randomUUID().toString()
                settings.save()
            }

            // Loading available fonts can take a long time on Android phones.
            // Therefore we initialize the lazy parameters in the font implementation, while we're in another thread, to avoid ANRs on main thread
            Fonts.fontImplementation.setFontFamily(settings.fontFamilyData, settings.getFontSize())

            // This stuff needs to run on the main thread because it needs the GL context
            launchOnGLThread {
                BaseScreen.setSkin() // needs to come AFTER the Texture reset, since the buttons depend on it and after loadSkinConfigs to be able to use the SkinConfig

                musicController.chooseTrack(suffixes = listOf(MusicMood.Menu, MusicMood.Ambient),
                    flags = EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))

                ImageGetter.ruleset = vanillaRuleset // so that we can enter the map editor without having to load a game first

                when {
                    settings.isFreshlyCreated -> setAsRootScreen(LanguagePickerScreen())
                    deepLinkedMultiplayerGame == null -> setAsRootScreen(MainMenuScreen())
                    else -> tryLoadDeepLinkedGame()
                }

                isInitialized = true
            }
        }
    }

    /**
     * Loads a game, [disposing][BaseScreen.dispose] all screens.
     *
     * Initializes the state of all important modules.
     *
     * Automatically runs on the appropriate thread.
     *
     * Sets the returned `WorldScreen` as the only active screen.
     */
    suspend fun loadGame(newGameInfo: GameInfo, callFromLoadScreen: Boolean = false): WorldScreen = withThreadPoolContext toplevel@{
        val prevGameInfo = gameInfo
        gameInfo = newGameInfo


        if (gameInfo?.gameParameters?.isOnlineMultiplayer == true
                && gameInfo?.gameParameters?.anyoneCanSpectate == false
                && gameInfo!!.civilizations.none { it.playerId == settings.multiplayer.userId }) {
            throw UncivShowableException("You are not allowed to spectate!")
        }

        initializeResources(prevGameInfo, newGameInfo)

        val isLoadingSameGame = worldScreen != null && prevGameInfo != null && prevGameInfo.gameId == newGameInfo.gameId
        val worldScreenRestoreState = if (!callFromLoadScreen && isLoadingSameGame) worldScreen!!.getRestoreState() else null

        lateinit var loadingScreen: LoadingScreen

        withGLContext {
            // this is not merged with the below GL context block so that our loading screen gets a chance to show - otherwise
            // we do it all in one swoop on the same thread and the application just "freezes" without loading screen for the duration.
            loadingScreen = LoadingScreen(getScreen())
            setScreen(loadingScreen)
        }

        return@toplevel withGLContext {
            for (screen in screenStack) screen.dispose()
            screenStack.clear()

            worldScreen = null // This allows the GC to collect our old WorldScreen, otherwise we keep two WorldScreens in memory.
            val newWorldScreen = WorldScreen(newGameInfo, newGameInfo.getPlayerToViewAs(), worldScreenRestoreState)
            worldScreen = newWorldScreen

            val moreThanOnePlayer = newGameInfo.civilizations.count { it.playerType == PlayerType.Human } > 1
            val isSingleplayer = !newGameInfo.gameParameters.isOnlineMultiplayer
            val screenToShow = if (moreThanOnePlayer && isSingleplayer) {
                PlayerReadyScreen(newWorldScreen)
            } else {
                newWorldScreen
            }

            screenStack.addLast(screenToShow)
            setScreen(screenToShow)
            loadingScreen.dispose()

            return@withGLContext newWorldScreen
        }
    }

    /** The new game info may have different mods or rulesets, which may use different resources that need to be loaded. */
    private suspend fun initializeResources(prevGameInfo: GameInfo?, newGameInfo: GameInfo) {
        if (prevGameInfo == null
                || prevGameInfo.gameParameters.baseRuleset != newGameInfo.gameParameters.baseRuleset
                || prevGameInfo.gameParameters.mods != newGameInfo.gameParameters.mods) {
            withGLContext {
                ImageGetter.setNewRuleset(newGameInfo.ruleset)
            }
            val fullModList = newGameInfo.gameParameters.getModsAndBaseRuleset()
            musicController.setModList(fullModList)
        }
    }

    /** Re-creates the current [worldScreen], if there is any. */
    suspend fun reloadWorldscreen() {
        val curWorldScreen = worldScreen
        val curGameInfo = gameInfo
        if (curWorldScreen == null || curGameInfo == null) return

        loadGame(curGameInfo)
    }

    /**
     * @throws UnsupportedOperationException Use pushScreen or replaceCurrentScreen instead
     */
    @Deprecated("Never use this, it's only here because it's part of the gdx.Game interface.", ReplaceWith("pushScreen"))
    override fun setScreen(screen: Screen) {
        throw UnsupportedOperationException("Use pushScreen or replaceCurrentScreen instead")
    }

    override fun getScreen(): BaseScreen? {
        val curScreen = super.getScreen()
        return if (curScreen == null) { null } else { curScreen as BaseScreen
        }
    }

    private fun setScreen(newScreen: BaseScreen) {
        debug("Setting new screen: %s, screenStack: %s", newScreen, screenStack)
        Gdx.input.inputProcessor = newScreen.stage
        super.setScreen(newScreen) // This can set the screen to the policy picker or tech picker screen, so the input processor must be set before
        if (newScreen is WorldScreen) {
            newScreen.shouldUpdate = true
        }
        Gdx.graphics.requestRendering()
    }

    /** Removes & [disposes][BaseScreen.dispose] all currently active screens in the [screenStack] and sets the given screen as the only screen. */
    private fun setAsRootScreen(root: BaseScreen) {
        for (screen in screenStack) screen.dispose()
        screenStack.clear()
        screenStack.addLast(root)
        setScreen(root)
    }
    /** Adds a screen to be displayed instead of the current screen, with an option to go back to the previous screen by calling [popScreen] */
    fun pushScreen(newScreen: BaseScreen) {
        screenStack.addLast(newScreen)
        setScreen(newScreen)
    }

    /**
     * Pops the currently displayed screen off the screen stack and shows the previous screen.
     *
     * If there is no other screen than the current, will ask the user to quit the game and return null.
     *
     * Automatically [disposes][BaseScreen.dispose] the old screen.
     *
     * @return the new screen
     */
    fun popScreen(): BaseScreen? {
        if (screenStack.size == 1) {
            musicController.pause()
            ConfirmPopup(
                screen = screenStack.last(),
                question = "Do you want to exit the game?",
                confirmText = "Exit",
                restoreDefault = { musicController.resume() },
                action = { Gdx.app.exit() }
            ).open(force = true)
            return null
        }
        val oldScreen = screenStack.removeLast()
        val newScreen = screenStack.last()
        setScreen(newScreen)
        newScreen.resume()
        oldScreen.dispose()
        return newScreen
    }

    /** Replaces the current screen with a new one. Automatically [disposes][BaseScreen.dispose] the old screen. */
    fun replaceCurrentScreen(newScreen: BaseScreen) {
        val oldScreen = screenStack.removeLast()
        screenStack.addLast(newScreen)
        setScreen(newScreen)
        oldScreen.dispose()
    }

    /** Resets the game to the stored world screen and automatically [disposes][Screen.dispose] all other screens. */
    fun resetToWorldScreen(): WorldScreen {
        for (screen in screenStack.filter { it !is WorldScreen }) screen.dispose()
        screenStack.removeAll { it !is WorldScreen }
        val worldScreen= screenStack.last() as WorldScreen

        // Re-initialize translations, images etc. that may have been 'lost' when we were playing around in NewGameScreen
        val ruleset = worldScreen.gameInfo.ruleset
        translations.translationActiveMods = ruleset.mods
        ImageGetter.setNewRuleset(ruleset)

        setScreen(worldScreen)
        return worldScreen
    }

    private fun tryLoadDeepLinkedGame() = Concurrency.run("LoadDeepLinkedGame") {
        if (deepLinkedMultiplayerGame == null) return@run

        launchOnGLThread {
            if (screenStack.isEmpty() || screenStack[0] !is GameStartScreen) {
                setAsRootScreen(LoadingScreen(getScreen()!!))
            }
        }
        try {
            onlineMultiplayer.loadGame(deepLinkedMultiplayerGame!!)
        } catch (ex: Exception) {
            launchOnGLThread {
                val mainMenu = MainMenuScreen()
                replaceCurrentScreen(mainMenu)
                val popup = Popup(mainMenu)
                val (message) = LoadGameScreen.getLoadExceptionMessage(ex)
                popup.addGoodSizedLabel(message)
                popup.row()
                popup.addCloseButton()
                popup.open()
            }
        } finally {
            deepLinkedMultiplayerGame = null
        }
    }

    // This is ALWAYS called after create() on Android - google "Android life cycle"
    override fun resume() {
        super.resume()
        if (!isInitialized) return // The stuff from Create() is still happening, so the main screen will load eventually
        musicController.resume()

        // This is also needed in resume to open links and notifications
        // correctly when the app was already running. The handling in onCreate
        // does not seem to be enough
        tryLoadDeepLinkedGame()
    }

    override fun pause() {
        val curGameInfo = gameInfo
        if (curGameInfo != null) files.requestAutoSave(curGameInfo)
        if (::musicController.isInitialized) musicController.pause()
        super.pause()
    }

    override fun resize(width: Int, height: Int) {
        screen.resize(width, height)
    }

    override fun render() = wrappedCrashHandlingRender()

    override fun dispose() {
        Gdx.input.inputProcessor = null // don't allow ANRs when shutting down, that's silly
        SoundPlayer.clearCache()
        if (::musicController.isInitialized) musicController.gracefulShutdown()  // Do allow fade-out

        val curGameInfo = gameInfo
        if (curGameInfo != null) {
            val autoSaveJob = files.autoSaveJob
            if (autoSaveJob != null && autoSaveJob.isActive) {
                // auto save is already in progress (e.g. started by onPause() event)
                // let's allow it to finish and do not try to autosave second time
                Concurrency.runBlocking {
                    autoSaveJob.join()
                }
            } else {
                files.autoSave(curGameInfo)      // NO new thread
            }
        }
        settings.save()
        Concurrency.stopThreadPools()

        // On desktop this should only be this one and "DestroyJavaVM"
        logRunningThreads()

        exitProcess(0)
    }

    private fun logRunningThreads() {
        val numThreads = Thread.activeCount()
        val threadList = Array(numThreads) { _ -> Thread() }
        Thread.enumerate(threadList)
        threadList.filter { it !== Thread.currentThread() && it.name != "DestroyJavaVM" }.forEach {
            debug("Thread %s still running in UncivGame.dispose().", it.name)
        }
    }

    /** Handles an uncaught exception or error. First attempts a platform-specific handler, and if that didn't handle the exception or error, brings the game to a [CrashScreen]. */
    fun handleUncaughtThrowable(ex: Throwable) {
        if (ex is CancellationException) {
            return // kotlin coroutines use this for control flow... so we can just ignore them.
        }
        Log.error("Uncaught throwable", ex)
        try {
            PrintWriter(files.fileWriter("lasterror.txt")).use {
                ex.printStackTrace(it)
            }
        } catch (ex: Exception) {
            // ignore
        }
        Gdx.app.postRunnable {
            setAsRootScreen(CrashScreen(ex))
        }
    }

    /** Returns the [worldScreen] if it is the currently active screen of the game */
    fun getWorldScreenIfActive(): WorldScreen? {
        return if (screen == worldScreen) worldScreen else null
    }

    fun goToMainMenu(): MainMenuScreen {
        val curGameInfo = gameInfo
        if (curGameInfo != null) {
            files.requestAutoSaveUnCloned(curGameInfo) // Can save gameInfo directly because the user can't modify it on the MainMenuScreen
        }
        val mainMenuScreen = MainMenuScreen()
        pushScreen(mainMenuScreen)
        return mainMenuScreen
    }

    /** Sets a simulated [GameInfo] object this game should run on */
    fun startSimulation(simulatedGameInfo: GameInfo) {
        gameInfo = simulatedGameInfo
    }

    companion object {
        //region AUTOMATICALLY GENERATED VERSION DATA - DO NOT CHANGE THIS REGION, INCLUDING THIS COMMENT
        val VERSION = Version("4.5.8", 832)
        //endregion

        lateinit var Current: UncivGame
        fun isCurrentInitialized() = this::Current.isInitialized
        fun isCurrentGame(gameId: String): Boolean = isCurrentInitialized() && Current.gameInfo != null && Current.gameInfo!!.gameId == gameId
        fun isDeepLinkedGameLoading() = isCurrentInitialized() && Current.deepLinkedMultiplayerGame != null
    }

    data class Version(
        val text: String,
        val number: Int
    ) : IsPartOfGameInfoSerialization {
        @Suppress("unused") // used by json serialization
        constructor() : this("", -1)
        fun toNiceString() = "$text (Build $number)"
    }
}

private class GameStartScreen : BaseScreen() {
    init {
        val logoImage = ImageGetter.getExternalImage("banner.png")
        logoImage.center(stage)
        logoImage.setOrigin(Align.center)
        logoImage.color = Color.WHITE.cpy().apply { a = 0f }
        logoImage.addAction(Actions.alpha(1f, 0.3f))
        stage.addActor(logoImage)
    }
}
