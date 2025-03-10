package com.unciv.ui.popups.options

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.TabbedPager
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.concurrency.Concurrency
import com.unciv.utils.concurrency.withGLContext
import kotlin.reflect.KMutableProperty0

/**
 * The Options (Settings) Popup
 * @param screen The caller - note if this is a [WorldScreen] or [MainMenuScreen] they will be rebuilt when major options change.
 */
//region Fields
class OptionsPopup(
    screen: BaseScreen,
    private val selectPage: Int = defaultPage,
    private val onClose: () -> Unit = {}
) : Popup(screen.stage, /** [TabbedPager] handles scrolling */ scrollable = false ) {

    val game = screen.game
    val settings = screen.game.settings
    val tabs: TabbedPager
    val selectBoxMinWidth: Float
    private val tabMinWidth: Float

    private var keyBindingsTab: KeyBindingsTab? = null
    /** Enable the still experimental Keyboard Bindings page in OptionsPopup */
    var enableKeyBindingsTab: Boolean = false

    //endregion

    companion object {
        const val defaultPage = 2  // Gameplay

        const val keysTabCaption = "Keys"
        const val keysTabBeforeCaption = "Advanced"
    }

    init {
        if (settings.addCompletedTutorialTask("Open the options table"))
            (screen as? WorldScreen)?.shouldUpdate = true

        innerTable.pad(0f)
        val tabMaxWidth: Float
        val tabMaxHeight: Float
        screen.run {
            selectBoxMinWidth = if (stage.width < 600f) 200f else 240f
            tabMaxWidth = if (isPortrait()) stage.width - 10f else 0.8f * stage.width
            tabMinWidth = 0.6f * stage.width
            tabMaxHeight = (if (isPortrait()) 0.7f else 0.8f) * stage.height
        }
        tabs = TabbedPager(
            tabMinWidth, tabMaxWidth, 0f, tabMaxHeight,
            headerFontSize = 21, backgroundColor = Color.CLEAR, capacity = 8
        )
        add(tabs).pad(0f).grow().row()

        tabs.addPage(
            "About",
            aboutTab(),
            ImageGetter.getExternalImage("Icon.png"), 24f
        )
        tabs.addPage(
            "Display",
            displayTab(this, ::reloadWorldAndOptions),
            ImageGetter.getImage("UnitPromotionIcons/Scouting"), 24f
        )
        tabs.addPage(
            "Gameplay",
            gameplayTab(this),
            ImageGetter.getImage("OtherIcons/Options"), 24f
        )
        tabs.addPage(
            "Language",
            languageTab(this, ::reloadWorldAndOptions),
            ImageGetter.getImage("FlagIcons/${settings.language}"), 24f
        )
        tabs.addPage(
            "Sound",
            soundTab(this),
            ImageGetter.getImage("OtherIcons/Speaker"), 24f
        )
        tabs.addPage(
            "Multiplayer",
            multiplayerTab(this),
            ImageGetter.getImage("OtherIcons/Multiplayer"), 24f
        )

        tabs.addPage(
            "Advanced",
            advancedTab(this, ::reloadWorldAndOptions),
            ImageGetter.getImage("OtherIcons/Settings"), 24f
        )

        if (RulesetCache.size > BaseRuleset.values().size) {
            val content = ModCheckTab(screen)
            tabs.addPage("Locate mod errors", content, ImageGetter.getImage("OtherIcons/Mods"), 24f)
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT) && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT))) {
            tabs.addPage("Debug", debugTab(this), ImageGetter.getImage("OtherIcons/SecretOptions"), 24f, secret = true)
        }

        addCloseButton {
            screen.game.musicController.onChange(null)
            center(screen.stage)
            keyBindingsTab?.save()
            onClose()
        }.padBottom(10f)

        if (GUI.keyboardAvailable) {
            showOrHideKeyBindings()  // Do this late because it looks for the page to insert before
        }

        pack() // Needed to show the background.
        center(screen.stage)
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (!visible) return
        tabs.askForPassword(secretHashCode = 2747985)
        if (tabs.activePage < 0) tabs.selectPage(selectPage)
    }

    /** Reload this Popup after major changes (resolution, tileset, language, font) */
    private fun reloadWorldAndOptions() {
        Concurrency.run("Reload from options") {
            settings.save()
            withGLContext {
                // We have to run setSkin before the screen is rebuild else changing skins
                // would only load the new SkinConfig after the next rebuild
                BaseScreen.setSkin()
            }
            val screen = UncivGame.Current.screen
            if (screen is WorldScreen) {
                UncivGame.Current.reloadWorldscreen()
            } else if (screen is MainMenuScreen) {
                withGLContext {
                    UncivGame.Current.replaceCurrentScreen(MainMenuScreen())
                }
            }
            withGLContext {
                UncivGame.Current.screen?.openOptionsPopup(tabs.activePage)
            }
        }
    }

    fun addCheckbox(table: Table, text: String, initialState: Boolean, updateWorld: Boolean = false, newRow: Boolean = true, action: ((Boolean) -> Unit)) {
        val checkbox = text.toCheckBox(initialState) {
            action(it)
            settings.save()
            val worldScreen = GUI.getWorldScreenIfActive()
            if (updateWorld && worldScreen != null) worldScreen.shouldUpdate = true
        }
        if (newRow) table.add(checkbox).colspan(2).left().row()
        else table.add(checkbox).left()
    }

    fun addCheckbox(
        table: Table,
        text: String,
        settingsProperty: KMutableProperty0<Boolean>,
        updateWorld: Boolean = false,
        action: (Boolean) -> Unit = {}
    ) {
        addCheckbox(table, text, settingsProperty.get(), updateWorld) {
            action(it)
            settingsProperty.set(it)
        }
    }

    internal fun showOrHideKeyBindings() {
        // At the moment, the Key bindings Tab exists only on-demand. To refactor it back to permanent,
        // move the `keyBindingsTab =` line and addPage call to before the Advanced Tab creation,
        // then delete this function, delete the enableKeyBindingsTab flag and clean up what is flagged by the compiler as missing or unused.
        val existingIndex = tabs.getPageIndex(keysTabCaption)
        if (enableKeyBindingsTab && existingIndex < 0) {
            if (keyBindingsTab == null)
                keyBindingsTab = KeyBindingsTab(this, tabMinWidth - 40f)  // 40 = padding
            val beforeIndex = tabs.getPageIndex(keysTabBeforeCaption)
            tabs.addPage(
                keysTabCaption, keyBindingsTab,
                ImageGetter.getImage("OtherIcons/Keyboard"), 24f,
                insertBefore = beforeIndex
            )
        } else if (!enableKeyBindingsTab && existingIndex >= 0) {
            tabs.removePage(existingIndex)
        }
    }
}
