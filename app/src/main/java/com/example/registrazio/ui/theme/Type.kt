package com.example.registrazio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Il prototipo usa lo stack di sistema (`-apple-system, Roboto, …`), che su
 * Android corrisponde a [FontFamily.Default]. Le dimensioni non seguono una
 * scala tipografica: ogni componente ha il suo valore preciso (14.5px per i
 * titoli delle card, 12.5px per i commenti, 11px per le etichette…), quindi
 * restano dichiarate nei singoli composable.
 *
 * Qui teniamo solo una base coerente. Nota il `letterSpacing = 0.sp`:
 * il default Material aggiunge 0.5sp che il prototipo non ha e che
 * allargherebbe visibilmente ogni riga di testo.
 */
private val Base = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp
)

val Typography = Typography(
    bodyLarge = Base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = Base.copy(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = Base.copy(fontSize = 12.sp, lineHeight = 17.sp),
    titleMedium = Base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.16).sp),
    labelSmall = Base.copy(fontSize = 11.sp, lineHeight = 15.sp)
)
