package com.example.registrazio.ui.folder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Commento
import com.example.registrazio.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val EQ_BARS = 36
private val LINE_HEIGHT = 26.dp
private val MARKER_SIZE = 17.dp
private val PLAYHEAD_WIDTH = 23.dp

/**
 * `.track-line` del prototipo: quattro livelli sovrapposti ad altezza fissa —
 * equalizzatore di sfondo, binario, marker dei commenti, cursore.
 *
 * L'altezza non cambia mai, nemmeno quando la traccia parte: è il motivo per
 * cui l'equalizzatore sta sullo sfondo invece di comparire sopra il binario.
 * Se si aggiungesse in play, tutto il contenuto sotto salterebbe.
 */
@Composable
fun Timeline(
    durataSecondi: Int,
    posizioneSecondi: Float,
    commenti: List<Commento>,
    indiceSelezionato: Int?,
    /** Audio davvero in corso: muove l'equalizzatore. Non è "play premuto". */
    inRiproduzione: Boolean,
    onMarkerCliccato: (Int) -> Unit,
    onSposta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    var inTrascinamento by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(LINE_HEIGHT)
    ) {
        val larghezza = maxWidth

        EqualizerBackground(inRiproduzione)

        // .rail — il binario grigio a metà altezza
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = 12.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.borderStrong)
        )

        // La durata resta 0 finché non l'abbiamo letta dal file audio: senza
        // questa rete la divisione darebbe NaN e il layout ne uscirebbe rotto.
        val durataSicura = durataSecondi.coerceAtLeast(1)

        // marker dei commenti, centrati sul proprio minutaggio
        commenti.forEachIndexed { indice, commento ->
            val frazione = (commento.timestampSecondi / durataSicura).coerceIn(0f, 1f)
            val selezionato = indice == indiceSelezionato
            Box(
                Modifier
                    .offset(
                        x = larghezza * frazione - MARKER_SIZE / 2,
                        y = 4.dp
                    )
                    .size(MARKER_SIZE)
                    .clip(CircleShape)
                    .background(colors.avatarFor(commento.iniziale))
                    .then(
                        if (selezionato) Modifier.border(2.dp, colors.accent, CircleShape)
                        else Modifier
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onMarkerCliccato(indice) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    commento.iniziale,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 9.sp
                )
            }
        }

        // .playhead — un rientro dell'1.1% per non farlo mai toccare il bordo
        val inset = 0.011f
        val frazionePos = (posizioneSecondi / durataSicura).coerceIn(0f, 1f)

        // Durante il trascinamento comanda il dito, non il player.
        //
        // Prima la nuova posizione veniva ricavata dalla posizione del cursore,
        // che il trascinamento stesso stava aggiornando: un anello che si
        // retroalimentava. E ogni movimento faceva partire un seek, mentre il
        // ciclo di aggiornamento riscriveva la posizione con quella del player,
        // ancora vicina a zero perché stava ribufferizzando. Da qui il cursore
        // che sfarfallava tornando all'inizio.
        var frazioneTrascinata by remember { mutableStateOf<Float?>(null) }

        // Il blocco dei gesti si ricrea solo se cambiano le sue chiavi: senza
        // questo leggerebbe per sempre i valori della prima composizione.
        val frazioneCorrente by rememberUpdatedState(frazionePos)
        val spostaAggiornato by rememberUpdatedState(onSposta)
        val durataAggiornata by rememberUpdatedState(durataSecondi)

        val frazioneVisiva = inset + (frazioneTrascinata ?: frazionePos) * (1f - inset * 2)

        Box(
            Modifier
                .offset(x = larghezza * frazioneVisiva - PLAYHEAD_WIDTH / 2)
                .width(PLAYHEAD_WIDTH)
                .height(LINE_HEIGHT)
                .pointerInput(larghezza) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            inTrascinamento = true
                            frazioneTrascinata = frazioneCorrente
                        },
                        onDragEnd = {
                            inTrascinamento = false
                            // Un solo salto, alla fine: seguire il dito con un
                            // seek per movimento tiene il player a ribufferizzare
                            // e non si riesce mai a mirare un punto.
                            frazioneTrascinata?.let { spostaAggiornato(it * durataAggiornata) }
                            frazioneTrascinata = null
                        },
                        onDragCancel = {
                            inTrascinamento = false
                            frazioneTrascinata = null
                        }
                    ) { change, delta ->
                        change.consume()
                        // Somma di spostamenti, non ricalcolo dalla posizione:
                        // il riquadro si muove col cursore, e leggerne la
                        // posizione mentre lo si trascina si morde la coda.
                        val passo = with(density) { delta.toDp() } / larghezza
                        frazioneTrascinata =
                            ((frazioneTrascinata ?: frazioneCorrente) + passo).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .scale(if (inTrascinamento) 1.4f else 1f)
                    .then(
                        if (inTrascinamento) Modifier.border(6.dp, colors.accentRing, CircleShape)
                        else Modifier
                    )
                    .shadow(if (inTrascinamento) 3.dp else 1.dp, CircleShape)
                    .size(9.5.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color.White)
                    .border(2.2.dp, colors.accent, CircleShape)
            )
        }
    }
}

/**
 * `.eq-bg`: 36 barrette che ballano solo mentre la traccia suona.
 * A riposo restano a 3dp con opacità 0.14, così lo spazio è già occupato.
 */
@Composable
private fun EqualizerBackground(attivo: Boolean) {
    val colors = AppTheme.colors
    var altezze by remember { mutableStateOf(List(EQ_BARS) { 3f }) }

    LaunchedEffect(attivo) {
        if (!attivo) {
            altezze = List(EQ_BARS) { 3f }
            return@LaunchedEffect
        }
        while (true) {
            altezze = List(EQ_BARS) { 2f + Random.nextFloat() * 15f }
            delay(200)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .alpha(if (attivo) 0.3f else 0.14f)
            .padding(horizontal = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        altezze.forEach { altezza ->
            val h by animateDpAsState(altezza.dp, tween(140), label = "eqBar")
            Box(
                Modifier
                    .weight(1f)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.borderStrong)
            )
        }
    }
}
