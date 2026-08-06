package com.example.registrazio

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.registrazio.util.MisuratoreScatti
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
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
        // Solo in debug: scrive nel log quanti fotogrammi si perdono e quando.
        // Serve a misurare invece di indovinare, e in release non gira.
        MisuratoreScatti.avvia(BuildConfig.DEBUG)
        // Il prototipo disegna sotto le barre di sistema (`viewport-fit=cover`)
        // e gestisce da sé le safe area: qui serve lo stesso edge-to-edge.
        enableEdgeToEdge()
        chiediPermessoNotifiche()
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

    /**
     * Da Android 13 le notifiche vanno chieste.
     *
     * Si chiede all'avvio e una volta sola: se l'utente dice di no, l'audio
     * funziona lo stesso — sparisce solo la notifica, e con lei il commento
     * rapido. Non vale un secondo giro di richieste, e Android le ignorerebbe
     * comunque dopo due rifiuti.
     */
    private fun chiediPermessoNotifiche() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permesso = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permesso) == PackageManager.PERMISSION_GRANTED) return
        // `requestPermissions` e non un launcher registrato: qui non serve
        // sapere la risposta, e un launcher lanciato da `onCreate` va servito
        // con attenzione al ciclo di vita per niente.
        ActivityCompat.requestPermissions(this, arrayOf(permesso), RICHIESTA_NOTIFICHE)
    }

    private fun raccogliCondivisione(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val testo = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        quantiShare++
        condiviso = TestoCondiviso(testo, quantiShare)
    }

    private companion object {
        const val RICHIESTA_NOTIFICHE = 1
    }
}
