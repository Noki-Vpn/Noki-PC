package com.noki.vpn.ui
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
private val SecurityTextPrimary = com.noki.desktop.DesktopUiKit.Text
internal const val SecurityPasswordLockPath =
    "M832 464h-68V240c0-70.7-57.3-128-128-128H388c-70.7 0-128 57.3-128 128v224h-68c-17.7 0-32 14.3-32 32v384c0 17.7 14.3 32 32 32h640c17.7 0 32-14.3 32-32V496c0-17.7-14.3-32-32-32zM332 240c0-30.9 25.1-56 56-56h248c30.9 0 56 25.1 56 56v224H332V240zm460 600H232V536h560v304zM484 701v53c0 4.4 3.6 8 8 8h40c4.4 0 8-3.6 8-8v-53a48.01 48.01 0 10-56 0z"
@Composable
internal fun SecurityPasswordIcon(modifier: Modifier = Modifier) {
    val lockPath = remember { PathParser().parsePathString(SecurityPasswordLockPath).toPath() }
    Canvas(modifier = modifier) {
        scale(
            scaleX = size.width / 896f,
            scaleY = size.height / 896f,
            pivot = Offset.Zero,
        ) {
            translate(left = -64f, top = -64f) {
                drawPath(path = lockPath, color = SecurityTextPrimary)
            }
        }
    }
}
