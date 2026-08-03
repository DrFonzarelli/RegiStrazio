package com.example.registrazio.data.model

import androidx.compose.runtime.Immutable

/**
 * Voto a stella, ciclico: vuota → mezza ("interessante") → piena ("preferita") → vuota.
 * Replica il comportamento del tasto stella nel prototipo.
 */
enum class VotoStella {
    NESSUNO,
    MEZZA,
    PIENA;

    fun next(): VotoStella = when (this) {
        NESSUNO -> MEZZA
        MEZZA -> PIENA
        PIENA -> NESSUNO
    }
}

/**
 * Traccia audio — documento `tracce/{tracciaId}`.
 *
 * I contatori dei voti sono di gruppo, mentre [mioVoto] è locale: nel prototipo
 * cambiare il proprio voto sposta i contatori di conseguenza (vedi [conVoto]).
 */
@Immutable
data class Traccia(
    val id: String,
    val cartellaId: String,
    val titolo: String,
    /** Node handle del file su MEGA. */
    val idFileMega: String = "",
    val durataSecondi: Int,
    /** ~200 valori 0.0–1.0, `null` finché nessuno l'ha ancora calcolata. */
    val waveformData: List<Float>? = null,
    val ascolti: Int = 0,
    val mioVoto: VotoStella = VotoStella.NESSUNO,
    val votiPieni: Int = 0,
    val votiMezzi: Int = 0,
    /** Presente nella cache locale: si riproduce da disco invece che in streaming. */
    val scaricata: Boolean = false,
    /** Quante volte è stata scaricata dal gruppo (statistica del foglio dettagli). */
    val downloadEvents: Int = 0,
    /** 24 secchielli di ascolto cumulato, per il grafico "punti più ascoltati". */
    val playBuckets: List<Float> = List(24) { 0f },
    val commenti: List<Commento> = emptyList(),
    val link: String? = null,
    val creatoIl: Long = System.currentTimeMillis()
) {
    /** Punteggio usato dall'ordinamento "più votate": la mezza stella vale 0.5. */
    val punteggio: Float
        get() = votiPieni + votiMezzi * 0.5f

    /**
     * Applica il ciclo della stella aggiornando anche i contatori di gruppo,
     * come fa `favBtn` nel prototipo.
     */
    fun conVotoSuccessivo(): Traccia {
        val nuovo = mioVoto.next()
        return when (nuovo) {
            VotoStella.MEZZA -> copy(mioVoto = nuovo, votiMezzi = votiMezzi + 1)
            VotoStella.PIENA -> copy(
                mioVoto = nuovo,
                votiMezzi = (votiMezzi - 1).coerceAtLeast(0),
                votiPieni = votiPieni + 1
            )
            VotoStella.NESSUNO -> copy(
                mioVoto = nuovo,
                votiPieni = (votiPieni - 1).coerceAtLeast(0)
            )
        }
    }
}
