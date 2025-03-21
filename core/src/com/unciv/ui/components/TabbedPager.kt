package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.popups.Popup
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.keyShortcuts
import com.unciv.ui.components.extensions.onActivation
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.screens.basescreen.BaseScreen

//TODO If keys are assigned, the widget is in a popup not filling stage width, and a button is
// partially visible on the right end, the key tooltip will show outside the parent.

/**
 * Implements a 'Tabs' widget where different pages can be switched by selecting a header button.
 *
 * Each page is an Actor, passed to the Widget via [addPage]. Pages can be [removed][removePage],
 * [replaced][replacePage] or dynamically added after the Widget is already shown.

 * Pages are automatically scrollable, switching pages preserves scroll positions individually.
 * The widget optionally supports "fixed content", an additional Actor that will be inserted above
 * the regular content of any page. It will scroll only horizontally, and its scroll position will
 * synchronize bidirectionally with the scrolling of the main content.
 *
 * Pages can be disabled or secret - any 'secret' pages added require a later call to [askForPassword]
 * to activate them (or discard if the password is wrong).
 *
 * The size parameters are lower and upper bounds of the page content area. The widget will always report
 * these bounds (plus header height) as layout properties min/max-Width/Height, and measure the content
 * area of added pages and set the reported pref-W/H to their maximum within these bounds. But, if a
 * maximum is not specified, that coordinate will grow with content up to screen size, and layout
 * max-W/H will always report the same as pref-W/H.
 */
