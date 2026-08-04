package com.example.registrazio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.registrazio.ui.AppRoot
import com.example.registrazio.ui.TestoCondiviso

class MainActivity : ComponentActivity() {

    /**
     * L'ultimo testo arrivato da "Condividi", con un contatore.
     *
     * Il contatore non è un vezzo: condividere due volte di fila lo stesso link
     * darebbe due valori identici, e senza qualcosa che cambia la seconda volta
     * non succederebbe niente.
     */
    private var condiviso by mutableStateOf<TestoCondiviso?>(null)
    private var quantiShare = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Il prototipo disegna sotto le barre di sistema (`viewport-fit=cover`)
        // e gestisce da sé le safe area: qui serve lo stesso edge-to-edge.
        enableEdgeToEdge()
        raccogliCondivisione(intent)
        setContent {
            AppRoot(testoCondiviso = condiviso)
        }
    }

    /**
     * Con `singleTask` una seconda condivisione non ripassa da [onCreate]:
     * arriva qui, sull'istanza già viva. Senza questo, condividere con l'app
     * già aperta non farebbe niente.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        raccogliCondivisione(intent)
    }

    private fun raccogliCondivisione(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val testo = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        quantiShare++
        condiviso = TestoCondiviso(testo, quantiShare)
    }
}
