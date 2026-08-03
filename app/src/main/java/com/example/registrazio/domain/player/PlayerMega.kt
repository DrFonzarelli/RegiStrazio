package com.example.registrazio.domain.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            // true: se arriva una telefonata la riproduzione si ferma da sola
            true
        )
    }

    /** Chiamato quando la durata reale del file diventa nota (in secondi). */
    var onDurata: ((Int) -> Unit)? = null

    /** Chiamato quando il brano finisce da solo. */
    var onFine: (() -> Unit)? = null

    /** Chiamato se la riproduzione fallisce, col messaggio da mostrare. */
    var onErrore: ((String) -> Unit)? = null

    private var durataNotificata = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(stato: Int) {
                if (stato == Player.STATE_READY) notificaDurata()
                if (stato == Player.STATE_ENDED) onFine?.invoke()
            }

            override fun onPlayerError(errore: PlaybackException) {
                onErrore?.invoke("Non riesco a riprodurre la traccia: ${errore.errorCodeName}")
            }
        })
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
    fun riproduci(url: String, chiave: MegaCrypto.ChiaveFile, daSecondi: Float) {
        durataNotificata = 0
        val sorgente = ProgressiveMediaSource
            .Factory(MegaDataSourceFactory(chiave))
            .createMediaSource(MediaItem.fromUri(url))

        player.setMediaSource(sorgente)
        player.prepare()
        if (daSecondi > 0f) player.seekTo((daSecondi * 1000).toLong())
        player.play()
    }

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

    fun rilascia() = player.release()
}
