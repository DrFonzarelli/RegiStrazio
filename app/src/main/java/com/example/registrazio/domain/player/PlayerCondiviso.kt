package com.example.registrazio.domain.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * L'unico ExoPlayer dell'app, condiviso fra la schermata e il servizio.
 *
 * **Perché un singleton, che di solito è una cattiva idea.** L'audio deve
 * sopravvivere allo schermo spento e all'app in secondo piano: è tutto il senso
 * di poter commentare mentre si ascolta. Un player dentro il ViewModel muore con
 * la schermata, e uno dentro il servizio costringerebbe l'interfaccia a parlarci
 * attraverso un `MediaController` asincrono, riscrivendo ogni chiamata.
 *
 * Con un'istanza sola, [PlayerMega] continua a comandare come ha sempre fatto e
 * il servizio ci mette sopra la sessione media — la notifica, la lock screen, i
 * tasti delle cuffie. Nessuno dei due la crea o la distrugge: la crea questo
 * oggetto e la chiude [rilascia], quando si chiude l'app per davvero.
 */
@UnstableApi
object PlayerCondiviso {

    private var istanza: ExoPlayer? = null

    /**
     * Cosa sta suonando adesso, per chi non ha accesso allo stato dell'interfaccia.
     *
     * Serve al servizio per intitolare la notifica e al commento rapido per
     * sapere **su quale traccia** e **a che minuto** scriverlo. Il player da solo
     * conosce un URL, non una traccia nostra.
     */
    private val _inAscolto = MutableStateFlow<TracciaInAscolto?>(null)
    val inAscolto: StateFlow<TracciaInAscolto?> = _inAscolto.asStateFlow()

    /** Il player, creato alla prima richiesta. Usa sempre l'`applicationContext`. */
    fun player(context: Context): ExoPlayer = istanza ?: ExoPlayer.Builder(
        context.applicationContext
    ).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            // true: se arriva una telefonata la riproduzione si ferma da sola
            true
        )
    }.also { istanza = it }

    fun segnaInAscolto(traccia: TracciaInAscolto?) {
        _inAscolto.value = traccia
    }

    /** Posizione corrente in secondi, o 0 se non c'è niente in canna. */
    val posizioneSecondi: Float
        get() = istanza?.let { (it.currentPosition.coerceAtLeast(0) / 1000f) } ?: 0f

    fun rilascia() {
        istanza?.release()
        istanza = null
        _inAscolto.value = null
    }
}

/** Il minimo che serve per intitolare una notifica e attribuire un commento. */
data class TracciaInAscolto(val id: String, val titolo: String, val cartellaId: String)
