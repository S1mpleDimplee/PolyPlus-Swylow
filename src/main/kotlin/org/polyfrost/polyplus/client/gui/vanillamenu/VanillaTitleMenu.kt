@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.polyplus.client.gui.vanillamenu

//? if >= 26.1 {
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.SpriteIconButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import org.apache.logging.log4j.LogManager
import org.polyfrost.polyplus.client.PolyPlusMainMenuConfig
import org.polyfrost.polyplus.client.gui.MainMenuReplacement
import org.polyfrost.polyplus.client.gui.PolyPlusOneConfigIntegration
import org.polyfrost.polyplus.client.PolyPlusConfig
import org.polyfrost.polyplus.client.features.OnboardingFeatures
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme
import org.polyfrost.polyplus.client.social.SocialOverlay
import org.polyfrost.polyplus.client.utils.ClientPlatform
import org.polyfrost.polyplus.mixin.client.access.ScreenAccessor
import java.util.WeakHashMap

/**
 * The vanilla title screen with OneClient's buttons in vanilla style: Social, Wardrobe and Host World in a row under
 * Realms, Accounts, Quickplay, Mods (and Essential when it's installed) beside vanilla's icon buttons, and the
 * lobby / menu / notifications / theme switches in one dropdown at the top right.
 */
object VanillaTitleMenu {
    private val LOGGER = LogManager.getLogger("PolyPlus/VanillaTitleMenu")
    private val LATE_PHASE: Identifier = Identifier.fromNamespaceAndPath("polyplus", "vanilla_title_menu")

    private const val ROW = 24
    private const val SIZE = 20
    private const val GAP = 4
    private const val MARGIN = 4
    private const val DROPDOWN_WIDTH = 124
    private const val LOGO_BOTTOM = 80

    private val dropdowns = WeakHashMap<Screen, Dropdown>()

    private class Dropdown(val items: List<Button>) {
        var open = false
            set(value) {
                field = value
                items.forEach { it.visible = value }
            }
    }

