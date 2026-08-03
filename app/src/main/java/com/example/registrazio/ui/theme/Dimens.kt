package com.example.registrazio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Raggi e misure ricorrenti del prototipo. I nomi seguono le CSS custom
 * properties originali per rendere immediato il confronto con l'HTML.
 */
object Radius {
    val lg = 16.dp   // --radius-lg : track-card
    val md = 12.dp   // --radius-md : folder-card, gate-option
    val sm = 10.dp   // --radius-sm : button, preview-box, add-box, comment-row
    val input = 8.dp // input / textarea
    val pill = 20.dp // chip-btn, sort-toggle, bulk-dl-btn, gate-action-btn
    val sheet = 20.dp // bottom sheet: 20px 20px 0 0

    val cardLg = RoundedCornerShape(lg)
    val cardMd = RoundedCornerShape(md)
    val cardSm = RoundedCornerShape(sm)
    val inputShape = RoundedCornerShape(input)
    val pillShape = RoundedCornerShape(pill)
    val sheetShape = RoundedCornerShape(topStart = sheet, topEnd = sheet)
}

/**
 * Larghezza massima della colonna app: `.app { max-width:480px }` nel prototipo.
 * Su telefono non ha effetto, ma tiene il layout identico su tablet/foldable.
 */
val AppMaxWidth = 480.dp

/** `main { padding:11px 11px calc(86px + safe-bottom) }` */
object MainPadding {
    val horizontal = 11.dp
    val top = 11.dp
    val bottomForMiniPlayer = 86.dp
}
