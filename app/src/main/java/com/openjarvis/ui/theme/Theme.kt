package com.openjarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette referenced by the existing Compose screens but absent from upstream sources. */
object VoidColor {
    val Void950 = Color(0xFF08080E)
    val Void900 = Color(0xFF101018)
    val Void800 = Color(0xFF181824)
    val Void700 = Color(0xFF242434)
    val Void600 = Color(0xFF343448)
    val Violet = Color(0xFFA78BFA)
    val VioletDim = Color(0xFF6D4BB5)
    val Cyan = Color(0xFF67E8F9)
    val Green = Color(0xFF86EFAC)
    val Red = Color(0xFFFCA5A5)
    val Amber = Color(0xFFFCD34D)
    val TextPrimary = Color(0xFFF4F4FA)
    val TextSecondary = Color(0xFFB4B4C8)
    val TextDisabled = Color(0xFF77778C)
    val BorderSubtle = Color(0xFF303042)
    val BorderGlow = Color(0xFF7858AA)
}

@Composable
fun OpenJarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VoidColor.Violet,
            onPrimary = VoidColor.Void950,
            secondary = VoidColor.Cyan,
            background = VoidColor.Void950,
            onBackground = VoidColor.TextPrimary,
            surface = VoidColor.Void900,
            onSurface = VoidColor.TextPrimary,
            onSurfaceVariant = VoidColor.TextSecondary,
            error = VoidColor.Red
        ),
        content = content
    )
}
