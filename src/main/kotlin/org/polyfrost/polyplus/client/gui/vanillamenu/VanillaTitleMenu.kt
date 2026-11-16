@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.polyplus.client.gui.vanillamenu

//? if >= 26.1 {
import androidx.compose.ui.geometry.Offset
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.SpriteIconButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import org.apache.logging.log4j.LogManager
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme
import org.polyfrost.polyplus.client.PolyPlusConfig
import org.polyfrost.polyplus.client.PolyPlusMainMenuConfig
import org.polyfrost.polyplus.client.VanillaMenuLayout
import org.polyfrost.polyplus.client.features.OnboardingFeatures
import org.polyfrost.polyplus.client.gui.InlineCanvas
import org.polyfrost.polyplus.client.gui.MENU_PREVIEW_YAW
import org.polyfrost.polyplus.client.gui.MainMenuReplacement
import org.polyfrost.polyplus.client.gui.PauseMenuPanel
import org.polyfrost.polyplus.client.gui.PolyPlusOneConfigIntegration
import org.polyfrost.polyplus.client.gui.preview.PlayerPreviewOffscreen
import org.polyfrost.polyplus.client.gui.preview.PlayerPreviewSource
import org.polyfrost.polyplus.client.lobby.StartupOverlay
import org.polyfrost.polyplus.client.social.SocialOverlay
import org.polyfrost.polyplus.client.utils.ClientPlatform
import org.polyfrost.polyplus.mixin.client.access.ScreenAccessor
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * The vanilla title screen with OneClient's buttons in vanilla style, in one of two layouts:
 * - Centered: Social, Wardrobe and Host World under Realms, Accounts and OneConfig beside vanilla's icon buttons,
 *   Quickplay as a panel on the left.
 * - Essential style: your skin on the left with Wardrobe under it, the OneClient buttons and Quickplay on the right,
 *   accounts at the top left.
 * Both have a bell and a settings dropdown at the top right, slide in after the intro, and every piece can be
 * moved with the menu editor.
 */
object VanillaTitleMenu {
    private val LOGGER = LogManager.getLogger("PolyPlus/VanillaTitleMenu")
    private val LATE_PHASE: Identifier = Identifier.fromNamespaceAndPath("polyplus", "vanilla_title_menu")

    private const val ROW = 24
    private const val SIZE = 20
    private const val GAP = 4
    private const val MARGIN = 4
    private const val COLUMN = 200
    private const val DROPDOWN_WIDTH = 124
    private const val LOGO_BOTTOM = 80
    private const val PREVIEW_RATIO = 0.7f
    private const val INTRO_ROW_DELAY = 0.07f
    private const val INTRO_SLIDE_SECONDS = 0.5f
    private const val INTRO_DISTANCE = 30

    /** The intro only plays the first time the title screen opens after the game starts. */
    private var introPending = true

    private class State(val scope: String) {
        val keys = LinkedHashMap<AbstractWidget, String>()
        val base = HashMap<AbstractWidget, Pair<Int, Int>>()
        var dropdown: List<Button> = emptyList()
        var dropdownToggle: AbstractWidget? = null
        var editBar: List<AbstractWidget> = emptyList()
        var preview: PreviewSlot? = null
        var dragging: String? = null
        var dragFrom = Offset.Zero
        var pressX = 0.0
        var pressY = 0.0
        var introStart = 0L
        var introRows: Map<AbstractWidget, Int> = emptyMap()
    }

    private val states = WeakHashMap<Screen, State>()

