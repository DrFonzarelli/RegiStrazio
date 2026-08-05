package com.example.registrazio.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.registrazio.data.model.StatoSync
import com.example.registrazio.data.model.VotoStella

/**
 * Le tabelle dell'archivio locale.
 *
 * Questo è il posto dove i dati **vivono davvero**. Firestore è la destinazione
 * della sincronizzazione, non la fonte: ogni riga qui porta il proprio
 * [StatoSync], che dice se è già stata caricata o se aspetta ancora.
 *
 * Regola: quando l'utente fa qualcosa, si scrive qui **subito**. Chiudere
 * l'app non deve poter far perdere niente.
 */
@Entity(tableName = "cartelle")
data class CartellaEntity(
    @PrimaryKey val id: String,
    val nome: String,
    /** Link pubblico completo, chiave inclusa. */
    val linkMega: String,
    val megaFolderId: String,
    val aggiuntoIl: Long,
    val aggiuntoDa: String,
    val statoSync: StatoSync
)

@Entity(tableName = "tracce")
data class TracciaEntity(
    @PrimaryKey val id: String,
    val cartellaId: String,
    val titolo: String,
    val idFileMega: String,
    /** Peso del file su MEGA: denominatore della percentuale di download. */
    val dimensioneByte: Long,
    val durataSecondi: Int,
    val ascolti: Int,
    val mioVoto: VotoStella,
    val votiPieni: Int,
    val votiMezzi: Int,
    val scaricata: Boolean,
    val downloadEvents: Int,
    val playBuckets: List<Float>,
    val waveformData: List<Float>?,
    val creatoIl: Long,
    /**
     * Chiave AES e nonce del file su MEGA.
     *
     * Restano **solo qui**: su Firestore va `idFileMega`, mai la chiave. Sono
     * comunque ricavabili dal link della cartella, quindi salvarle non aggiunge
     * un segreto nuovo — evita solo di dover rileggere MEGA a ogni riavvio per
     * poter premere play.
     */
    val chiaveAes: ByteArray?,
    val nonce: ByteArray?,
    val statoSync: StatoSync
) {
    // Con un ByteArray dentro, equals/hashCode generati confrontano i
    // riferimenti: due righe identiche risulterebbero diverse.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TracciaEntity) return false
        return id == other.id &&
            cartellaId == other.cartellaId &&
            titolo == other.titolo &&
            idFileMega == other.idFileMega &&
            dimensioneByte == other.dimensioneByte &&
            durataSecondi == other.durataSecondi &&
            ascolti == other.ascolti &&
            mioVoto == other.mioVoto &&
            votiPieni == other.votiPieni &&
            votiMezzi == other.votiMezzi &&
            scaricata == other.scaricata &&
            downloadEvents == other.downloadEvents &&
            playBuckets == other.playBuckets &&
            waveformData == other.waveformData &&
            creatoIl == other.creatoIl &&
            chiaveAes.contentEquals(other.chiaveAes) &&
            nonce.contentEquals(other.nonce) &&
            statoSync == other.statoSync
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Una traccia scaricata sul telefono.
 *
 * Tabella a parte e non un campo di [TracciaEntity], perché il download è una
 * scelta di **questo** telefono: non riguarda il gruppo e non finisce mai su
 * Firestore. Non ha quindi nemmeno uno [com.example.registrazio.data.model.StatoSync].
 */
@Entity(tableName = "download")
data class DownloadEntity(
    @PrimaryKey val tracciaId: String,
    val percorso: String,
    val dimensioneByte: Long,
    val scaricatoIl: Long
)

@Entity(tableName = "commenti")
data class CommentoEntity(
    @PrimaryKey val id: String,
    val tracciaId: String,
    val appUid: String,
    val autoreNome: String,
    val autoreColore: String,
    val timestampSecondi: Float,
    val testo: String,
    val creatoIl: Long,
    val statoSync: StatoSync
)

/** Room sa gestire solo tipi semplici: qui si traducono gli altri. */
class Convertitori {

    @TypeConverter
    fun statoDaTesto(valore: String?): StatoSync =
        valore?.let { runCatching { StatoSync.valueOf(it) }.getOrNull() } ?: StatoSync.LOCALE

    @TypeConverter
    fun statoATesto(stato: StatoSync): String = stato.name

    @TypeConverter
    fun votoDaTesto(valore: String?): VotoStella =
        valore?.let { runCatching { VotoStella.valueOf(it) }.getOrNull() } ?: VotoStella.NESSUNO

    @TypeConverter
    fun votoATesto(voto: VotoStella): String = voto.name

    @TypeConverter
    fun listaDaTesto(valore: String?): List<Float>? = when {
        valore == null -> null
        valore.isEmpty() -> emptyList()
        else -> valore.split(',').mapNotNull { it.toFloatOrNull() }
    }

    @TypeConverter
    fun listaATesto(lista: List<Float>?): String? = lista?.joinToString(",")
}
