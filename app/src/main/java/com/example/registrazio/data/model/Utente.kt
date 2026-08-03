package com.example.registrazio.data.model

import androidx.compose.runtime.Immutable

/**
 * Profilo di un membro del collettivo — documento `utenti/{appUid}` su Firestore.
 *
 * [appUid] è l'UUID generato dall'app, non l'UID di Firebase Anonymous Auth:
 * quello cambia a ogni reinstall e non può fare da chiave identità.
 */
@Immutable
data class Utente(
    val appUid: String,
    val nome: String,
    /** Chiave della palette ("a", "m", "l", "t", "g", "r", "o"), non un hex. */
    val colore: String,
    val creatoIl: Long = System.currentTimeMillis()
) {
    /** Iniziale maiuscola mostrata dentro il pallino dell'avatar. */
    val iniziale: String
        get() = nome.trim().take(1).uppercase()
}
