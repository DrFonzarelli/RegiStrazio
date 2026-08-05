package com.example.registrazio.data.model

import androidx.compose.runtime.Immutable

/**
 * Commento ancorato a un punto preciso della traccia —
 * `tracce/{tracciaId}/commenti/{commentoId}`.
 *
 * [autoreNome] e [autoreColore] sono uno snapshot preso al momento della
 * scrittura, non un riferimento al profilo: se qualcuno cambia nome o colore
 * più avanti, i commenti vecchi restano attribuiti come erano allora.
 */
@Immutable
data class Commento(
    val id: String,
    val tracciaId: String,
    val appUid: String,
    val autoreNome: String,
    /** Chiave palette dell'autore, congelata alla scrittura. */
    val autoreColore: String,
    /** Posizione nella traccia in secondi (0 = inizio). */
    val timestampSecondi: Float,
    val testo: String,
    val creatoIl: Long = System.currentTimeMillis(),
    /**
     * `null` = già su Firestore. Valorizzato solo finché il commento vive
     * in Room in attesa di essere caricato.
     */
    val statoSync: StatoSync? = null
) {
    /** Lettera nel pallino del marker e dell'avatar. */
    val iniziale: String
        get() = autoreNome.trim().take(1).uppercase()

    /**
     * Aspetta ancora di arrivare su Firestore.
     *
     * [StatoSync.SINCRONIZZATO] conta come "già arrivato" quanto il `null`: la
     * differenza fra i due è solo da dove viene il commento — appena riletto da
     * Room porta uno stato esplicito, appena costruito in memoria no.
     */
    val isPending: Boolean
        get() = statoSync != null && statoSync != StatoSync.SINCRONIZZATO
}
