package com.example.registrazio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Il prototipo NON segue il tema di sistema: ha un tasto luna/sole nella topbar
 * che commuta `html[data-theme]`. Qui replichiamo lo stesso comportamento —
 * `darkTheme` arriva dallo stato dell'app, non da `isSystemInDarkTheme()`.
 *
 * Nessun dynamic color: la palette del prototipo è fissa e riconoscibile,
 * lasciarla ridipingere dal wallpaper la snaturerebbe.
 */
@Composable
fun RegiStrazioTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors

    // Schema M3 minimo: serve solo ai pochi componenti Material che usiamo
    // (ripple, selezione testo, cursore). Il resto passa da LocalAppColors.
    val m3 = if (darkTheme) {
        darkColorScheme(
            primary = appColors.accent,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            background = appColors.bg,
            onBackground = appColors.text,
            surface = appColors.surface,
            onSurface = appColors.text,
            error = appColors.danger
        )
    } else {
        lightColorScheme(
            primary = appColors.accent,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            background = appColors.bg,
            onBackground = appColors.text,
            surface = appColors.surface,
            onSurface = appColors.text,
            error = appColors.danger
        )
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = m3,
            typography = Typography,
            content = content
        )
    }
}

/** Scorciatoia: `AppTheme.colors.accent` invece di `LocalAppColors.current.accent`. */
object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}
