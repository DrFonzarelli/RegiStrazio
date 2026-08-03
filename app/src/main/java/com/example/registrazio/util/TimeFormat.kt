package com.example.registrazio.util

import kotlin.math.roundToInt

/** `secToLabel` del prototipo: 95 → "1:35". */
fun secToLabel(seconds: Float): String {
    val s = seconds.coerceAtLeast(0f).roundToInt()
    val m = s / 60
    val rem = s % 60
    return "$m:${if (rem < 10) "0" else ""}$rem"
}

fun secToLabel(seconds: Int): String = secToLabel(seconds.toFloat())

/** `labelToSec` del prototipo: "1:35" → 95. Formati non validi tornano 0. */
fun labelToSec(label: String): Int {
    val parts = label.split(":")
    if (parts.size != 2) return 0
    val m = parts[0].trim().toIntOrNull() ?: 0
    val s = parts[1].trim().toIntOrNull() ?: 0
    return m * 60 + s
}
