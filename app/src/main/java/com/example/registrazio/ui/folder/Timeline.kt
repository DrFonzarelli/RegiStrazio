package com.example.registrazio.ui.folder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
private val PLAYHEAD_DOT = 9.5.dp

/** `box-shadow: 0 0 0 6px var(--accent-ring)`: quanto l'alone esce dal pallino. */
private val ALONE = 6.dp

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

        // La durata resta 0 finché non l'abbiamo letta dal file audio, e la
        // leggiamo solo premendo play: una traccia mai avviata con sopra i
        // commenti di qualcun altro è la norma, non un caso limite.
        //
        // Dividere per 0 darebbe NaN, ma il ripiego a 1 non basta: manderebbe
        // ogni marker oltre il fondo scala, ammassandoli tutti contro il bordo
        // destro come se i commenti fossero tutti sull'ultimo secondo. Finché
        // la durata vera non si sa, la si stima dal commento più avanzato — la
        // traccia dura *almeno* quanto il punto di cui qualcuno ha parlato — e
        // un margine tiene l'ultimo marker staccato dal bordo. Al primo play la
        // durata vera prende il posto della stima e i marker si assestano.
        val durataSicura = when {
            durataSecondi > 0 -> durataSecondi.toFloat()
            commenti.isEmpty() -> 1f
            else -> commenti.maxOf { it.timestampSecondi }.coerceAtLeast(1f) * 1.15f
        }

        // La stima e la durata vera possono essere molto lontane, e il momento
        // in cui la seconda sostituisce la prima è il primo play: senza
        // animazione tutti i marker si teletrasportano insieme, e sembra che si
        // sia rotto qualcosa proprio mentre parte l'audio. Scivolando, la
        // stessa correzione si legge per quello che è — la scala che si
        // assesta. Solo per i marker: il cursore deve stare dove sta il suono,
        // e a quel punto è comunque all'inizio.
        val durataMarker by animateFloatAsState(
            targetValue = durataSicura,
            animationSpec = tween(450),
            label = "scala"
        )

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

        // Il gesto sta sull'**intera riga**, non sul pallino.
        //
        // Il prototipo usa `setPointerCapture` e ricalcola la posizione dal dito
        // (`(clientX - rect.left) / rect.width`): il cursore sta esattamente
        // sotto il dito, sempre. Somma di spostamenti su un riquadro largo 23dp
        // invece costava lo slop iniziale — il pallino restava indietro di quei
        // millimetri per tutta la trascinata, e non si riusciva a mirare.
        //
        // Ascoltando sulla riga, che non si muove, la x del dito è già la
        // coordinata giusta e non serve inseguire niente.
        val larghezzaPx = with(density) { larghezza.toPx() }
        val presaPx = with(density) { PLAYHEAD_WIDTH.toPx() } / 2f

        Box(
            Modifier
                .matchParentSize()
                .pointerInput(larghezzaPx) {
                    awaitEachGesture {
                        val giu = awaitFirstDown(requireUnconsumed = false)
                        val centro = (inset + frazioneCorrente * (1f - inset * 2)) * larghezzaPx
                        // Solo se il dito è atterrato sul cursore: altrove sulla
                        // riga ci sono i marker dei commenti, che hanno il loro tap.
                        if (kotlin.math.abs(giu.position.x - centro) > presaPx) return@awaitEachGesture

                        giu.consume()
                        inTrascinamento = true
                        frazioneTrascinata = frazioneCorrente

                        while (true) {
                            val evento = awaitPointerEvent()
                            val punta = evento.changes.firstOrNull { it.id == giu.id } ?: break
                            if (!punta.pressed) {
                                punta.consume()
                                break
                            }
                            punta.consume()
                            frazioneTrascinata =
                                ((punta.position.x / larghezzaPx - inset) / (1f - inset * 2))
                                    .coerceIn(0f, 1f)
                        }

                        inTrascinamento = false
                        // Un solo salto, alla fine: seguire il dito con un seek
                        // per movimento tiene il player a ribufferizzare e non si
                        // riesce mai a mirare un punto.
                        frazioneTrascinata?.let { spostaAggiornato(it * durataAggiornata) }
                        frazioneTrascinata = null
                    }
                }
        )

        // I marker **dopo** la presa del cursore, quindi sopra di essa: chi tocca
        // un pallino con l'iniziale vuole quel commento, e il suo tap non deve
        // finire nel trascinamento. Al cursore resta tutto il resto della riga.
        //
        // Fotografia dei commenti già presenti, scattata una volta sola: serve a
        // far entrare con l'animazione solo quelli che *arrivano*, come `is-new`
        // nel prototipo, e non tutti a ogni scorrimento della lista.
        val giaVisti = remember { commenti.map { it.id }.toSet() }

        commenti.forEachIndexed { indice, commento ->
            val frazione = (commento.timestampSecondi / durataMarker).coerceIn(0f, 1f)
            MarkerCommento(
                iniziale = commento.iniziale,
                colore = colors.avatarFor(commento.iniziale),
                selezionato = indice == indiceSelezionato,
                nuovo = commento.id !in giaVisti,
                offsetX = larghezza * frazione - MARKER_SIZE / 2,
                onClick = { onMarkerCliccato(indice) }
            )
        }

        Box(
            Modifier
                .offset(x = larghezza * frazioneVisiva - PLAYHEAD_WIDTH / 2)
                .width(PLAYHEAD_WIDTH)
                .height(LINE_HEIGHT),
            contentAlignment = Alignment.Center
        ) {
            val scalaPallino by animateFloatAsState(
                if (inTrascinamento) 1.4f else 1f,
                tween(120),
                label = "scalaPlayhead"
            )
            val alone by animateFloatAsState(
                if (inTrascinamento) 1f else 0f,
                tween(120),
                label = "alonePlayhead"
            )

            Box(
                Modifier
                    .size(PLAYHEAD_DOT)
                    .scale(scalaPallino)
                    // `box-shadow: 0 0 0 6px var(--accent-ring)`: un alone che
                    // cresce **fuori** dal pallino.
                    //
                    // `border()` non poteva farlo: in Compose il bordo si disegna
                    // dentro i propri limiti, quindi mangiava il pallino invece
                    // di circondarlo. Disegnandolo si ottiene quello che fa il CSS.
                    .drawBehind {
                        if (alone > 0f) {
                            drawCircle(
                                color = colors.accentRing.copy(
                                    alpha = colors.accentRing.alpha * alone
                                ),
                                radius = size.minDimension / 2f + ALONE.toPx() * alone
                            )
                        }
                    }
                    .shadow(if (inTrascinamento) 3.dp else 1.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.2.dp, colors.accent, CircleShape)
            )
        }
    }
}

