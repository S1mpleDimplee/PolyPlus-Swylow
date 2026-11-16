@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.polyplus.client.gui.vanillamenu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.components.ButtonStyle
import org.polyfrost.oneconfig.api.ui.v1.components.TextButton
import org.polyfrost.polyplus.client.PolyPlusMainMenuConfig
import org.polyfrost.polyplus.client.gui.SocialText
import kotlin.math.roundToInt

/**
 * Lets the player move the pieces of the OneClient menus around. Each menu has a scope ("lobby", "polyplus",
 * "vanilla-centered", "vanilla-sides"), each piece an id, and the offsets from where the piece normally sits are
 * saved in the main menu config, in dp for the Compose menus and GUI pixels for the vanilla one.
 */
object MenuLayoutEditor {
    /** The scope being edited right now, or null. */
    var editing by mutableStateOf<String?>(null)

    private val offsets = mutableStateMapOf<String, Offset>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        PolyPlusMainMenuConfig.menuLayoutOffsets.split(';').forEach { entry ->
            val key = entry.substringBefore('=', "")
            val values = entry.substringAfter('=', "").split(',').mapNotNull { it.toFloatOrNull() }
            if (key.isNotEmpty() && values.size == 2) offsets[key] = Offset(values[0], values[1])
        }
    }

    private fun save() {
        PolyPlusMainMenuConfig.menuLayoutOffsets = offsets.entries
            .filter { it.value != Offset.Zero }
            .joinToString(";") { "${it.key}=${it.value.x},${it.value.y}" }
        PolyPlusMainMenuConfig.save()
    }

    @JvmStatic
    fun offset(scope: String, id: String): Offset {
        load()
        return offsets["$scope/$id"] ?: Offset.Zero
    }

    @JvmStatic
    fun setOffset(scope: String, id: String, value: Offset) {
        load()
        offsets["$scope/$id"] = value
        save()
    }

    @JvmStatic
    fun isEditing(scope: String): Boolean = editing == scope

    @JvmStatic
    fun isHidden(scope: String, id: String): Boolean = "$scope/$id" in PolyPlusMainMenuConfig.menuHidden.split(',')

    @JvmStatic
    fun setHidden(scope: String, id: String, hidden: Boolean) {
        val list = PolyPlusMainMenuConfig.menuHidden.split(',').filter { it.isNotEmpty() && it != "$scope/$id" }
        PolyPlusMainMenuConfig.menuHidden = (if (hidden) list + "$scope/$id" else list).joinToString(",")
        PolyPlusMainMenuConfig.save()
    }

    @JvmStatic
    fun start(scope: String) {
        editing = scope
    }

    @JvmStatic
    fun stop() {
        editing = null
    }

    @JvmStatic
    fun reset(scope: String) {
        load()
        offsets.keys.filter { it.startsWith("$scope/") }.forEach { offsets.remove(it) }
        save()
        PolyPlusMainMenuConfig.menuHidden = PolyPlusMainMenuConfig.menuHidden.split(',').filter { it.isNotEmpty() && !it.startsWith("$scope/") }.joinToString(",")
        PolyPlusMainMenuConfig.save()
    }
}

/**
 * A piece of a Compose menu that can be moved in edit mode. [modifier] places it as usual (align, padding),
 * the saved offset moves it from there, and while editing an outline on top takes the drags.
 */
@Composable
fun EditableBlock(scope: String, id: String, label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val saved = MenuLayoutEditor.offset(scope, id)
    val density = LocalDensity.current
    Box(modifier.offset { with(density) { IntOffset(saved.x.dp.roundToPx(), saved.y.dp.roundToPx()) } }) {
        content()
        if (MenuLayoutEditor.isEditing(scope)) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color(0x223B6CFF), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF3B6CFF), RoundedCornerShape(6.dp))
                    .pointerInput(scope, id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val current = MenuLayoutEditor.offset(scope, id)
                            MenuLayoutEditor.setOffset(scope, id, Offset(current.x + drag.x / density.density, current.y + drag.y / density.density))
                        }
                    },
            ) {
                SocialText(label, 11.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            }
        }
    }
}

/** The bar shown at the top while a Compose menu is being edited: what to do, Reset and Done. */
@Composable
fun BoxScope.MenuEditorBar(scope: String) {
    if (!MenuLayoutEditor.isEditing(scope)) return
    Row(
        Modifier
            .align(Alignment.TopCenter)
            // under the menus' top bars, so it never covers their buttons
            .padding(top = 64.dp)
            .background(Color(0xE0101216), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF3B6CFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SocialText("Editing the menu: drag the blue boxes to move them", 13.sp, color = Color.White)
        TextButton("Reset", null, Modifier.height(28.dp), ButtonStyle.Secondary, true) { MenuLayoutEditor.reset(scope) }
        TextButton("Done", null, Modifier.height(28.dp), ButtonStyle.Primary, true) { MenuLayoutEditor.stop() }
    }
}
