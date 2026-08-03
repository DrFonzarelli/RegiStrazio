package com.example.registrazio.data.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sorgente dati per ExoPlayer che decifra al volo un file MEGA.
 *
 * I byte che arrivano dall'URL di MEGA sono cifrati con AES-CTR: puntarci
 * ExoPlayer direttamente produce rumore, non audio. Questa classe sta in mezzo,
 * scarica il cifrato e consegna al player byte già in chiaro. ExoPlayer non sa
 * che dietro c'è MEGA, e non deve saperlo.
 *
 * Il seek funziona perché CTR è cifratura a flusso con contatore: per leggere
 * dal byte N basta partire dal blocco `N / 16` e scartare i primi `N % 16`
 * byte, senza toccare tutto ciò che viene prima.
 */
@UnstableApi
class MegaDataSource(
    private val chiave: MegaCrypto.ChiaveFile,
    private val interno: HttpDataSource = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .createDataSource()
) : BaseDataSource(/* isNetwork = */ true) {

    private var cipher: Cipher? = null

    /** Byte del primo blocco che precedono la posizione chiesta: vanno buttati. */
    private var daScartare = 0
    private var aperto = false
    private var uriCorrente: Uri? = null

    private val tampone = ByteArray(64 * 1024)

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val allineato = MegaCrypto.inizioBloccoPer(dataSpec.position)
        daScartare = MegaCrypto.scartoPerOffset(dataSpec.position)

        cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(chiave.aes, "AES"),
                IvParameterSpec(MegaCrypto.ivPerOffset(chiave.nonce, dataSpec.position))
            )
        }

        // Alla richiesta HTTP servono anche i byte scartati, altrimenti alla fine
        // del brano ne mancherebbero tanti quanti il disallineamento iniziale.
        val lunghezzaInterna =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else dataSpec.length + daScartare

        val disponibili = interno.open(
            dataSpec.buildUpon()
                .setPosition(allineato)
                .setLength(lunghezzaInterna)
                .build()
        )

        uriCorrente = interno.uri
        aperto = true
        transferStarted(dataSpec)

        return if (disponibili == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else (disponibili - daScartare).coerceAtLeast(0)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val cifrario = cipher ?: return C.RESULT_END_OF_INPUT

        // I byte scartati vanno comunque fatti passare dal cifrario: il contatore
        // CTR avanza con il flusso, saltarli sfaserebbe tutto il resto.
        while (daScartare > 0) {
            val quanti = minOf(daScartare, tampone.size)
            val letti = interno.read(tampone, 0, quanti)
            if (letti == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            cifrario.update(tampone, 0, letti)
            daScartare -= letti
            bytesTransferred(letti)
        }

        val letti = interno.read(tampone, 0, minOf(length, tampone.size))
        if (letti == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT

        val prodotti = cifrario.update(tampone, 0, letti, buffer, offset)
        bytesTransferred(letti)
        return prodotti
    }

    override fun getUri(): Uri? = uriCorrente

    override fun close() {
        cipher = null
        daScartare = 0
        uriCorrente = null
        try {
            interno.close()
        } finally {
            if (aperto) {
                aperto = false
                transferEnded()
            }
        }
    }
}

/**
 * Fabbrica legata a una singola traccia: la chiave cambia da file a file, e
 * ExoPlayer costruisce la sorgente quando gli pare.
 */
@UnstableApi
class MegaDataSourceFactory(private val chiave: MegaCrypto.ChiaveFile) : DataSource.Factory {
    override fun createDataSource(): DataSource = MegaDataSource(chiave)
}
