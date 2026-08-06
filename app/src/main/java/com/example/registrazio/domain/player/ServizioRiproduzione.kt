package com.example.registrazio.domain.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.example.registrazio.MainActivity
import com.example.registrazio.R
import com.google.common.collect.ImmutableList

/**
 * Il servizio che tiene in vita la riproduzione e mostra la notifica.
 *
 * **Perché serve un servizio.** Senza, l'audio è legato alla schermata: appena
 * si esce dall'app Android è libero di fermare tutto. Con un `MediaSessionService`
 * in primo piano la riproduzione continua a schermo spento, compaiono i comandi
 * sulla lock screen e funzionano i tasti delle cuffie — e soprattutto c'è un
 * posto dove mettere il tasto "Commenta", che è tutto il punto: dire una cosa
 * sulla traccia **mentre** la si ascolta, senza rientrare nell'app.
 *
 * Il player non lo crea questo servizio: è quello di [PlayerCondiviso], lo stesso
 * che comanda l'interfaccia. Così i due non possono raccontare cose diverse.
 */
@UnstableApi
class ServizioRiproduzione : MediaSessionService() {

    private var sessione: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        creaCanale()

        val apriApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nuova = MediaSession.Builder(this, PlayerCondiviso.player(this))
            .setSessionActivity(apriApp)
            .build()
        sessione = nuova

        setMediaNotificationProvider(ProviderNotifica(this))

        // **Senza questa riga non compare nessuna notifica.**
        //
        // `onGetSession` viene chiamato solo quando un `MediaController` si
        // connette al servizio, ed è da lì che Media3 registra la sessione col
        // proprio gestore di notifiche. Nella nostra app nessuno connette un
        // controller — l'interfaccia comanda il player direttamente — quindi
        // quel momento non arrivava mai: il servizio partiva, costruiva la
        // sessione, e restava muto.
        //
        // `addSession` fa esplicitamente quello che il controller avrebbe fatto
        // per caso.
        addSession(nuova)
        Log.d(TAG, "servizio avviato, sessione registrata")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sessione

    /**
     * L'utente ha tolto l'app dalle recenti mentre era in pausa: non c'è niente
     * da tenere in vita, e lasciare una notifica ferma sarebbe solo ingombro.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = sessione?.player
        if (player == null || !player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        sessione?.let {
            removeSession(it)
            it.release()
        }
        sessione = null
        super.onDestroy()
    }

    private fun creaCanale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canale = NotificationChannel(
            CANALE,
            "Riproduzione",
            // BASSA: è una notifica di stato, non un avviso. Con IMPORTANCE
            // DEFAULT suonerebbe e vibrerebbe a ogni cambio di traccia.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Comandi della traccia in ascolto e commento rapido"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canale)
    }

    companion object {
        const val CANALE = "riproduzione"
        const val ID_NOTIFICA = 1001
        private const val TAG = "ServizioRiproduzione"

        /** Accende il servizio, se non è già acceso. */
        fun avvia(context: Context) {
            val intento = Intent(context, ServizioRiproduzione::class.java)
            // `startForegroundService` da solo obbligherebbe a chiamare
            // `startForeground` entro pochi secondi: ci pensa MediaSessionService
            // quando il player comincia davvero a suonare.
            context.startService(intento)
        }

        fun ferma(context: Context) {
            context.stopService(Intent(context, ServizioRiproduzione::class.java))
        }
    }
}

/**
 * La notifica della traccia in ascolto: titolo, play/pausa e **Commenta**.
 *
 * Scritta a mano invece di usare quella predefinita di Media3 perché le sue
 * azioni sono tutte comandi del player, e "Commenta" non lo è: apre una nostra
 * schermata. Costruendo la notifica qui, i due tasti stanno sullo stesso piano
 * invece di essere uno dentro Media3 e uno appiccicato sopra.
 */
@UnstableApi
/**
 * "Prova 12 giugno · 3:24" — da dove viene il pezzo e quanto dura.
 *
 * La durata sta qui e non nel cronometro perché è **ferma**: quella che scorre
 * la conta Android da sé. Insieme si leggono come il minutaggio classico, con
 * il tempo trascorso a sinistra messo dal sistema.
 */
