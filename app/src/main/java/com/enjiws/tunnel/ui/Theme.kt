package com.enjiws.tunnel.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palet: hitam, abu-abu, merah gelap. */
object EnjiwsColors {
    val Black = Color(0xFF0A0A0B)
    val Charcoal = Color(0xFF131315)
    val Panel = Color(0xFF1B1B1E)
    val Outline = Color(0xFF2C2C30)
    val Grey = Color(0xFF8D8D94)
    val GreyLight = Color(0xFFC4C4C9)
    val RedDark = Color(0xFF7A1717)
    val Red = Color(0xFFB32020)
    val RedGlow = Color(0xFFE23B3B)
    val Text = Color(0xFFF2F2F3)
}

@Composable
fun EnjiwsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EnjiwsColors.Red,
            onPrimary = EnjiwsColors.Text,
            background = EnjiwsColors.Black,
            onBackground = EnjiwsColors.Text,
            surface = EnjiwsColors.Charcoal,
            onSurface = EnjiwsColors.Text,
            surfaceVariant = EnjiwsColors.Panel,
            secondary = EnjiwsColors.Grey,
            outline = EnjiwsColors.Outline
        ),
        content = content
    )
}
