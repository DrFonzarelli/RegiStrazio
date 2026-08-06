package com.example.registrazio.domain.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sin

/**
 * La copertina quadrata della notifica: un'onda disegnata, al posto del nulla.
 *
 * La notifica media tiene a sinistra uno spazio per la copertina dell'album. Un
 * brano di prova non ce l'ha, e lasciandolo vuoto il sistema ci mette un
 * quadrato grigio — è la parte che rendeva la notifica "grossolana" rispetto al
 * prototipo, che lì disegna un equalizzatore.
 *
 * **Statica, non animata.** Quello spazio accetta una `Bitmap` e nient'altro:
 * il sistema la disegna una volta e non la ridisegna a sessanta fotogrammi al
 * secondo. L'onda del prototipo che ondeggia lì non è replicabile, e provarci
 * vorrebbe dire ridisegnare la notifica di continuo — rumore per il sistema e
 * batteria buttata.
 *
 * **La forma dipende dal titolo**, non dal caso: la stessa traccia mostra
 * sempre la stessa onda, e due tracce diverse ne mostrano due diverse. Così
 * l'immagine dice qualcosa invece di essere decorazione pura, e scorrendo le
 * notifiche si riconosce il pezzo prima di leggerne il nome.
 */
object CopertinaOnde {

    private const val LATO = 256
    private const val BARRE = 22

    /** Crema e scuro del logo: la notifica resta della stessa famiglia dell'app. */
    private const val SFONDO = 0xFFF1E9E0.toInt()
    private const val BARRA = 0xFF242726.toInt()

    fun per(titolo: String): Bitmap {
        val bitmap = Bitmap.createBitmap(LATO, LATO, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(SFONDO)

        val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BARRA }

        val margine = LATO * 0.14f
        val larghezzaUtile = LATO - margine * 2
        val spazio = larghezzaUtile * 0.035f
        val larghezzaBarra = (larghezzaUtile - spazio * (BARRE - 1)) / BARRE
        val centro = LATO / 2f
        val altezzaMax = LATO * 0.34f

        // Il titolo fa da seme: `hashCode` di una stringa è stabile fra un
        // avvio e l'altro, quindi la stessa traccia disegna sempre la stessa
        // onda. Con `Random()` cambierebbe a ogni notifica.
        val seme = titolo.hashCode()

        for (i in 0 until BARRE) {
            // Due seni di periodo diverso più il seme: basta a far sembrare
            // un'onda invece di una scala, senza tirarsi dietro un generatore.
            val onda = sin(i * 0.9f + seme % 7) * 0.5f + sin(i * 0.37f + seme % 11) * 0.3f
            val altezza = (LATO * 0.06f + abs(onda) * altezzaMax).coerceAtMost(altezzaMax)
            val x = margine + i * (larghezzaBarra + spazio)
            canvas.drawRoundRect(
                RectF(x, centro - altezza, x + larghezzaBarra, centro + altezza),
                larghezzaBarra / 2f,
                larghezzaBarra / 2f,
                pennello
            )
        }
        return bitmap
    }
}