    fun register() {
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE_PHASE)
        ScreenEvents.AFTER_INIT.register(LATE_PHASE) { _, screen, _, _ ->
            if (screen !is TitleScreen || MainMenuReplacement.replacesTitleScreen()) return@register
            runCatching { build(screen) }.onFailure { LOGGER.error("Couldn't add the OneClient buttons to the title screen", it) }
        }
    }

    @JvmStatic
    fun scope(): String = when (PolyPlusMainMenuConfig.vanillaMenuLayout) {
        VanillaMenuLayout.CENTERED -> "vanilla-centered"
        VanillaMenuLayout.SIDES -> "vanilla-sides"
        VanillaMenuLayout.ONECLIENT -> "vanilla-oneclient"
    }

    private fun presetName(layout: VanillaMenuLayout): String = when (layout) {
        VanillaMenuLayout.CENTERED -> "Centered"
        VanillaMenuLayout.SIDES -> "Essential style"
        VanillaMenuLayout.ONECLIENT -> "OneClient style"
    }

    private fun key(widget: AbstractWidget): String =
        (widget.message.contents as? TranslatableContents)?.key ?: widget.message.string.ifEmpty { widget.javaClass.simpleName }

    // ---------------------------------------------------------------- layout

    private fun build(screen: TitleScreen) {
        val access = screen as ScreenAccessor
        val vanilla = screen.children().filterIsInstance<AbstractWidget>()
        val state = State(scope())
        states[screen] = state
        // vanilla's own pieces can be moved too, each on its own
        vanilla.filter { it.y < screen.height - 10 }.forEach { state.keys[it] = key(it) }

        if (PolyPlusMainMenuConfig.vanillaMenuButtons) {
            val built = when (PolyPlusMainMenuConfig.vanillaMenuLayout) {
                VanillaMenuLayout.SIDES -> buildSides(screen, access, vanilla, state)
                VanillaMenuLayout.ONECLIENT -> buildOneClient(screen, access, vanilla, state)
                VanillaMenuLayout.CENTERED -> false
            }
            if (!built) buildCentered(screen, access, vanilla, state)
        }
        addTopRight(screen, access, state)
        if (MenuLayoutEditor.isEditing(state.scope)) addEditBar(screen, access, state)

        for ((widget, _) in state.keys) state.base[widget] = widget.x to widget.y
        applyOffsets(state)
        // hidden pieces stay visible in the editor, so they can be shown again
        if (!MenuLayoutEditor.isEditing(state.scope)) {
            for ((widget, key) in state.keys) if (MenuLayoutEditor.isHidden(state.scope, key)) widget.visible = false
        }

        if (introPending && PolyPlusMainMenuConfig.introAnimation && !MenuLayoutEditor.isEditing(state.scope)) {
            introPending = false
            val rows = state.keys.keys.map { it.y }.distinct().sorted()
            state.introRows = state.keys.keys.associateWith { rows.indexOf(it.y) }
            state.introStart = -1L
            applyIntro(state, 0f)
        }
        introPending = false
    }

    private fun find(widgets: List<AbstractWidget>, key: String) = widgets.firstOrNull { (it.message.contents as? TranslatableContents)?.key == key }

    private fun buildCentered(screen: TitleScreen, access: ScreenAccessor, vanilla: List<AbstractWidget>, state: State) {
        val singleplayer = find(vanilla, "menu.singleplayer") ?: return
        val anchor = find(vanilla, "menu.online") ?: find(vanilla, "menu.multiplayer") ?: return
        val center = screen.width / 2
        val left = center - COLUMN / 2
        val rowY = anchor.y + ROW
        // everything under Realms moves down a row, except the copyright line at the very bottom
        vanilla.filter { it.y >= rowY && it.y < screen.height - 10 }.forEach { it.y += ROW }

        val third = (COLUMN - 2 * GAP) / 3
        val row = listOf(
            "social" to textButton("Social", "Friends, chats and parties") { SocialOverlay.open(screen) },
            "wardrobe" to textButton("Wardrobe", "Cosmetics and your Minecraft capes") { PolyPlusOneConfigIntegration.openCosmetics() },
            "host" to textButton("Host World", "Host a singleplayer world for your friends") { MenuPanelScreen.open(MenuPanelScreen.Panel.HostWorld) },
        )
        row.forEachIndexed { index, (id, button) ->
            // the last one takes the rounding so the row ends exactly where the 200 wide buttons end
            val x = left + index * (third + GAP)
            val w = if (index == row.lastIndex) left + COLUMN - x else third
            button.setRectangle(w, SIZE, x, rowY)
            add(access, state, button, "oc-$id")
        }

        // vanilla's small icon buttons share a row with OneClient's, spread over exactly the 200 wide column
        val iconRowY = (find(vanilla, "menu.options")?.y ?: (rowY + 2 * ROW)) - ROW
        val vanillaIcons = vanilla.filter { it.y == iconRowY && it.width == SIZE && it.height == SIZE }
        val leftSpace = left - 2 * MARGIN
        val panelWidth = minOf(150, leftSpace - 8)
        val quickplayPanel = panelWidth >= 80
        val ours = buildList {
            add("oc-accounts" to iconButton("accounts", "Accounts") { MenuPanelScreen.open(MenuPanelScreen.Panel.Accounts) })
            add("oc-oneconfig" to iconButton("mods", "OneConfig mod settings") { PolyPlusOneConfigIntegration.openMods() })
            if (FabricLoader.getInstance().isModLoaded("essential")) add("oc-essential" to iconButton("essential", "Essential") { EssentialMenu.open(screen) })
        }
        ours.forEach { (id, widget) -> add(access, state, widget, id) }
        val icons = ours.take(1).map { it.second } + vanillaIcons + ours.drop(1).map { it.second }
        spread(icons, left, COLUMN, iconRowY)

        // with the extra row the buttons may reach the copyright line, so the block moves up toward the logo
        val block = (vanilla.filter { it.y >= singleplayer.y && it.y < screen.height - 10 } + row.map { it.second } + ours.map { it.second }).distinct()
        val overflow = block.maxOf { it.y + it.height } - (screen.height - 14)
        val lift = minOf(overflow, singleplayer.y - LOGO_BOTTOM)
        if (lift > 0) block.forEach { it.y -= lift }

        if (quickplayPanel) addQuickplay(screen, access, state, (left - panelWidth) / 2, singleplayer.y, panelWidth)
    }

    /**
     * Like the OneClient menu: Quickplay on the left, your skin, account and the OneClient buttons on the right,
     * the vanilla buttons in the middle. False when the screen is too narrow for the side columns.
     */
    private fun buildOneClient(screen: TitleScreen, access: ScreenAccessor, vanilla: List<AbstractWidget>, state: State): Boolean {
        val singleplayer = find(vanilla, "menu.singleplayer") ?: return false
        val center = screen.width / 2
        val sideSpace = center - COLUMN / 2
        if (sideSpace < 96) return false
        val columnWidth = minOf(140, sideSpace - 16)
        val leftX = (sideSpace - columnWidth) / 2
        val rightX = center + COLUMN / 2 + (sideSpace - columnWidth) / 2
        addQuickplay(screen, access, state, leftX, singleplayer.y - 10, columnWidth)

        val previewHeight = 64
        val buttons = 4
        val top = (singleplayer.y - 40).coerceAtLeast(MARGIN + SIZE + GAP)
            .coerceAtMost(screen.height - 14 - previewHeight - buttons * (SIZE + 2))
        val preview = PreviewSlot(rightX, top, columnWidth, previewHeight)
        state.preview = preview
        access.polyplusAddRenderableWidget(preview)
        state.keys[preview] = "player"
        val font = Minecraft.getInstance().font
        val name = ClientPlatform.localPlayerName() + "  \u25BE"
        val column = listOf(
            "player" to Button.builder(Component.literal(font.plainSubstrByWidth(name, columnWidth - 8))) { MenuPanelScreen.open(MenuPanelScreen.Panel.Accounts) }
                .tooltip(Tooltip.create(Component.literal("Switch account"))).build(),
            "player" to textButton("Host World", "Host a singleplayer world for your friends") { MenuPanelScreen.open(MenuPanelScreen.Panel.HostWorld) },
            "player" to textButton("Social", "Friends, chats and parties") { SocialOverlay.open(screen) },
            "player" to textButton("Wardrobe", "Cosmetics and your Minecraft capes") { PolyPlusOneConfigIntegration.openCosmetics() },
        )
        column.forEachIndexed { index, (key, button) ->
            button.setRectangle(columnWidth, SIZE, rightX, top + previewHeight + 2 + index * (SIZE + 2))
            add(access, state, button, key)
        }

        // OneConfig (and Essential) next to vanilla's small icon buttons
        val iconRowY = (find(vanilla, "menu.options")?.y ?: (singleplayer.y + 4 * ROW)) - ROW
        val vanillaIcons = vanilla.filter { it.y == iconRowY && it.width == SIZE && it.height == SIZE }
        val ours = buildList {
            add(iconButton("mods", "OneConfig mod settings") { PolyPlusOneConfigIntegration.openMods() })
            if (FabricLoader.getInstance().isModLoaded("essential")) add(iconButton("essential", "Essential") { EssentialMenu.open(screen) })
        }
        ours.forEachIndexed { index, widget -> add(access, state, widget, if (index == 0) "oc-oneconfig" else "oc-essential") }
        spread(vanillaIcons + ours, center - COLUMN / 2, COLUMN, iconRowY)
        return true
    }

    /** Essential's layout. False when the screen is too narrow for the side columns. */
    private fun buildSides(screen: TitleScreen, access: ScreenAccessor, vanilla: List<AbstractWidget>, state: State): Boolean {
        val singleplayer = find(vanilla, "menu.singleplayer") ?: return false
        val center = screen.width / 2
        val sideSpace = center - COLUMN / 2
        if (sideSpace < 96) return false
        val columnWidth = minOf(140, sideSpace - 16)
        val rightX = center + COLUMN / 2 + (sideSpace - columnWidth) / 2

        val buttons = listOf(
            "host" to textButton("Host World", "Host a singleplayer world for your friends") { MenuPanelScreen.open(MenuPanelScreen.Panel.HostWorld) },
            "social" to textButton("Social", "Friends, chats and parties") { SocialOverlay.open(screen) },
            "oneconfig" to textButton("OneConfig", "OneConfig mod settings") { PolyPlusOneConfigIntegration.openMods() },
        ) + if (FabricLoader.getInstance().isModLoaded("essential")) {
            listOf("essential" to textButton("Essential", "Essential's menu") { EssentialMenu.open(screen) })
        } else {
            emptyList()
        }
        // the column moves up when the buttons and Quickplay wouldn't fit above the copyright line
        val quickplayHeight = SIZE + 2 + QuickplayPanel.entries().size * (QuickplayPanel.ROW_HEIGHT + 2)
        val columnTop = minOf(singleplayer.y, screen.height - 14 - buttons.size * ROW - 6 - quickplayHeight).coerceAtLeast(MARGIN + SIZE + GAP)
        buttons.forEachIndexed { index, (id, button) ->
            button.setRectangle(columnWidth, SIZE, rightX, columnTop + index * ROW)
            add(access, state, button, "oc-$id")
        }
        addQuickplay(screen, access, state, rightX, columnTop + buttons.size * ROW + 6, columnWidth)

        // your skin on the left, Wardrobe right under it
        val previewWidth = minOf(150, sideSpace - 16)
        val previewHeight = (previewWidth / PREVIEW_RATIO).roundToInt().coerceAtMost(screen.height - singleplayer.y - 40)
        val previewX = (sideSpace - previewWidth) / 2
        val previewY = singleplayer.y - 16
        val preview = PreviewSlot(previewX, previewY, previewWidth, previewHeight)
        state.preview = preview
        access.polyplusAddRenderableWidget(preview)
        state.keys[preview] = "player"
        val wardrobe = textButton("Wardrobe", "Cosmetics and your Minecraft capes") { PolyPlusOneConfigIntegration.openCosmetics() }
        wardrobe.setRectangle(minOf(previewWidth, 100), SIZE, previewX + (previewWidth - minOf(previewWidth, 100)) / 2, previewY + previewHeight + 4)
        add(access, state, wardrobe, "player")

        // accounts at the top left, like Essential's account switcher
        val font = Minecraft.getInstance().font
        val name = ClientPlatform.localPlayerName() + "  ▾"
        val accounts = Button.builder(Component.literal(name)) { MenuPanelScreen.open(MenuPanelScreen.Panel.Accounts) }
            .tooltip(Tooltip.create(Component.literal("Switch account")))
            .bounds(MARGIN, MARGIN, font.width(name) + 16, SIZE).build()
        add(access, state, accounts, "oc-accounts")
        return true
    }

    private fun addQuickplay(screen: TitleScreen, access: ScreenAccessor, state: State, x: Int, y: Int, width: Int) {
        val entries = QuickplayPanel.entries()
        if (entries.isEmpty()) return
        lateinit var rows: List<AbstractWidget>
        val header = QuickplayPanel.header(width) { rows.forEach { it.visible = QuickplayPanel.expanded } }
        header.setPosition(x, y)
        add(access, state, header, "quickplay")
        rows = entries.mapIndexed { index, server ->
            QuickplayPanel.Row(screen, server, width).also {
                it.setPosition(x, y + SIZE + 2 + index * (QuickplayPanel.ROW_HEIGHT + 2))
                it.visible = QuickplayPanel.expanded
                add(access, state, it, "quickplay")
            }
        }
    }

    private fun addTopRight(screen: TitleScreen, access: ScreenAccessor, state: State) {
        val entries = listOf<Pair<() -> Component, () -> Unit>>(
            { Component.literal("OneClient Lobby") } to { MainMenuReplacement.switchToLobby() },
            { Component.literal("PolyPlus Menu") } to { MainMenuReplacement.switchToPolyPlusMenu() },
            { Component.literal("Notifications") } to { MenuPanelScreen.open(MenuPanelScreen.Panel.Notifications) },
            { themeLabel() } to { toggleTheme() },
            { Component.literal("Edit Main Menu") } to { EditMenuChooser.open() },
            { Component.literal("OneClient Settings") } to { PolyPlusOneConfigIntegration.openSettings() },
        )
        val toggleX = screen.width - SIZE - MARGIN
        val bell = iconButton("bell", "Notifications") { MenuPanelScreen.open(MenuPanelScreen.Panel.Notifications) }
        if (PolyPlusMainMenuConfig.vanillaMenuDropdown) {
            val items = entries.mapIndexed { index, (label, action) ->
                lateinit var button: Button
                button = Button.builder(label()) {
                    action()
                    button.message = label()
                }.bounds(screen.width - DROPDOWN_WIDTH - MARGIN, MARGIN + SIZE + GAP + index * SIZE, DROPDOWN_WIDTH, SIZE).build()
                button.visible = false
                add(access, state, button, "topright")
                button
            }
            state.dropdown = items
            val toggle = iconButton("sliders", "OneClient", tooltip = false) { items.forEach { it.visible = !it.visible } }
            toggle.setPosition(toggleX, MARGIN)
            add(access, state, toggle, "topright")
            state.dropdownToggle = toggle
            bell.setPosition(toggleX - SIZE - GAP, MARGIN)
            add(access, state, bell, "topright")
        } else {
            // all of them in a row along the top, right aligned
            val font = Minecraft.getInstance().font
            var right = screen.width - MARGIN
            for ((label, action) in entries.reversed()) {
                val width = font.width(label()) + 12
                lateinit var button: Button
                button = Button.builder(label()) {
                    action()
                    button.message = label()
                }.bounds(right - width, MARGIN, width, SIZE).build()
                add(access, state, button, "topright")
                right -= width + 2
            }
        }
    }

    private fun addEditBar(screen: TitleScreen, access: ScreenAccessor, state: State) {
        val current = PolyPlusMainMenuConfig.vanillaMenuLayout
        val barLeft = screen.width / 2 - (130 + 60 + 60 + 2 * GAP) / 2
        val preset = Button.builder(Component.literal("Preset: " + presetName(current))) {
            val next = VanillaMenuLayout.entries[(current.ordinal + 1) % VanillaMenuLayout.entries.size]
            PolyPlusMainMenuConfig.vanillaMenuLayout = next
            PolyPlusMainMenuConfig.save()
            MenuLayoutEditor.start(scope())
            ClientPlatform.setScreen(TitleScreen())
        }.tooltip(Tooltip.create(Component.literal("Switch to another premade layout, then move things from there"))).bounds(barLeft, MARGIN, 130, SIZE).build()
        access.polyplusAddRenderableWidget(preset)
        val done = Button.builder(Component.literal("Done")) { MenuLayoutEditor.stop(); ClientPlatform.setScreen(TitleScreen()) }
            .bounds(barLeft + 130 + 60 + 2 * GAP, MARGIN, 60, SIZE).build()
        val reset = Button.builder(Component.literal("Reset")) { MenuLayoutEditor.reset(state.scope); ClientPlatform.setScreen(TitleScreen()) }
            .bounds(barLeft + 130 + GAP, MARGIN, 60, SIZE).build()
        access.polyplusAddRenderableWidget(reset)
        access.polyplusAddRenderableWidget(done)
        state.editBar = listOf(preset, reset, done)
    }

    private fun add(access: ScreenAccessor, state: State, widget: AbstractWidget, key: String) {
        access.polyplusAddRenderableWidget(widget)
        state.keys[widget] = key
    }

    private fun spread(icons: List<AbstractWidget>, left: Int, width: Int, y: Int) {
        if (icons.isEmpty()) return
        if (icons.size == 1) {
            icons[0].setPosition(left + (width - SIZE) / 2, y)
            return
        }
        val step = (width - SIZE).toFloat() / (icons.size - 1)
        // never further apart than a normal gap, so a short row stays together in the middle
        val used = if (step > SIZE + GAP) SIZE + GAP.toFloat() else step
        val start = left + (width - (used * (icons.size - 1) + SIZE)) / 2f
        icons.forEachIndexed { index, icon -> icon.setPosition((start + index * used).roundToInt(), y) }
    }

    // ---------------------------------------------------------------- moving pieces

    private fun applyOffsets(state: State) {
        for ((widget, key) in state.keys) {
            val (x, y) = state.base[widget] ?: continue
            val offset = MenuLayoutEditor.offset(state.scope, key)
            widget.setPosition(x + offset.x.roundToInt(), y + offset.y.roundToInt())
        }
    }

    private fun applyIntro(state: State, t: Float) {
        var done = true
        for ((widget, row) in state.introRows) {
            val (x, y) = state.base[widget] ?: continue
            val offset = MenuLayoutEditor.offset(state.scope, state.keys[widget] ?: continue)
            val p = easeOut(((t - row * INTRO_ROW_DELAY) / INTRO_SLIDE_SECONDS).coerceIn(0f, 1f))
            if (p < 1f) done = false
            widget.setPosition(x + offset.x.roundToInt(), y + offset.y.roundToInt() + ((1f - p) * INTRO_DISTANCE).roundToInt())
            widget.setAlpha(p)
        }
        if (done && t > 0f) {
            state.introStart = 0L
            state.introRows.keys.forEach { it.setAlpha(1f) }
        }
    }

    private fun easeOut(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    /** Called before the title screen draws: the intro, dragging in the editor, and Quickplay's pings. */
    @JvmStatic
    fun frameStart(screen: Screen, mouseX: Int, mouseY: Int) {
        val state = states[screen] ?: return
        QuickplayPanel.tick()
        if (state.introStart != 0L) {
            // the slide starts once the OneClient intro has opened the curtain
            if (state.introStart == -1L) {
                if (StartupOverlay.active) {
                    applyIntro(state, 0f)
                    return
                }
                state.introStart = System.nanoTime()
            }
            applyIntro(state, (System.nanoTime() - state.introStart) / 1_000_000_000f)
        }
        val dragging = state.dragging ?: return
        val offset = Offset(state.dragFrom.x + (mouseX - state.pressX).toFloat(), state.dragFrom.y + (mouseY - state.pressY).toFloat())
        for ((widget, key) in state.keys) {
            if (key != dragging) continue
            val (x, y) = state.base[widget] ?: continue
            widget.setPosition(x + offset.x.roundToInt(), y + offset.y.roundToInt())
        }
        if (!PauseMenuPanel.leftDown()) {
            state.dragging = null
            MenuLayoutEditor.setOffset(state.scope, dragging, offset)
        }
    }

    /** Called after the title screen draws: your skin, and the outlines while editing. */
    @JvmStatic
    fun frameEnd(screen: Screen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val state = states[screen] ?: return
        state.preview?.let { drawPreview(screen, graphics, it) }
        // drawn again last, so the splash text and anything else can't end up on top of the open dropdown
        state.dropdown.filter { it.visible }.forEach { it.extractRenderState(graphics, mouseX, mouseY, a) }
        if (!MenuLayoutEditor.isEditing(state.scope)) return
        val font = Minecraft.getInstance().font
        val groups = state.keys.entries.filter { it.key.visible }.groupBy({ it.value }, { it.key })
        for ((key, widgets) in groups) {
            val x0 = widgets.minOf { it.x } - 1
            val y0 = widgets.minOf { it.y } - 1
            val x1 = widgets.maxOf { it.x + it.width } + 1
            val y1 = widgets.maxOf { it.y + it.height } + 1
            val hidden = MenuLayoutEditor.isHidden(state.scope, key)
            val color = when {
                key == state.dragging -> 0xFFFFFFFF.toInt()
                hidden -> 0xFFE05555.toInt()
                else -> 0xFF3B6CFF.toInt()
            }
            graphics.fill(x0, y0, x1, y1, if (hidden) 0x88401010.toInt() else 0x223B6CFF)
            graphics.outline(x0, y0, x1 - x0, y1 - y0, color)
            if (hidden) graphics.text(font, "hidden", x0 + 2, y0 + 2, 0xFFFF8888.toInt())
        }
        val hint = "Drag to move. Right click to hide or show. Reset puts it all back."
        val hintX = screen.width / 2 - font.width(hint) / 2
        val hintY = MARGIN + SIZE + 6
        graphics.fill(hintX - 4, hintY - 3, hintX + font.width(hint) + 4, hintY + 11, 0xC0000000.toInt())
        graphics.text(font, hint, hintX, hintY, 0xFFFFFFFF.toInt())
    }

    /** True when the click was used here (editor drag), so the screen shouldn't handle it. */
    @JvmStatic
    fun mouseClicked(screen: Screen, event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        val button = event.button()
        val state = states[screen] ?: return false
        if (!MenuLayoutEditor.isEditing(state.scope)) {
            // the open dropdown is on top, so it gets the click before any button under it
            state.dropdown.firstOrNull { it.visible && it.isMouseOver(mouseX, mouseY) }?.let { return it.mouseClicked(event, doubleClick) }
            // a click outside the open dropdown closes it
            val open = state.dropdown.any { it.visible }
            if (open && state.dropdown.none { it.isMouseOver(mouseX, mouseY) } && state.dropdownToggle?.isMouseOver(mouseX, mouseY) != true) {
                state.dropdown.forEach { it.visible = false }
            }
            return false
        }
        if (state.editBar.any { it.isMouseOver(mouseX, mouseY) }) return false
        val hit = state.keys.entries.lastOrNull { (widget, _) ->
            widget.visible &&
                mouseX >= widget.x && mouseX < widget.x + widget.width && mouseY >= widget.y && mouseY < widget.y + widget.height
        } ?: return true
        if (button == 1) {
            MenuLayoutEditor.setHidden(state.scope, hit.value, !MenuLayoutEditor.isHidden(state.scope, hit.value))
            return true
        }
        state.dragging = hit.value
        state.dragFrom = MenuLayoutEditor.offset(state.scope, hit.value)
        state.pressX = mouseX
        state.pressY = mouseY
        return true
    }

    // ---------------------------------------------------------------- your skin

    /** Holds the place of the player preview so it lays out and moves like the buttons; drawn in [frameEnd]. */
    private class PreviewSlot(x: Int, y: Int, width: Int, height: Int) : AbstractWidget(x, y, width, height, Component.literal("Player")) {
        init {
            active = false
        }

        override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) = Unit

        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private val inline by lazy { InlineCanvas("title_preview") }

    private val preview by lazy {
        PlayerPreviewOffscreen.register().apply {
            vanillaHost = true
            source = PlayerPreviewSource.LocalLive
            autoSpin = false
            // centered in its box, which the model (1.8 blocks tall) fills to about 85%
            verticalAnchor = 0.5f
            initialYaw = MENU_PREVIEW_YAW
        }
    }

    private fun drawPreview(screen: Screen, graphics: GuiGraphicsExtractor, slot: PreviewSlot) {
        if (screen.width <= 0 || screen.height <= 0) return
        val entry = preview
        entry.modelScale = 0.47f
        entry.fw = slot.width / screen.width.toFloat()
        entry.fh = slot.height / screen.height.toFloat()
        // the canvas draws in framebuffer pixels
        val scale = Minecraft.getInstance().window.width / screen.width.toFloat()
        inline.draw(graphics) { canvas ->
            PlayerPreviewOffscreen.drawFaded(entry, canvas, slot.x * scale, slot.y * scale, slot.width * scale, slot.height * scale, 0f)
        }
    }

    // ---------------------------------------------------------------- theme and helpers

    private fun dark(): Boolean = PolyPlusMainMenuConfig.vanillaDarkTheme

    private fun themeLabel(): Component = Component.literal(if (dark()) "Light Theme" else "Dark Theme")

    /** One switch for both: the OneConfig theme and the dark vanilla buttons. */
    fun toggleTheme() {
        val goingDark = !dark()
        PolyPlusMainMenuConfig.vanillaDarkTheme = goingDark
        PolyPlusMainMenuConfig.save()
        // the OneConfig theme follows, so the lobby, PolyPlus menu and social menu match the vanilla buttons
        val target = OnboardingFeatures.themeVariant(!goingDark) ?: return
        PolyPlusConfig.onboardingLightTheme = !goingDark
        OneConfigTheme.activate(target)
        PolyPlusConfig.save()
    }

    private fun textButton(label: String, tooltip: String, onPress: () -> Unit): Button =
        Button.builder(Component.literal(label)) { onPress() }.tooltip(Tooltip.create(Component.literal(tooltip))).build()

    private fun iconButton(sprite: String, label: String, tooltip: Boolean = true, onPress: () -> Unit): SpriteIconButton {
        val button = SpriteIconButton.builder(Component.literal(label), { onPress() }, true)
            .sprite(Identifier.fromNamespaceAndPath("polyplus", "oneclient/$sprite"), 15, 15)
            .size(SIZE, SIZE)
            .build()
        if (tooltip) button.setTooltip(Tooltip.create(Component.literal(label)))
        return button
    }

    internal fun toast(title: String, body: String) {
        val mc = Minecraft.getInstance()
        SystemToast.add(
            //? if >= 26.2 {
            mc.gui.toastManager(),
            //?} else {
            /*mc.toastManager,
            *///?}
            SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(body))
    }
}

