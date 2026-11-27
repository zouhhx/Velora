package com.zouhu.speedlimit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalSlmColors = staticCompositionLocalOf { DarkSlmColors }

@Composable
fun SpeedLimitMeterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val slm = if (darkTheme) DarkSlmColors else LightSlmColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = slm.mint,
            onPrimary = Color.White,
            background = slm.bg,
            onBackground = slm.text,
            surface = slm.surface,
            onSurface = slm.text,
            error = slm.red,
        )
    } else {
        lightColorScheme(
            primary = slm.mint,
            onPrimary = Color.White,
            background = slm.bg,
            onBackground = slm.text,
            surface = slm.surface,
            onSurface = slm.text,
            error = slm.red,
        )
    }
    CompositionLocalProvider(LocalSlmColors provides slm) {
        MaterialTheme(colorScheme = scheme, typography = SlmTypography, content = content)
    }
}