/**
 * `.marker`: il pallino con l'iniziale di chi ha commentato, sul suo minutaggio.
 *
 * Selezionato porta due anelli concentrici — uno del colore dello sfondo che fa
 * da stacco, uno accent — che nel prototipo sono due `box-shadow` sovrapposti e
 * quindi stanno **fuori** dal pallino. Con un `border` starebbero dentro e
 * mangerebbero l'iniziale, che è l'unica cosa che il marker deve far leggere.
 */
@Composable
private fun MarkerCommento(
    iniziale: String,
    colore: Color,
    selezionato: Boolean,
    nuovo: Boolean,
    offsetX: Dp,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    val interazione = remember { MutableInteractionSource() }
    val premuto by interazione.collectIsPressedAsState()

    // `@keyframes markerEnter`: entra da 0.2 con un piccolo scatto oltre l'uno.
    var entrato by remember { mutableStateOf(!nuovo) }
    LaunchedEffect(Unit) { entrato = true }
    val ingresso by animateFloatAsState(
        if (entrato) 1f else 0.2f,
        spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "ingressoMarker"
    )

    val scala = ingresso * if (premuto) 0.92f else 1f

    Box(
        Modifier
            .offset(x = offsetX, y = 4.dp)
            .size(MARKER_SIZE)
            .alpha(ingresso.coerceIn(0f, 1f))
            .scale(scala)
            .drawBehind {
                if (selezionato) {
                    val r = size.minDimension / 2f
                    drawCircle(colors.surfaceAlt, radius = r + 2.dp.toPx())
                    drawCircle(colors.accent, radius = r + 2.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                }
            }
            .clip(CircleShape)
            .background(colore)
            .clickable(interactionSource = interazione, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            iniziale,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 9.sp
        )
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
