package com.example.registrazio.domain.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sin

/**
 * L'equalizzatore del mini player, ridisegnato per lo spazio copertina della
 * notifica: cinque barrette accent su **niente**.
 *
 * ## Perché non è animato, e non può esserlo
 *
 * Quello spazio accetta una `Bitmap` e nient'altro. Il sistema la disegna una
 * volta quando la notifica cambia, non sessanta volte al secondo: non è una
 * superficie che possiamo ridipingere, è un'immagine che consegniamo. Farla
 * ondeggiare vorrebbe dire ricostruire e ripubblicare la notifica intera a
 * ogni fotogramma — il sistema la limiterebbe comunque, e nel frattempo
 * ciucceremmo batteria per un'animazione che quasi nessuno sta guardando.
 *
 * Quindi: stessa forma, stessi colori, stesse proporzioni di
 * `MiniPlayer.MiniEqualizer` — cinque barre, spaziatura larga quanto un quarto
 * di barra, angoli tondi — ma ferme.
 *
 * ## Sfondo trasparente
 *
 * La prima versione riempiva il quadrato di crema, e sembrava una copertina
 * finta. Senza sfondo restano le sole barrette, come nella barra in ascolto
 * dell'app: il riquadro lo disegna il sistema con il proprio colore, e
 * l'iconcina ci galleggia dentro.
 *
 * ## La forma dipende dal titolo
 *
 * Non dal caso: la stessa traccia mostra sempre lo stesso disegno, due tracce
 * diverse ne mostrano due diversi. Con un valore casuale cambierebbe a ogni
 * aggiornamento della notifica — cioè a ogni play, pausa e cambio di stato —
 * e sfarfallerebbe senza dire niente.
 */
object CopertinaOnde {

    private const val LATO = 256
    private const val BARRE = 5

    /** `accent` del tema chiaro: la stessa tinta delle barrette nell'app. */
    private const val ACCENT = 0xFF3C6E64.toInt()

    fun per(titolo: String): Bitmap {
        val bitmap = Bitmap.createBitmap(LATO, LATO, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Niente `drawColor`: il quadrato resta trasparente.

        val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }

        // Le barrette occupano poco più di un terzo del riquadro e stanno in
        // mezzo: nella notifica si legge come un'icona, non come una copertina
        // che riempie tutto.
        val larghezzaTotale = LATO * 0.38f
        val spazio = larghezzaTotale * 0.11f
        val larghezzaBarra = (larghezzaTotale - spazio * (BARRE - 1)) / BARRE
        val sinistra = (LATO - larghezzaTotale) / 2f
        val centro = LATO / 2f
        val altezzaMax = LATO * 0.17f
        val altezzaMin = LATO * 0.05f

        // `hashCode` di una stringa è stabile fra un avvio e l'altro: la stessa
        // traccia disegna sempre le stesse cinque barre.
        val seme = titolo.hashCode()

        for (i in 0 until BARRE) {
            val onda = sin(i * 1.4f + seme % 13) * 0.6f + sin(i * 0.6f + seme % 7) * 0.4f
            val mezzaAltezza = (altezzaMin + abs(onda) * (altezzaMax - altezzaMin))
                .coerceAtMost(altezzaMax)
            val x = sinistra + i * (larghezzaBarra + spazio)
            canvas.drawRoundRect(
                RectF(x, centro - mezzaAltezza, x + larghezzaBarra, centro + mezzaAltezza),
                larghezzaBarra / 2f,
                larghezzaBarra / 2f,
                pennello
            )
        }
        return bitmap
    }
}
