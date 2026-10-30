package com.noki.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class PcDialogTest {
    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun `dialog traps keyboard focus closes on Escape and returns keys to its previous owner`() {
        val visible = mutableStateOf(false)
        val fullScreen = mutableStateOf(false)
        val previousFocus = FocusRequester()
        var backgroundKeys = 0
        var contentBounds = Rect.Zero
        val scene = ImageComposeScene(1000, 650, density = Density(1f)) {
            DesktopTheme(null) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxSize().focusRequester(previousFocus)
                        .onPreviewKeyEvent { backgroundKeys++; false }.focusable())
                    if (visible.value) PcDialog("Dialog", { visible.value = false }, fullScreen = fullScreen.value) {
                        Box(Modifier.fillMaxWidth().height(40.dp).onGloballyPositioned { contentBounds = it.boundsInWindow() })
                        PcInput("", {}, "Input")
                    }
                }
            }
        }
        var frame = 0L
        fun render() { repeat(3) { scene.render(++frame * 100_000_000L).close() } }
        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            render()
        }
        try {
            render(); previousFocus.requestFocus(); render()
            key(Key.DirectionRight)
            assertTrue(backgroundKeys > 0)
            backgroundKeys = 0
            visible.value = true; render()
            assertEquals(2, scene.semanticsOwners.size)
            assertEquals(264f, contentBounds.left, .1f)
            assertEquals(472f, contentBounds.width, .1f)
            scene.render(++frame * 100_000_000L).use { image ->
                assertEquals(0x737373, image.getRGB(10, 10) and 0xffffff)
                assertEquals(0x737373, image.getRGB(989, 639) and 0xffffff)
            }
            repeat(12) { key(Key.Tab) }
            assertEquals(0, backgroundKeys)
            fullScreen.value = true; render()
            assertEquals(40f, contentBounds.left, .1f)
            assertEquals(920f, contentBounds.width, .1f)
            key(Key.Escape)
            assertFalse(visible.value)
            assertEquals(1, scene.semanticsOwners.size)
            key(Key.DirectionRight)
            assertTrue(backgroundKeys > 0)
        } finally { scene.close() }
    }
}