    fun register() {
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE_PHASE)
        ScreenEvents.AFTER_INIT.register(LATE_PHASE) { _, screen, _, _ ->
            if (screen !is TitleScreen || MainMenuReplacement.replacesTitleScreen()) return@register
            runCatching { build(screen) }.onFailure { LOGGER.error("Couldn't add the OneClient buttons to the title screen", it) }
        }
    }

    private fun key(widget: AbstractWidget): String? = (widget.message.contents as? TranslatableContents)?.key

    private fun build(screen: TitleScreen) {
        val access = screen as ScreenAccessor
        val widgets = screen.children().filterIsInstance<AbstractWidget>()
        fun find(key: String) = widgets.firstOrNull { key(it) == key }

        if (PolyPlusMainMenuConfig.vanillaMenuButtons) {
            val singleplayer = find("menu.singleplayer")
            val multiplayer = find("menu.multiplayer")
            val anchor = find("menu.online") ?: multiplayer
            if (singleplayer != null && anchor != null) {
                val center = screen.width / 2
                val rowY = anchor.y + ROW
                // everything under Realms moves down a row, except the copyright line at the very bottom
                val below = widgets.filter { it.y >= rowY && it.y < screen.height - 10 }
                below.forEach { it.y += ROW }

                val third = (200 - 2 * GAP) / 3
                val row = listOf(
                    textButton("Social", "Friends, chats and parties") { SocialOverlay.open(screen) },
                    textButton("Wardrobe", "Cosmetics and your Minecraft capes") { PolyPlusOneConfigIntegration.openCosmetics() },
                    textButton("Host World", "Host a singleplayer world for your friends") { MenuPanelScreen.open(MenuPanelScreen.Panel.HostWorld) },
                )
                row.forEachIndexed { index, button ->
                    button.setRectangle(third, SIZE, center - 100 + index * (third + GAP), rowY)
                    access.polyplusAddRenderableWidget(button)
                }

                // vanilla's small icon buttons (friends, language, accessibility) share a row with OneClient's
                val iconRowY = (find("menu.options")?.y ?: (rowY + 2 * ROW)) - ROW
                val vanillaIcons = widgets.filter { it.y == iconRowY && it.width == SIZE && it.height == SIZE }
                val ours = buildList {
                    add(iconButton("accounts", "Accounts") { MenuPanelScreen.open(MenuPanelScreen.Panel.Accounts) })
                    add(iconButton("quickplay", "Quickplay") { ClientPlatform.setScreen(QuickplayScreen(screen)) })
                    add(iconButton("mods", "Mods") { PolyPlusOneConfigIntegration.openMods() })
                    if (FabricLoader.getInstance().isModLoaded("essential")) {
                        add(iconButton("essential", "Essential") { EssentialMenu.open(screen) })
                    }
                }
                ours.forEach { access.polyplusAddRenderableWidget(it) }
                val icons = ours.take(2) + vanillaIcons + ours.drop(2)
                val total = icons.size * SIZE + (icons.size - 1) * GAP
                icons.forEachIndexed { index, icon -> icon.setPosition(center - total / 2 + index * (SIZE + GAP), iconRowY) }

                // with the extra row the buttons may reach the copyright line, so the block moves up toward the logo
                val bottom = widgets.filter { it.y < screen.height - 10 }.maxOf { it.y + it.height }
                val overflow = bottom - (screen.height - 14)
                val room = singleplayer.y - LOGO_BOTTOM
                val lift = minOf(overflow, room)
                if (lift > 0) {
                    (widgets.filter { it.y >= singleplayer.y && it.y < screen.height - 10 } + row + ours).distinct().forEach { it.y -= lift }
                }
            }
        }

        addTopRight(screen, access)
    }

    private fun addTopRight(screen: TitleScreen, access: ScreenAccessor) {
        val entries = listOf<Pair<() -> Component, () -> Unit>>(
            { Component.literal("OneClient Lobby") } to { MainMenuReplacement.switchToLobby() },
            { Component.literal("PolyPlus Menu") } to { MainMenuReplacement.switchToPolyPlusMenu() },
            { Component.literal("Notifications") } to { MenuPanelScreen.open(MenuPanelScreen.Panel.Notifications) },
            { themeLabel() } to { toggleTheme() },
            { Component.literal("OneClient Settings") } to { PolyPlusOneConfigIntegration.openSettings() },
        )
        if (PolyPlusMainMenuConfig.vanillaMenuDropdown) {
            lateinit var dropdown: Dropdown
            val items = entries.mapIndexed { index, (label, action) ->
                lateinit var button: Button
                button = Button.builder(label()) {
                    action()
                    button.message = label()
                }.bounds(screen.width - DROPDOWN_WIDTH - MARGIN, MARGIN + SIZE + 2 + index * (SIZE + 1), DROPDOWN_WIDTH, SIZE).build()
                access.polyplusAddRenderableWidget(button)
            }
            dropdown = Dropdown(items)
            dropdown.open = false
            dropdowns[screen] = dropdown
            val toggle = iconButton("menu", "OneClient") { dropdown.open = !dropdown.open }
            toggle.setPosition(screen.width - SIZE - MARGIN, MARGIN)
            access.polyplusAddRenderableWidget(toggle)
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
                access.polyplusAddRenderableWidget(button)
                right -= width + 2
            }
        }
    }

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

    private fun iconButton(sprite: String, label: String, onPress: () -> Unit): SpriteIconButton {
        val button = SpriteIconButton.builder(Component.literal(label), { onPress() }, true)
            .sprite(Identifier.fromNamespaceAndPath("polyplus", "oneclient/$sprite"), 15, 15)
            .size(SIZE, SIZE)
            .build()
        button.setTooltip(Tooltip.create(Component.literal(label)))
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