//region Fields
@Suppress("MemberVisibilityCanBePrivate", "unused")  // All members are part of our API
open class TabbedPager(
    minimumWidth: Float = 0f,
    maximumWidth: Float = Float.MAX_VALUE,
    minimumHeight: Float = 0f,
    maximumHeight: Float = Float.MAX_VALUE,
    private val headerFontSize: Int = Constants.defaultFontSize,
    private val headerFontColor: Color = Color.WHITE,
    private val highlightColor: Color = Color.BLUE,
    backgroundColor: Color = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f),
    private val headerPadding: Float = 10f,
    separatorColor: Color = Color.CLEAR,
    private val shorcutScreen: BaseScreen? = null,
    capacity: Int = 4
) : Table() {

    private val dimW: DimensionMeasurement
    private val dimH: DimensionMeasurement

    private val pages = ArrayList<PageState>(capacity)

    /**
     * Index of currently selected page, or -1 of none. Read-only, use [selectPage] to change.
     */
    var activePage = -1
        private set

    private val header = Table(BaseScreen.skin)
    private val headerScroll = LinkedScrollPane(horizontalOnly = true, header)
    protected var headerHeight = 0f

    private val fixedContentScroll = LinkedScrollPane(horizontalOnly = true)
    private val fixedContentScrollCell: Cell<ScrollPane>
    private val contentScroll = LinkedScrollPane(horizontalOnly = false, linkTo = fixedContentScroll)
    private var savedScrollListener: EventListener? = null

    private val deferredSecretPages = ArrayDeque<PageState>(0)
    private var askPasswordLock = false

    //endregion
    //region Public Interfaces

    /** Pages added via [addPage] can optionally implement this to get notified when they are
     *  [activated] or [deactivated], or to provide [fixed content][getFixedContent] */
    interface IPageExtensions {
        /** Called by [TabbedPager] after a page is shown, whether by user click or programmatically. */
        fun activated(index: Int, caption: String, pager: TabbedPager)

        /** Called by [TabbedPager] before a page is hidden, whether by user click or programmatically. */
        fun deactivated(index: Int, caption: String, pager: TabbedPager) {}

        /** @return Optional second content [Actor], will be placed outside the tab's main [ScrollPane] between header and `content`. Scrolls horizontally only. */
        fun getFixedContent(): Actor? = null
    }

    //endregion
    //region Private Classes

    private class PageState(
        caption: String,
        var content: Actor,
        var fixedContent: Actor?,
        var disabled: Boolean,
        icon: Actor?,
        iconSize: Float,
        val shortcutKey: KeyCharAndCode,
        var scrollAlign: Int,
        val syncScroll: Boolean,
        pager: TabbedPager
    ) {
        var fixedHeight = 0f
        var scrollX = 0f
        var scrollY = 0f

        val button = IconTextButton(caption, icon, pager.headerFontSize, pager.headerFontColor).apply {
            name = caption // enable finding pages by untranslated caption without needing our own field
            if (icon != null) {
                if (iconSize != 0f)
                    iconCell.size(iconSize)
                iconCell.padRight(pager.headerPadding * 0.5f)
            }
        }
        var buttonX = 0f
        var buttonW = 0f

        val caption: String
            get() = button.name

        override fun toString() = "PageState($caption, key=$shortcutKey, disabled=$disabled, content:${content.javaClass.simpleName}, fixedContent:${fixedContent?.javaClass?.simpleName})"
    }

    private data class DimensionMeasurement(
        var min: Float,
        var pref: Float,
        var max: Float,
        val limit: Float,
        val growMax: Boolean
    ) {
        constructor(limit: Float) : this(0f, 0f, 0f, limit, true)
        companion object {
            fun from(min: Float, max: Float, limit: Float): DimensionMeasurement {
                if (max == Float.MAX_VALUE)
                    return DimensionMeasurement(min, min, 0f, limit, true)
                val fixedMax = max.coerceAtMost(limit)
                return DimensionMeasurement(min, min, fixedMax, fixedMax, false)
            }
        }
        fun measure(newMin: Float, newPref: Float, newMax: Float): Boolean {
            var needLayout = false
            newMin.coerceAtMost(limit)
                .let { if (it > min) { min = it; needLayout = true } }
            newPref.coerceAtLeast(min).coerceAtMost(limit)
                .let { if (it > pref) { pref = it; needLayout = true } }
            if (!growMax) return needLayout
            newMax.coerceAtLeast(pref).coerceAtMost(limit)
                .let { if (it > max) { max = it; needLayout = true } }
            return needLayout
        }
        fun measureWidth(group: WidgetGroup?): Boolean {
            if (group == null) return false
            group.packIfNeeded()
            return measure(group.minWidth, group.prefWidth, group.maxWidth)
        }
        fun measureHeight(group: WidgetGroup?): Boolean {
            if (group == null) return false
            group.packIfNeeded()
            return measure(group.minHeight, group.prefHeight, group.maxHeight)
        }
        fun combine(top: DimensionMeasurement, bottom: DimensionMeasurement) {
            min = (top.min + bottom.min).coerceAtLeast(min).coerceAtMost(limit)
            pref = (top.pref + bottom.pref).coerceAtLeast(pref).coerceIn(min..limit)
            if (growMax)
                max = (top.max + bottom.max).coerceAtLeast(max).coerceIn(pref..limit)
        }
    }

    private class LinkedScrollPane(
        horizontalOnly: Boolean,
        widget: Actor? = null,
        linkTo: LinkedScrollPane? = null
    ) : AutoScrollPane(widget, BaseScreen.skin) {
        val linkedScrolls = mutableSetOf<LinkedScrollPane>()
        var enableSync = true

        init {
            if (horizontalOnly)
                setScrollingDisabled(false, true)
            setOverscroll(false, false)
            setScrollbarsOnTop(true)
            setupFadeScrollBars(0f, 0f)

            if (linkTo != null) {
                linkedScrolls += linkTo
                linkTo.linkedScrolls += this
            }
        }

        private fun sync(update: Boolean = true) {
            if (!enableSync) return
            for (linkedScroll in linkedScrolls) {
                if (linkedScroll.scrollX == this.scrollX) continue
                linkedScroll.scrollX = this.scrollX
                if (update) linkedScroll.updateVisualScroll()
            }
        }

        class SyncedScrollListener(val linkedScrollPane: LinkedScrollPane):InputListener(){
            val oldScrollListener = linkedScrollPane.listeners.removeIndex(linkedScrollPane.listeners.size-1) as InputListener
            override fun scrolled(event: InputEvent?, x: Float, y: Float, amountX: Float, amountY: Float): Boolean {
                val toReturn = oldScrollListener.scrolled(event, x, y, amountX, amountY)
                linkedScrollPane.sync(false)
                return toReturn
            }
        }

        override fun addScrollListener() {
            super.addScrollListener()
            addListener(SyncedScrollListener(this))
        }

        class LinkedCaptureListener(val linkedScrollPane: LinkedScrollPane):InputListener(){
            val oldListener = linkedScrollPane.captureListeners.removeIndex(0) as InputListener
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                val toReturn = oldListener.touchDown(event, x, y, pointer, button)
                linkedScrollPane.sync()
                return toReturn
            }
            override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
                oldListener.touchDragged(event, x, y, pointer)
                linkedScrollPane.sync()
            }
            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                oldListener.touchUp(event, x, y, pointer, button)
                linkedScrollPane.sync()
            }
            override fun mouseMoved(event: InputEvent?, x: Float, y: Float): Boolean {
                // syncing here leads to stutter
                return oldListener.mouseMoved(event, x, y)
            }
        }

        override fun addCaptureListener() {
            super.addCaptureListener()
            addCaptureListener(LinkedCaptureListener(this))
        }

        class LinkedFlickScrollListener(val stdFlickListener: ActorGestureListener, val linkedScrollPane: LinkedScrollPane):ActorGestureListener(){
            override fun pan(event: InputEvent?, x: Float, y: Float, deltaX: Float, deltaY: Float) {
                stdFlickListener.pan(event, x, y, deltaX, deltaY)
                linkedScrollPane.sync()
            }
            override fun fling(event: InputEvent?, velocityX: Float, velocityY: Float, button: Int) {
                stdFlickListener.fling(event, velocityX, velocityY, button)
                linkedScrollPane.sync()
            }
        }

        override fun getFlickScrollListener(): ActorGestureListener {
            val stdFlickListener = super.getFlickScrollListener()
            return LinkedFlickScrollListener(stdFlickListener, this)
        }

        override fun act(delta: Float) {
            val wasFlinging = isFlinging
            super.act(delta)
            if (wasFlinging) sync()
        }
    }

    private class EmptyClosePage(private val action: ()->Unit) : Actor(), IPageExtensions {
        override fun activated(index: Int, caption: String, pager: TabbedPager) {
            action()
        }
    }

    //endregion
    //region Initialization

    init {
        val screen = (if (UncivGame.isCurrentInitialized()) UncivGame.Current.screen else null)
        val (screenWidth, screenHeight) = (screen?.stage?.run { width to height }) ?: (Float.MAX_VALUE to Float.MAX_VALUE)
        dimW = DimensionMeasurement.from(minimumWidth, maximumWidth, screenWidth)
        dimH = DimensionMeasurement.from(minimumHeight, maximumHeight, screenHeight)

        background = BaseScreen.skinStrings.getUiBackground("General/TabbedPager", tintColor = backgroundColor)

        header.defaults().pad(headerPadding, headerPadding * 0.5f)
        // Measure header height, most likely its final value
        removePage(addPage("Dummy"))
        add(headerScroll).growX().minHeight(headerHeight).row()
        if (separatorColor != Color.CLEAR)
            addSeparator(separatorColor)

        fixedContentScrollCell = add(fixedContentScroll)
        fixedContentScrollCell.growX().row()
        add(contentScroll).grow().row()
    }

    //endregion
    //region Widget interface

    // The following are part of the Widget interface and serve dynamic sizing
    override fun getPrefWidth() = dimW.pref
    fun setPrefWidth(width: Float) {
        if (dimW.growMax && width > dimW.max) dimW.max = width
        if (width !in dimW.min..dimW.max) throw IllegalArgumentException()
        dimW.pref = width
        invalidateHierarchy()
    }
    override fun getPrefHeight() = dimH.pref + headerHeight
    fun setPrefHeight(height: Float) {
        val contentHeight = (height - headerHeight).coerceIn(0f..dimH.limit)
        if (dimH.growMax && contentHeight > dimH.max) dimH.max = contentHeight
        if (contentHeight !in dimH.min..dimH.max) throw IllegalArgumentException()
        dimH.pref = contentHeight
        invalidateHierarchy()
    }
    override fun getMinWidth() = dimW.min
    override fun getMaxWidth() = dimW.max
    override fun getMinHeight() = headerHeight
    override fun getMaxHeight() = dimH.max + headerHeight

    //endregion
    //region API

    /** @return Number of pages currently stored */
    fun pageCount() = pages.size

    /** @return index of a page by its (untranslated) caption, or -1 if no such page exists */
    fun getPageIndex(caption: String) = pages.indexOfLast { it.caption == caption }

    /** Change the selected page by using its index.
     * @param index Page number or -1 to deselect the current page.
     * @param centerButton `true` centers the page's header button, `false` ensures it is visible.
     * @return `true` if the page was successfully changed.
     */
    fun selectPage(index: Int, centerButton: Boolean = true): Boolean {
        if (index !in -1 until pages.size) return false
        if (activePage == index) return false
        if (index >= 0 && pages[index].disabled) return false

        if (activePage != -1) {
            val page = pages[activePage]
            (page.content as? IPageExtensions)?.deactivated(activePage, page.caption, this)
            page.button.color = Color.WHITE
            fixedContentScroll.actor = null
            page.scrollX = contentScroll.scrollX
            page.scrollY = contentScroll.scrollY
            contentScroll.actor = null
        }

        activePage = index

        if (index != -1) {
            val page = pages[index]
            page.button.color = highlightColor

            if (page.scrollAlign != 0) {
                if (Align.isCenterHorizontal(page.scrollAlign))
                    page.scrollX = (page.content.width - this.width) / 2
                else if (Align.isRight(page.scrollAlign))
                    page.scrollX = Float.MAX_VALUE  // ScrollPane _will_ clamp this
                if (Align.isCenterVertical(page.scrollAlign))
                    page.scrollY = (page.content.height - this.height) / 2
                else if (Align.isBottom(page.scrollAlign))
                    page.scrollY = Float.MAX_VALUE  // ScrollPane _will_ clamp this
                page.scrollAlign = 0  // once only
            }

            fixedContentScroll.actor = page.fixedContent
            fixedContentScroll.height = page.fixedHeight
            fixedContentScrollCell.minHeight(page.fixedHeight)
            fixedContentScroll.layout()
            fixedContentScroll.scrollX = page.scrollX
            fixedContentScroll.updateVisualScroll()
            fixedContentScroll.enableSync = page.syncScroll

            contentScroll.actor = page.content
            contentScroll.layout()
            contentScroll.scrollX = page.scrollX
            contentScroll.scrollY = page.scrollY
            contentScroll.updateVisualScroll()
            contentScroll.enableSync = page.syncScroll

            if (centerButton)
                // centering is nice when selectPage is called programmatically
                headerScroll.scrollX = page.buttonX + (page.buttonW - headerScroll.width) / 2
            else
                // when coming from a tap/click, can we at least ensure no part of it is outside the visible area
                headerScroll.run { scrollX = scrollX.coerceIn((page.buttonX + page.buttonW - scrollWidth)..page.buttonX) }

            (page.content as? IPageExtensions)?.activated(index, page.caption, this)
        }
        return true
    }

    /** Change the selected page by using its caption.
     * @param caption Caption of the page to select. A nonexistent name will deselect the current page.
     * @param centerButton `true` centers the page's header button, `false` ensures it is visible.
     * @return `true` if the page was successfully changed.
     */
    fun selectPage(caption: String, centerButton: Boolean = true) = selectPage(getPageIndex(caption), centerButton)
    private fun selectPage(page: PageState) = selectPage(getPageIndex(page), centerButton = false)

    /** Change the disabled property of a page by its index.
     * @return previous value or `false` if index invalid.
     */
    fun setPageDisabled(index: Int, disabled: Boolean): Boolean {
        if (index !in 0 until pages.size) return false
        val page = pages[index]
        val oldValue = page.disabled
        page.disabled = disabled
        page.button.isEnabled = !disabled
        if (disabled && index == activePage) selectPage(-1)
        return oldValue
    }

    /** Change the disabled property of a page by its caption.
     * @return previous value or `false` if caption not found.
     */
    fun setPageDisabled(caption: String, disabled: Boolean) = setPageDisabled(getPageIndex(caption), disabled)

    /** Access a page's header button e.g. for unusual formatting */
    fun getPageButton(index: Int) = pages[index].button

    /** Query the vertical scroll position af a page's contents */
    fun getPageScrollY(index: Int): Float {
        if (index == activePage) return contentScroll.scrollY
        if (index !in 0 until pages.size) return 0f
        return pages[index].scrollY
    }
    /** Change the vertical scroll position af a page's contents */
    fun setPageScrollY(index: Int, scrollY: Float, animation: Boolean = false) {
        if (index !in 0 until pages.size) return
        val page = pages[index]
        page.scrollY = scrollY
        if (index != activePage) return
        contentScroll.scrollY = scrollY
        if (!animation) contentScroll.updateVisualScroll()
    }

    /** Disable/Enable built-in ScrollPane for content pages, including focus stealing prevention */
    fun setScrollDisabled(disabled: Boolean) {
        if (disabled == contentScroll.isScrollingDisabledY) return
        contentScroll.setScrollingDisabled(disabled, disabled)
        if (disabled) {
            savedScrollListener = contentScroll.captureListeners.first()
            contentScroll.captureListeners.clear()
        } else {
            if (savedScrollListener != null)
                contentScroll.addCaptureListener(savedScrollListener)
        }
    }

    /** Remove a page by its index.
     * @return `true` if page successfully removed */
    fun removePage(index: Int): Boolean {
        if (index !in 0 until pages.size) return false
        if (index == activePage) selectPage(-1)
        val page = pages.removeAt(index)
        header.getCell(page.button).clearActor()
        header.cells.removeIndex(index)
        return true
    }

    /** Remove a page by its caption.
     * @return `true` if page successfully removed */
    fun removePage(caption: String) = removePage(getPageIndex(caption))

    /** Replace a page's [content] by its [index]. */
    fun replacePage(index: Int, content: Actor) {
        if (index !in 0 until pages.size) return
        val isActive = index == activePage
        if (isActive) selectPage(-1)
        pages[index].let {
            it.content = content
            it.fixedContent = (content as? IPageExtensions)?.getFixedContent()
            measureContent(it)
        }
        if (isActive) selectPage(index)
    }

    /** Replace a page's [content] by its [caption]. */
    fun replacePage(caption: String, content: Actor) = replacePage(getPageIndex(caption), content)

    /** Add a page!
     * @param caption Text to be shown on the header button (automatically translated), can later be used to reference the page in other calls.
     * @param content [Actor] to show in the lower area when this page is selected. Can optionally implement [IPageExtensions] to be notified of activation or deactivation.
     * @param icon Actor, typically an [Image], to show before the caption on the header button.
     * @param iconSize Size for [icon] - if not zero, the icon is wrapped to allow a [setSize] even on [Image] which ignores size.
     * @param insertBefore -1 to add at the end, or index of existing page to insert this before it.
     * @param secret Marks page as 'secret'. A password is asked once per [TabbedPager] and if it does not match the has passed in the constructor the page and all subsequent secret pages are dropped.
     * @param disabled Initial disabled state. Disabled pages cannot be selected even with [selectPage], their button is dimmed.
     * @param shortcutKey Optional keyboard key to associate.
     * @param syncScroll If on, the ScrollPanes for [content] and [fixed content][IPageExtensions.getFixedContent] will synchronize horizontally.
     * @return The new page's index or -1 if it could not be immediately added (secret).
     */
    fun addPage(
        caption: String,
        content: Actor? = null,
        icon: Actor? = null,
        iconSize: Float = 0f,
        insertBefore: Int = -1,
        secret: Boolean = false,
        disabled: Boolean = false,
        shortcutKey: KeyCharAndCode = KeyCharAndCode.UNKNOWN,
        scrollAlign: Int = Align.top,
        syncScroll: Boolean = true
    ): Int {
        // Build page descriptor and header button
        val page = PageState(
                caption = caption,
                content = content ?: Group(),
                fixedContent = (content as? IPageExtensions)?.getFixedContent(),
                disabled = disabled,
                icon = icon,
                iconSize = iconSize,
                shortcutKey = shortcutKey,
                scrollAlign = scrollAlign,
                syncScroll = syncScroll,
                pager = this
        )
        page.button.apply {
            isEnabled = !disabled
            onActivation {
                selectPage(page)
            }
            keyShortcuts.add(shortcutKey)
            addTooltip(shortcutKey, if (iconSize > 0f) iconSize else 18f)
            pack()
            if (height + 2 * headerPadding > headerHeight) {
                headerHeight = height + 2 * headerPadding
                if (activePage >= 0) this@TabbedPager.invalidateHierarchy()
            }
        }

        // Support 'secret' pages
        if (secret) {
            deferredSecretPages.addLast(page)
            return -1
        }

        return addAndShowPage(page, insertBefore)
    }

    /**
     * Add a "Close" button tho the Tab headers, with empty content which will invoke [action] when clicked
     */
    fun addClosePage(
        insertBefore: Int = -1,
        color: Color = Color(0.75f, 0.1f, 0.1f, 1f),
        action: ()->Unit
    ) {
        val index = addPage(Constants.close, EmptyClosePage(action), insertBefore = insertBefore)
        pages[index].button.color = color
    }

    /**
     * Activate any [secret][addPage] pages by asking for the password.
     *
     * If the parent of this Widget is a Popup, then this needs to be called _after_ the parent
     * is shown to ensure proper popup stacking.
     */
    fun askForPassword(secretHashCode: Int = 0) {
        class PassPopup(screen: BaseScreen, unlockAction: ()->Unit, lockAction: ()->Unit) : Popup(screen) {
            val passEntry = UncivTextField.create("Password")
            init {
                passEntry.isPasswordMode = true
                add(passEntry).row()
                addOKButton {
                    if (passEntry.text.hashCode() == secretHashCode) unlockAction() else lockAction()
                }
                this.keyboardFocus = passEntry
            }
        }

        if (!UncivGame.isCurrentInitialized() || askPasswordLock || deferredSecretPages.isEmpty()) return
        askPasswordLock = true  // race condition: Popup closes _first_, then deferredSecretPages is emptied -> parent shows and calls us again

        PassPopup(UncivGame.Current.screen!!, {
            addDeferredSecrets()
        }, {
            deferredSecretPages.clear()
        }).open(true)
    }

    //endregion
    //region Helper routines

    private fun getPageIndex(page: PageState) = pages.indexOf(page)

    private fun measureContent(page: PageState) {
        val dimFixedH = DimensionMeasurement(dimH.limit)
        val dimContentH = DimensionMeasurement(dimH.limit)
        dimW.measureWidth(page.fixedContent as? WidgetGroup)
        dimFixedH.measureHeight(page.fixedContent as? WidgetGroup)
        page.fixedHeight = dimFixedH.min
        dimW.measureWidth(page.content as? WidgetGroup)
        dimContentH.measureHeight(page.content as? WidgetGroup)
        dimH.combine(dimFixedH, dimContentH)
    }

    private fun addAndShowPage(page: PageState, insertBefore: Int): Int {
        // Update pages array and header table
        val newIndex: Int
        val buttonCell: Cell<Button>
        if (insertBefore >= 0 && insertBefore < pages.size) {
            newIndex = insertBefore
            pages.add(insertBefore, page)
            // Table.addActorAt breaks the Table, it's a Group method that updates children but not cells
            // So we add an empty cell and move cell actors around
            header.add()
            for (i in header.cells.size - 1 downTo insertBefore + 1) {
                val actor = header.removeActorAt(i - 1, true) as Button
                header.cells[i].setActor<Button>(actor)
            }
            header.cells[insertBefore].setActor<Button>(page.button)
            buttonCell = header.getCell(page.button)
        } else {
            newIndex = pages.size
            pages.add(page)
            buttonCell = header.add(page.button)
        }
        page.buttonX = if (newIndex == 0) 0f else pages[newIndex-1].run { buttonX + buttonW }
        page.buttonW = buttonCell.run { prefWidth + padLeft + padRight }
        for (i in newIndex + 1 until pages.size)
            pages[i].buttonX += page.buttonW

        measureContent(page)

        return newIndex
    }

    private fun addDeferredSecrets() {
        while (true) {
            val page = deferredSecretPages.removeFirstOrNull() ?: return
            addAndShowPage(page, -1)
        }
    }
}
