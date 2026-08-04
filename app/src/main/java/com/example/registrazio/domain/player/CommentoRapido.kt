package com.example.registrazio.domain.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.example.registrazio.data.local.ArchivioLocale
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.StatoSync
import com.example.registrazio.domain.identity.IdentityManager
import com.example.registrazio.ui.components.AppTextField
import com.example.registrazio.ui.components.FilledButton
import com.example.registrazio.ui.components.SecondaryButton
import com.example.registrazio.ui.components.appBorder
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.AppMaxWidth
import com.example.registrazio.ui.theme.Radius
import com.example.registrazio.ui.theme.RegiStrazioTheme
import com.example.registrazio.util.labelToSec
import com.example.registrazio.util.secToLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Il commento lasciato dalla notifica, senza tornare nell'app.
 *
 * **Perché una schermatina e non la risposta rapida di Android.** La risposta
 * rapida (`RemoteInput`) avvisa solo quando l'utente **invia**: non esiste modo
 * di sapere quando ha aperto il campo. Ma il minutaggio giusto è quello del
 * momento in cui cominci a scrivere — nel prototipo è esattamente lì che
 * `lnCommentSeconds` viene congelato — e un commento appiccicato al secondo in
 * cui premi invio finirebbe sistematicamente dopo il punto di cui parla.
 *
 * Con una schermata nostra quel momento lo conosciamo: è `onCreate`. E ci
 * entra anche il lucchetto della card, che sbloccato fa scorrere il minutaggio
 * insieme al cursore.
 *
 * **L'audio non si ferma mai.** Non si tocca il player: si legge e basta.
 */
@UnstableApi
class CommentoRapido : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Il minutaggio si congela **adesso**, all'apertura, non al salvataggio.
        val puntoDiPartenza = PlayerCondiviso.posizioneSecondi
        val traccia = PlayerCondiviso.inAscolto.value
        val io = IdentityManager(this).identita

        if (traccia == null || io == null) {
            finish()
            return
        }

        setContent {
            // Il tema chiaro/scuro qui non è leggibile dallo stato dell'app:
            // questa schermata può aprirsi senza che l'app sia viva. Si segue
            // quello di sistema, che è la risposta meno sorprendente.
            RegiStrazioTheme(darkTheme = isSystemInDarkTheme()) {
                RiquadroCommento(
                    titoloTraccia = traccia.titolo,
                    puntoDiPartenza = puntoDiPartenza,
                    onAnnulla = { finish() },
                    onSalva = { secondi, testo ->
                        salva(traccia.id, io.appUid, io.nome, io.colore, secondi, testo)
                        finish()
                    }
                )
            }
        }
    }

    private fun salva(
        tracciaId: String,
        appUid: String,
        nome: String,
        colore: String,
        secondi: Float,
        testo: String
    ) {
        val commento = Commento(
            id = UUID.randomUUID().toString(),
            tracciaId = tracciaId,
            appUid = appUid,
            autoreNome = nome,
            autoreColore = colore,
            timestampSecondi = secondi,
            testo = testo.trim(),
            statoSync = StatoSync.LOCALE
        )
        // Scope applicativo e non `lifecycleScope`: la schermata si chiude
        // subito dopo, e una scrittura legata al suo ciclo di vita verrebbe
        // annullata a metà.
        val app = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ArchivioLocale(app).salvaCommento(commento)
                CommentiDaFuori.annuncia(tracciaId)
            } catch (e: Throwable) {
                Log.w(TAG, "commento dalla notifica non salvato: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "CommentoRapido"

        /** L'azione "Commenta" della notifica. */
        fun azione(context: Context): androidx.core.app.NotificationCompat.Action {
            val intento = Intent(context, CommentoRapido::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val inSospeso = PendingIntent.getActivity(
                context,
                0,
                intento,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_edit,
                "Commenta",
                inSospeso
            )
                // Apre una finestra: da schermo bloccato Android chiederà prima
                // di sbloccare, ed è giusto così — bisogna comunque digitare.
                .setShowsUserInterface(true)
                .build()
        }
    }
}

/**
 * Lo stesso riquadro della card, ridotto all'essenziale.
 *
 * Testo, minutaggio col lucchetto, Annulla e Salva. Chi lo apre riconosce
 * quello che usa dentro l'app, e non c'è una seconda grammatica da imparare.
 */
@Composable
private fun RiquadroCommento(
    titoloTraccia: String,
    puntoDiPartenza: Float,
    onAnnulla: () -> Unit,
    onSalva: (Float, String) -> Unit
) {
    val colors = AppTheme.colors
    var testo by remember { mutableStateOf("") }
    var bloccato by remember { mutableStateOf(true) }
    var tempo by remember { mutableStateOf(secToLabel(puntoDiPartenza)) }

    // Sbloccato il minutaggio segue il cursore, esattamente come nella card.
    // Bloccato resta sul punto in cui hai aperto il riquadro: è il motivo per
    // cui questa schermata esiste invece della risposta rapida di sistema.
    //
    // Si interroga il player solo finché il riquadro è aperto: non serve uno
    // stato globale che ticchetta per tutta la vita dell'app.
    LaunchedEffect(bloccato) {
        while (!bloccato) {
            tempo = secToLabel(PlayerCondiviso.posizioneSecondi)
            delay(250)
        }
    }

    val fuoco = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fuoco.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAnnulla
            )
            .padding(16.dp)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = AppMaxWidth)
                .clip(Radius.cardLg)
                .background(colors.surface)
                .appBorder(colors.border, Radius.lg)
                // Un tap dentro il riquadro non deve chiuderlo.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                titoloTraccia,
                color = colors.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            AppTextField(
                value = testo,
                onValueChange = { testo = it },
                placeholder = "Scrivi un commento...",
                singleLine = false,
                minHeight = 72.dp,
                modifier = Modifier.fillMaxWidth().focusRequester(fuoco)
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    AppTextField(
                        value = tempo,
                        onValueChange = { tempo = it },
                        readOnly = bloccato,
                        textAlign = TextAlign.Center,
                        background = if (bloccato) colors.surfaceAlt else colors.surface,
                        modifier = Modifier.width(58.dp)
                    )
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (bloccato) colors.surface else colors.accentSoft)
                            .border(
                                1.dp,
                                if (bloccato) colors.borderStrong else colors.accent,
                                CircleShape
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { bloccato = !bloccato },
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            if (bloccato) AppIcons.Lock else AppIcons.Unlock,
                            13.dp,
                            if (bloccato) colors.textMuted else colors.accent
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Annulla", onAnnulla, fontSize = 14.sp, paddingVertical = 8.dp)
                    FilledButton(
                        "Salva",
                        colors.accent,
                        {
                            if (testo.isNotBlank()) {
                                onSalva(labelToSec(tempo).toFloat().coerceAtLeast(0f), testo)
                            }
                        },
                        fontSize = 14.sp,
                        paddingVertical = 8.dp
                    )
                }
            }
        }
    }
}
