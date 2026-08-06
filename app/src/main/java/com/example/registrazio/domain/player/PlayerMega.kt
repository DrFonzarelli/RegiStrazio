package com.example.registrazio.domain.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.registrazio.data.remote.MegaCrypto
import com.example.registrazio.data.remote.MegaDataSourceFactory

/**
 * ExoPlayer con davanti la decifratura MEGA.
 *
 * Tiene una sola traccia alla volta: l'app riproduce un brano per volta e non
 * ha code. Chi lo usa non deve sapere né di MEGA né di AES.
 */
@UnstableApi
class PlayerMega(context: Context) {

    // Non lo crea: se lo fa dare da [PlayerCondiviso], così il servizio della
    // notifica comanda esattamente lo stesso audio invece di un secondo player
    // che non sa niente di questo.
    private val player = PlayerCondiviso.player(context)

    /** Chiamato quando la durata reale del file diventa nota (in secondi). */
    var onDurata: ((Int) -> Unit)? = null

    /** Chiamato quando il brano finisce da solo. */
    var onFine: (() -> Unit)? = null

    /**
     * Chiamato se la riproduzione fallisce, con l'eccezione vera.
     *
     * Si passa l'eccezione e non una frase già fatta perché qui non si sa se il
     * problema è la rete assente o il file: la catena delle cause lo dice, e a
     * scegliere le parole è chi conosce il contesto.
     */
    var onErrore: ((Throwable) -> Unit)? = null

    /**
     * Play o pausa arrivati da **fuori** l'interfaccia: la notifica, i tasti
     * delle cuffie, una telefonata che ruba l'audio.
     *
     * Senza questo, premere pausa sulla notifica fermerebbe l'audio ma nell'app
     * il tasto resterebbe su "pausa": due schermi che raccontano cose diverse
     * sullo stesso player.
     */
    var onPlayPausa: ((Boolean) -> Unit)? = null

    private var durataNotificata = 0

    // Tenuto da parte per poterlo togliere: il player sopravvive a questo
    // oggetto, quindi un ascoltatore lasciato attaccato resterebbe lì per sempre
    // — e a ogni nuovo ViewModel se ne aggiungerebbe un altro.
    private val ascoltatore = object : Player.Listener {
        override fun onPlaybackStateChanged(stato: Int) {
            if (stato == Player.STATE_READY) notificaDurata()
            if (stato == Player.STATE_ENDED) onFine?.invoke()
        }

        override fun onPlayerError(errore: PlaybackException) {
            Log.w(TAG, "riproduzione fallita: ${errore.errorCodeName}")
            onErrore?.invoke(errore)
        }

        override fun onIsPlayingChanged(staSuonando: Boolean) {
            onPlayPausa?.invoke(staSuonando)
        }
    }

    init {
        player.addListener(ascoltatore)
    }

    private fun notificaDurata() {
        val durata = player.duration
        if (durata == C.TIME_UNSET || durata <= 0) return
        val secondi = (durata / 1000).toInt()
        // Il player rinotifica READY a ogni seek: senza questo si riscriverebbe
        // la stessa durata di continuo.
        if (secondi != durataNotificata) {
            durataNotificata = secondi
            onDurata?.invoke(secondi)
        }
    }

    /**
     * Comincia a suonare [url], decifrandolo con [chiave], partendo da [daSecondi].
     *
     * `ProgressiveMediaSource` invece di un semplice `setMediaItem`: serve a
     * imporre la nostra sorgente dati al posto di quella HTTP di default.
     */
    fun riproduci(
        url: String,
        chiave: MegaCrypto.ChiaveFile,
        daSecondi: Float,
        titolo: String = "",
        cartella: String = ""
    ) {
        durataNotificata = 0
        val sorgente = ProgressiveMediaSource
            .Factory(MegaDataSourceFactory(chiave))
            .createMediaSource(itemConTitolo(url, titolo, cartella))

        player.setMediaSource(sorgente)
        player.prepare()
        if (daSecondi > 0f) player.seekTo((daSecondi * 1000).toLong())
        player.play()
    }

    /**
     * Suona un file già sul telefono.
     *
     * Niente `MegaDataSourceFactory` qui: su disco il file è già in chiaro,
     * perché lo scaricatore lo decifra mentre lo scrive. Va bene il MediaItem
     * normale, e non serve nemmeno la rete.
     */
    fun riproduciFile(
        file: java.io.File,
        daSecondi: Float,
        titolo: String = "",
        cartella: String = ""
    ) {
        durataNotificata = 0
        player.setMediaItem(itemConTitolo(android.net.Uri.fromFile(file).toString(), titolo, cartella))
        player.prepare()
        if (daSecondi > 0f) player.seekTo((daSecondi * 1000).toLong())
        player.play()
    }

    /**
     * Il titolo viaggia col brano.
     *
     * La notifica e la lock screen leggono i metadati del `MediaItem`: senza,
     * mostrerebbero l'indirizzo temporaneo di MEGA o il percorso di un file in
     * cache, che non dicono niente a nessuno.
     */
    private fun itemConTitolo(uri: String, titolo: String, cartella: String): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(titolo.ifBlank { "RegiStrazio" })
                    // La cartella, non il nome dell'app: quello sta già in cima
                    // alla notifica, messo dal sistema, e ripeterlo qui sotto
                    // sprecava l'unica riga che poteva dire qualcosa di utile —
                    // da quale prova viene il pezzo che stai ascoltando.
                    .setArtist(cartella.ifBlank { "RegiStrazio" })
                    .build()
            )
            .build()

    fun riprendi() = player.play()

    fun pausa() = player.pause()

    fun cerca(secondi: Float) = player.seekTo((secondi * 1000).toLong().coerceAtLeast(0))

    fun ferma() {
        player.stop()
        player.clearMediaItems()
        durataNotificata = 0
    }

    /** Posizione corrente in secondi; 0 se non sta suonando niente. */
    val posizioneSecondi: Float
        get() = (player.currentPosition.coerceAtLeast(0) / 1000f)

    val staSuonando: Boolean
        get() = player.isPlaying

    /**
     * Play è premuto, indipendentemente dal fatto che il suono stia uscendo.
     *
     * Serve a leggere un `isPlaying` a `false` per quello che è. Vale sia
     * quando si è in pausa sia quando si sta caricando o finendo un `seek`, e
     * le due cose vogliono reazioni opposte: alla prima si spegne il ciclo di
     * aggiornamento, alla seconda va lasciato acceso perché fra un istante
     * l'audio riparte. Solo `playWhenReady` le distingue.
     */
    val vuoleSuonare: Boolean
        get() = player.playWhenReady

    /**
     * Non rilascia niente.
     *
     * Il player non è suo: è di [PlayerCondiviso], e il servizio della notifica
     * lo sta ancora usando. Chiuderlo qui spegnerebbe l'audio ogni volta che si
     * gira il telefono, che ricrea il ViewModel.
     */
    fun scollega() {
        player.removeListener(ascoltatore)
        onDurata = null
        onFine = null
        onErrore = null
    }

    private companion object {
        const val TAG = "PlayerMega"
    }
}