/** Essential has no public API for its menus, so its screens are found by name and opened if they're there. */
internal object EssentialMenu {
    private val LOGGER = LogManager.getLogger("PolyPlus/EssentialMenu")
    private val CANDIDATES = listOf(
        "gg.essential.gui.friends.SocialMenu",
        "gg.essential.gui.wardrobe.Wardrobe",
        "gg.essential.gui.menu.EssentialMenu",
    )

    fun open(parent: Screen) {
        for (name in CANDIDATES) {
            val screen = runCatching { create(name) }.onFailure { LOGGER.debug("Essential screen {} not available", name, it) }.getOrNull()
            if (screen != null) {
                ClientPlatform.setScreen(screen)
                return
            }
        }
        LOGGER.warn("Couldn't find an Essential menu to open")
        VanillaTitleMenu.toast("Essential", "Couldn't open Essential's menu from here, use Essential's own buttons.")
        ClientPlatform.setScreen(parent)
    }

    private fun create(name: String): Screen? {
        val type = Class.forName(name)
        for (constructor in type.constructors.sortedBy { it.parameterCount }) {
            if (constructor.parameterTypes.any { it.isPrimitive }) continue
            val made = runCatching { constructor.newInstance(*arrayOfNulls<Any>(constructor.parameterCount)) }.getOrNull()
            if (made is Screen) return made
        }
        return null
    }
}
//?}