private fun sottotitolo(player: androidx.media3.common.Player): String {
    val cartella = player.mediaMetadata.artist?.toString().orEmpty()
    val durataMs = player.duration
    if (durataMs <= 0L) return cartella
    val totale = durataMs / 1000
    val durata = "%d:%02d".format(totale / 60, totale % 60)
    return if (cartella.isBlank()) durata else "$cartella · $durata"
}

private class ProviderNotifica(private val context: Context) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val player = mediaSession.player
        val suona = player.isPlaying
        val titolo = player.mediaMetadata.title?.toString().orEmpty()

        val notifica = NotificationCompat.Builder(context, ServizioRiproduzione.CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo.ifBlank { "RegiStrazio" })
            .setContentText(sottotitolo(player))
            // Il tempo che scorre anche a notifica chiusa, **senza
            // ripubblicarla ogni secondo**.
            //
            // `setUsesChronometer` è il meccanismo dei timer e delle chiamate:
            // si dà ad Android l'istante in cui il conteggio è cominciato e
            // l'orologio lo fa avanzare lui, nel proprio processo. Aggiornare
            // il testo da qui vorrebbe dire ricostruire e ripubblicare la
            // notifica una volta al secondo, che il sistema limita comunque.
            //
            // L'istante d'inizio è "adesso meno quanto abbiamo già suonato":
            // così dopo un seek il cronometro riparte dal punto giusto invece
            // che da zero. In pausa si spegne, o continuerebbe a correre su un
            // audio fermo.
            .setUsesChronometer(suona)
            .setWhen(
                if (suona) System.currentTimeMillis() - player.currentPosition.coerceAtLeast(0)
                else 0L
            )
            .setShowWhen(suona)
            .setContentIntent(mediaSession.sessionActivity)
            .setDeleteIntent(ComandiNotifica.intento(context, ComandiNotifica.FERMA))
            // Una notifica di stato non deve suonare né riallertare a ogni
            // aggiornamento di posizione.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(suona)
            .addAction(
                NotificationCompat.Action.Builder(
                    if (suona) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (suona) "Pausa" else "Riprendi",
                    ComandiNotifica.intento(context, ComandiNotifica.PLAY_PAUSA)
                ).build()
            )
            .addAction(CommentoRapido.azione(context))
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    // Play/pausa **e** commenta, entrambi in vista compatta.
                    //
                    // Prima c'era solo il primo, per non far aprire la tastiera
                    // per sbaglio scorrendo le notifiche. Ma la vista estesa su
                    // molti telefoni è scomoda da raggiungere, e commentare è
                    // il motivo per cui questa app esiste: nasconderlo dietro un
                    // gesto difficile lo rendeva di fatto irraggiungibile
                    // proprio nel momento in cui serve, cioè mentre ascolti con
                    // lo schermo bloccato.
                    .setShowActionsInCompactView(0, 1)
            )
            .build()

        return MediaNotification(ServizioRiproduzione.ID_NOTIFICA, notifica)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean = false
}

/**
 * I tasti della notifica che non sono il commento.
 *
 * Passano da un receiver nostro e non dalle azioni di Media3: quelle vanno
 * costruite con `MediaNotification.ActionFactory`, e mescolarle a mano con
 * un'azione `RemoteInput` dentro la stessa notifica è più fragile che
 * gestirsele tutte allo stesso modo.
 */
@UnstableApi
class ComandiNotifica : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val player = PlayerCondiviso.player(context)
        when (intent.action) {
            PLAY_PAUSA -> if (player.isPlaying) player.pause() else player.play()
            FERMA -> {
                player.pause()
                ServizioRiproduzione.ferma(context)
            }
        }
    }

    companion object {
        const val PLAY_PAUSA = "com.example.registrazio.PLAY_PAUSA"
        const val FERMA = "com.example.registrazio.FERMA"

        fun intento(context: Context, azione: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            azione.hashCode(),
            Intent(context, ComandiNotifica::class.java).setAction(azione),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
