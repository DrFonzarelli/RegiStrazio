package com.example.registrazio.ui.folder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.ui.Ordinamento
import com.example.registrazio.ui.RichiestaCommento
import com.example.registrazio.ui.StatoScaricamento
import com.example.registrazio.ui.components.appBorder
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.Radius

/**
 * Lista delle tracce di una cartella, con la barra di ordinamento in testa.
 */
@Composable
fun FolderScreen(
    tracce: List<Traccia>,
    ordinamento: Ordinamento,
    scaricateSuTotali: Pair<Int, Int>,
    bulkInCorso: Boolean,
    bulkInPausa: Boolean,
    tracciaInRiproduzione: String?,
    /** Play davvero premuto: decide il tasto della card e il bordo accent. */
    inRiproduzione: Boolean,
    audioAttivo: Boolean,
    /**
     * Lo stato della lista **arriva da fuori**.
     *
     * Non è un dettaglio: la barra in ascolto compare solo quando la card della
     * traccia è fuori dallo schermo, e per saperlo chi la disegna deve poter
     * leggere questo stato. Creandolo qui dentro, quello di fuori restava vuoto
     * e la barra non spariva mai.
     */
    statoLista: LazyListState,
    /** Traccia per cui è stato chiesto di aprire il riquadro del commento. */
    apriCommentoPer: RichiestaCommento?,
    scaricamenti: Map<String, StatoScaricamento>,
    posizioneSecondi: Float,
    mioAppUid: String,
    onCambiaOrdinamento: () -> Unit,
    onScaricaTutte: () -> Unit,
    azioniPerTraccia: (Traccia) -> TrackCardActions,
    onChiediEliminazione: (Traccia, Commento) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxWidth(), state = statoLista, contentPadding = contentPadding) {
        item {
            SortBar(
                ordinamento = ordinamento,
                scaricate = scaricateSuTotali.first,
                totali = scaricateSuTotali.second,
                // Somma le frazioni dei download in corso: senza, la barra
                // resterebbe ferma per tutta una traccia e poi salterebbe di
                // colpo. È comunque una misura vera, solo più fine.
                // Solo le tracce di questa cartella: `scaricamenti` è la coda
                // intera, e sommarla tutta gonfierebbe la barra con il lavoro
                // fatto altrove.
                parzialeInCorso = tracce.sumOf { t ->
                    (scaricamenti[t.id]?.frazione ?: 0f).toDouble()
                }.toFloat(),
                inCorso = bulkInCorso,
                inPausa = bulkInPausa,
                inCoda = tracce.count { scaricamenti[it.id]?.inAttesa == true },
                onCambiaOrdinamento = onCambiaOrdinamento,
                onScaricaTutte = onScaricaTutte
            )
            Spacer(Modifier.height(10.dp))
        }

        items(tracce, key = { it.id }) { traccia ->
            val selezionata = tracciaInRiproduzione == traccia.id
            TrackCard(
                traccia = traccia,
                // La posizione si mostra anche in pausa: è lì che sei rimasto.
                posizioneSecondi = if (selezionata) posizioneSecondi else 0f,
                // `is-playing` nel prototipo si toglie alla pausa, non al cambio
                // di traccia: bordo accent e tasto pieno dicono "sta suonando
                // adesso", non "è la traccia selezionata".
                inRiproduzione = selezionata && inRiproduzione,
                audioAttivo = selezionata && audioAttivo,
                scaricamento = scaricamenti[traccia.id],
                mioAppUid = mioAppUid,
                apriCommento = apriCommentoPer?.takeIf { it.tracciaId == traccia.id }?.seq,
                azioni = azioniPerTraccia(traccia),
                onChiediEliminazione = { onChiediEliminazione(traccia, it) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * `.sort-bar`: "Scarica tutte" a sinistra, interruttore di ordinamento a destra.
 */
@Composable
private fun SortBar(
    ordinamento: Ordinamento,
    scaricate: Int,
    totali: Int,
    parzialeInCorso: Float,
    inCorso: Boolean,
    inPausa: Boolean,
    inCoda: Int,
    onCambiaOrdinamento: () -> Unit,
    onScaricaTutte: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BulkDownloadButton(scaricate, totali, parzialeInCorso, inCorso, inPausa, onScaricaTutte)
            // Quante aspettano il turno, accanto al tasto che le ha messe in
            // fila: è lì che si guarda per sapere se la coda sta finendo. In
            // topbar la stessa informazione c'è solo quando si è altrove, dove
            // questo tasto non si vede.
            if (inCoda > 0) {
                Text(
                    "+$inCoda in coda",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
        SortToggle(ordinamento, onCambiaOrdinamento)
    }
}

/**
 * `.bulk-dl-btn`: il riempimento accent-soft avanza da sinistra a mano a mano
 * che le tracce scendono, così il progresso si legge senza barre aggiuntive.
 */
@Composable
private fun BulkDownloadButton(
    scaricate: Int,
    totali: Int,
    parzialeInCorso: Float,
    inCorso: Boolean,
    inPausa: Boolean,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    val complete = totali > 0 && scaricate >= totali
    val evidenziato = inCorso || inPausa || complete
    val frazione =
        if (totali == 0) 0f
        else ((scaricate + parzialeInCorso) / totali).coerceIn(0f, 1f)

    // `transition: width .35s linear` del prototipo. Lineare e non elastica: è
    // una misura, e una misura non deve rimbalzare.
    val riempimento by animateFloatAsState(
        targetValue = frazione,
        animationSpec = tween(350, easing = LinearEasing),
        label = "riempimentoBulk"
    )

    val etichetta = when {
        complete -> "Tutte scaricate"
        // Mentre la coda gira il tasto ferma tutto, e deve dirlo: è l'unico
        // modo di svuotare la fila in un colpo, e vale anche quando in fila
        // c'è una traccia sola messa a mano — la coda è una.
        inCorso -> "Ferma tutte · $scaricate/$totali"
        inPausa -> "Riprendi $scaricate/$totali"
        scaricate > 0 -> "$scaricate/$totali scaricate"
        else -> "Scarica tutte"
    }

    val tinta = if (evidenziato) colors.accent else colors.textSecondary

    Box(
        Modifier
            .width(148.dp)
            .clip(Radius.pillShape)
            .background(colors.surface)
            // Il riempimento si disegna, non si impagina.
            //
            // Prima era un `Box` figlio con `fillMaxHeight()`: dentro un Box che
            // prende l'altezza dal testo, "tutta l'altezza disponibile" vale
            // zero, e infatti non si vedeva niente. `drawBehind` misura il nodo
            // già impaginato, che è esattamente ciò che fa `position:absolute;
            // top:0; bottom:0` nel prototipo.
            .drawBehind {
                if (riempimento > 0f) {
                    drawRect(colors.accentSoft, size = Size(size.width * riempimento, size.height))
                }
            }
            .border(
                1.dp,
                if (evidenziato) colors.accent else colors.borderStrong,
                Radius.pillShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // L'icona dice cosa succede se lo tocchi: pausa mentre scarica,
            // play quando è fermo a metà.
            AppIcon(
                when {
                    complete -> AppIcons.CloudDone
                    inCorso -> AppIcons.Pause
                    inPausa -> AppIcons.Play
                    else -> AppIcons.Cloud
                },
                14.dp,
                tinta
            )
            Text(
                etichetta,
                color = tinta,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * `.sort-toggle`: un solo tasto con dentro entrambe le icone; quella attiva è
 * in accent. Un tap passa da un modo all'altro, senza due zone da centrare.
 */
@Composable
private fun SortToggle(ordinamento: Ordinamento, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .clip(Radius.pillShape)
            .background(colors.surface)
            .appBorder(colors.borderStrong, Radius.pill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppIcon(
            AppIcons.Sort, 13.dp,
            if (ordinamento == Ordinamento.DEFAULT) colors.accent else colors.textMuted
        )
        AppIcon(
            AppIcons.Star, 13.dp,
            if (ordinamento == Ordinamento.PREFERITE) colors.accent else colors.textMuted
        )
    }
}
