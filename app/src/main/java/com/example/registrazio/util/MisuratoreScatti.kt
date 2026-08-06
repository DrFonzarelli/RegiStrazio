package com.example.registrazio.util

import android.util.Log
import android.view.Choreographer

/**
 * Conta i fotogrammi persi e li scrive nel log.
 *
 * Serve a smettere di indovinare. Sistemate tre cose che pesavano davvero, lo
 * scorrimento era migliorato ma non guarito, e senza un numero non c'è modo di
 * sapere se la prossima modifica serve o se stiamo solo spostando codice.
 *
 * Il conto arriva dal [Choreographer], che chiama il proprio callback una volta
 * per fotogramma: la distanza fra due chiamate **è** il tempo che quel
 * fotogramma è costato. Se supera il periodo del display, qualcuno non ce l'ha
 * fatta — e il numero di fotogrammi saltati è quel ritardo diviso il periodo.
 *
 * Nessuna dipendenza in più: `androidx.metrics` farebbe lo stesso lavoro meglio,
 * con la fase di rendering divisa per pezzi, ma qui basta sapere *quanto* e
 * *quando*.
 *
 * **Conta solo in debug.** [avvia] non fa niente in una build di release, dove
 * per altro i numeri sarebbero diversi: senza R8 e con la strumentazione di
 * Compose accesa, una build di debug è parecchio più lenta, e i suoi scatti non
 * sono quelli che vedrà chi usa l'app.
 */
object MisuratoreScatti {

    private var attivo = false
    private var ultimoFrame = 0L
    private var persiTotali = 0
    private var frameTotali = 0
    private var ultimoRiassunto = 0L

    /** Periodo di un fotogramma a 60 Hz, in nanosecondi. */
    private const val PERIODO_NS = 16_666_667L

    /** Sotto questa soglia è un fotogramma lento, non uno scatto visibile. */
    private const val SOGLIA_SCATTO = 2

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!attivo) return

            if (ultimoFrame != 0L) {
                val durata = frameTimeNanos - ultimoFrame
                val persi = ((durata - PERIODO_NS) / PERIODO_NS).toInt()
                frameTotali++
                if (persi >= SOGLIA_SCATTO) {
                    persiTotali += persi
                    Log.w(TAG, "scatto: $persi fotogrammi persi (${durata / 1_000_000} ms)")
                }

                // Un riassunto ogni due secondi: i singoli scatti dicono che è
                // successo, la media dice se sta migliorando.
                if (frameTimeNanos - ultimoRiassunto > 2_000_000_000L) {
                    if (persiTotali > 0) {
                        Log.i(TAG, "ultimi 2s: $persiTotali persi su $frameTotali fotogrammi")
                    }
                    persiTotali = 0
                    frameTotali = 0
                    ultimoRiassunto = frameTimeNanos
                }
            }
            ultimoFrame = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** @param debug passare `BuildConfig.DEBUG`: in release non si misura niente. */
    fun avvia(debug: Boolean) {
        if (!debug || attivo) return
        attivo = true
        ultimoFrame = 0L
        ultimoRiassunto = 0L
        Choreographer.getInstance().postFrameCallback(callback)
        Log.i(TAG, "misuratore scatti acceso")
    }

    fun ferma() {
        if (!attivo) return
        attivo = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    private const val TAG = "RegiStrazioScatti"
}
