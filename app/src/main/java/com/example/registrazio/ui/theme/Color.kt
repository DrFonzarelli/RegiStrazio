package com.example.registrazio.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette tradotta 1:1 dalle CSS custom properties del prototipo
 * (`prova-app-v3-integrata.html`, blocchi `:root` e `html[data-theme="dark"]`).
 *
 * Non usiamo lo schema Material3: la maggior parte di questi ruoli
 * (surface-alt, border-strong, accent-soft, accent-ring, star, avatar...)
 * non ha uno slot corrispondente, e forzarli dentro M3 significherebbe
 * perdere i valori esatti. Passano invece da un CompositionLocal.
 */
@Immutable
data class AppColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentRing: Color,
    val danger: Color,
    val topbarBg: Color,
    val miniplayerBg: Color,
    val star: Color,
    // colori avatar usati dai commenti (chiave = iniziale autore nel prototipo)
    val avatarA: Color,
    val avatarM: Color,
    val avatarL: Color,
    val avatarT: Color,
    // palette scelta all'onboarding
    val paletteA: Color,
    val paletteM: Color,
    val paletteL: Color,
    val paletteT: Color,
    val paletteG: Color,
    val paletteR: Color,
    val paletteO: Color,
    val isDark: Boolean
) {
    /** Colore avatar per la lettera dell'autore, con fallback del prototipo (#8A8578). */
    fun avatarFor(key: String): Color = when (key.uppercase()) {
        "A" -> avatarA
        "M" -> avatarM
        "L" -> avatarL
        "T" -> avatarT
        else -> Color(0xFF8A8578)
    }

    /** Colore della palette onboarding a partire dalla chiave salvata sul profilo. */
    fun paletteFor(key: String): Color = when (key.lowercase()) {
        "a" -> paletteA
        "m" -> paletteM
        "l" -> paletteL
        "t" -> paletteT
        "g" -> paletteG
        "r" -> paletteR
        "o" -> paletteO
        else -> paletteT
    }
}

/** Chiavi della palette, nell'ordine in cui compaiono nel Gate del prototipo. */
val PALETTE_KEYS = listOf("a", "m", "l", "t", "g", "r", "o")

val LightAppColors = AppColors(
    bg = Color(0xFFF5F1EA),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEFEAE0),
    border = Color(0xFFE1DACB),
    borderStrong = Color(0xFFCFC5AF),
    text = Color(0xFF231C13),
    textSecondary = Color(0xFF6B5F4C),
    textMuted = Color(0xFFA2977E),
    accent = Color(0xFF3C6E64),
    accentSoft = Color(0xFFE4EEEB),
    accentRing = Color(0x383C6E64),      // rgba(60,110,100,0.22)
    danger = Color(0xFFB0472E),
    topbarBg = Color(0xEBF5F1EA),        // rgba(245,241,234,0.92)
    miniplayerBg = Color(0xF0FFFFFF),    // rgba(255,255,255,0.94)
    star = Color(0xFFC99A2E),
    avatarA = Color(0xFF6B4FA0),
    avatarM = Color(0xFFB5842B),
    avatarL = Color(0xFFB5502B),
    avatarT = Color(0xFF35618C),
    paletteA = Color(0xFF6B4FA0),
    paletteM = Color(0xFF9A6E22),
    paletteL = Color(0xFFB5502B),
    paletteT = Color(0xFF35618C),
    paletteG = Color(0xFF4B7B4E),
    paletteR = Color(0xFFB5466F),
    paletteO = Color(0xFF2A7A8C),
    isDark = false
)

val DarkAppColors = AppColors(
    bg = Color(0xFF161310),
    surface = Color(0xFF1F1B16),
    surfaceAlt = Color(0xFF262019),
    border = Color(0xFF332B21),
    borderStrong = Color(0xFF453A2C),
    text = Color(0xFFF3EDE1),
    textSecondary = Color(0xFFC2B79F),
    textMuted = Color(0xFF8A7F68),
    accent = Color(0xFF6FA99B),
    accentSoft = Color(0xFF22332E),
    accentRing = Color(0x476FA99B),      // rgba(111,169,155,0.28)
    danger = Color(0xFFE17857),
    topbarBg = Color(0xE6161310),        // rgba(22,19,16,0.9)
    miniplayerBg = Color(0xEB1F1B16),    // rgba(31,27,22,0.92)
    star = Color(0xFFE0B84A),
    avatarA = Color(0xFF9C87CF),
    avatarM = Color(0xFFD4A853),
    avatarL = Color(0xFFD4805A),
    avatarT = Color(0xFF6FA0C7),
    paletteA = Color(0xFF9C87CF),
    paletteM = Color(0xFFC49040),
    paletteL = Color(0xFFD4805A),
    paletteT = Color(0xFF6FA0C7),
    paletteG = Color(0xFF7FB77E),
    paletteR = Color(0xFFD47FA0),
    paletteO = Color(0xFF4FB8CE),
    isDark = true
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
