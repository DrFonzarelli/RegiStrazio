package com.example.registrazio.data.model

/**
 * Stato di un commento scritto in locale rispetto a Firestore.
 *
 * Un commento nasce PENDING, diventa UPLOADING mentre il tasto Sincronizza lo
 * carica, e a quel punto o sparisce da Room (upload riuscito: la verità si
 * sposta su Firestore) o torna indietro come ERROR, riprovabile.
 */
enum class StatoSync {
    PENDING,
    UPLOADING,
    ERROR
}
