package com.carijajan.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary          = OrangeAmber,
    onPrimary        = OnOrange,
    primaryContainer = OrangeAmberLight,
    secondary        = TealGreen,
    onSecondary      = OnTeal,
    secondaryContainer = TealGreenLight,
    background       = Cream,
    surface          = SurfaceLight,
    surfaceVariant   = SurfaceVariant,
    error            = ErrorRed,
)

private val DarkColorScheme = darkColorScheme(
    primary          = OrangeAmberLight,
    onPrimary        = DarkBackground,
    primaryContainer = OrangeAmberDark,
    secondary        = TealGreenLight,
    onSecondary      = DarkBackground,
    secondaryContainer = TealGreenDark,
    background       = DarkBackground,
    surface          = DarkSurface,
    surfaceVariant   = DarkSurfaceVar,
    error            = Color(0xFFEF9A9A),
)

@Composable
fun CariJajanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,   // nonaktifkan dynamic color agar brand konsisten
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
