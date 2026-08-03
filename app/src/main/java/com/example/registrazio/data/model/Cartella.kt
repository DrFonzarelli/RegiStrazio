package com.example.registrazio.data.model

import androidx.compose.runtime.Immutable

/**
 * Cartella MEGA collegata al gruppo — documento `cartelle/{cartellaId}`.
 *
 * Il link pubblico contiene anche la chiave di decrittazione dopo il `#`,
 * quindi va trattato come un segreto: chi ce l'ha può leggere la cartella.
 */
@Immutable
data class Cartella(
    val id: String,
    val nome: String,
    /** Link pubblico completo, chiave inclusa: `https://mega.nz/folder/<id>#<key>`. */
    val linkMega: String,
    /** Porzione dopo `/folder/` e prima di `#`. */
    val megaFolderId: String,
    val aggiuntoIl: Long = System.currentTimeMillis(),
    /** appUid di chi l'ha collegata. */
    val aggiuntoDa: String = "",
    val numTracce: Int = 0
) {
    companion object {
        // Il parsing del link sta in LinkMega.parse: lì serve anche la chiave di
        // decrittazione, che senza non si va da nessuna parte.

        /** Nome provvisorio proposto quando si collega una cartella nuova. */
        fun suggestName(folderId: String): String = "Cartella " + folderId.take(6)
    }
}
