package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = Blue600,
    onPrimary = White,
    primaryContainer = Blue800,
    onPrimaryContainer = Blue100,
    secondary = Slate100,
    onSecondary = Slate900,
    secondaryContainer = Slate700,
    onSecondaryContainer = Slate100,
    background = Slate900,
    onBackground = SurfaceBg,
    surface = Slate900,
    onSurface = SurfaceBg,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate100,
    outline = Slate600
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Blue600,
    onPrimary = White,
    primaryContainer = Blue100,
    onPrimaryContainer = Blue800,
    secondary = Slate600,
    onSecondary = White,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate700,
    background = SurfaceBg,
    onBackground = Slate900,
    surface = White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate500,
    outline = Slate200
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disabling dynamicColor by default to guarantee the Professional Polish branding is displayed perfectly
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
