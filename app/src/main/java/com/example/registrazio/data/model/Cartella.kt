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
        /** Estrae l'id cartella da un link MEGA, o null se il link non è valido. */
        fun parseFolderId(link: String): String? {
            if (!link.contains("/folder/")) return null
            val after = link.substringAfter("/folder/", "")
            if (after.isEmpty()) return null
            val id = after.substringBefore('#').substringBefore('/').trim()
            return id.ifEmpty { null }
        }

        /** Nome provvisorio proposto quando si collega una cartella nuova. */
        fun suggestName(folderId: String): String = "Cartella " + folderId.take(6)
    }
}
