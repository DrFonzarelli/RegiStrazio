package com.example.registrazio.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.ui.Ordinamento
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
    tracciaInRiproduzione: String?,
    audioAttivo: Boolean,
    scaricamenti: Map<String, Float>,
    posizioneSecondi: Float,
    mioAppUid: String,
    onCambiaOrdinamento: () -> Unit,
    onScaricaTutte: () -> Unit,
    azioniPerTraccia: (Traccia) -> TrackCardActions,
    onChiediEliminazione: (Traccia, Commento) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding) {
        item {
            SortBar(
                ordinamento = ordinamento,
                scaricate = scaricateSuTotali.first,
                totali = scaricateSuTotali.second,
                inCorso = bulkInCorso,
                onCambiaOrdinamento = onCambiaOrdinamento,
                onScaricaTutte = onScaricaTutte
            )
            Spacer(Modifier.height(10.dp))
        }

        items(tracce, key = { it.id }) { traccia ->
            val suona = tracciaInRiproduzione == traccia.id
            TrackCard(
                traccia = traccia,
                posizioneSecondi = if (suona) posizioneSecondi else 0f,
                inRiproduzione = suona,
                audioAttivo = suona && audioAttivo,
                scaricamento = scaricamenti[traccia.id],
                mioAppUid = mioAppUid,
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
    inCorso: Boolean,
    onCambiaOrdinamento: () -> Unit,
    onScaricaTutte: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BulkDownloadButton(scaricate, totali, inCorso, onScaricaTutte)
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
    inCorso: Boolean,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    val complete = totali > 0 && scaricate >= totali
    val evidenziato = inCorso || complete
    val frazione = if (totali == 0) 0f else scaricate.toFloat() / totali

    val etichetta = when {
        complete -> "Tutte scaricate"
        scaricate > 0 -> "$scaricate/$totali scaricate"
        else -> "Scarica tutte"
    }

    Box(
        Modifier
            .width(148.dp)
            .clip(Radius.pillShape)
            .background(colors.surface)
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
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(frazione)
                .background(colors.accentSoft)
        )
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tinta = if (evidenziato) colors.accent else colors.textSecondary
            AppIcon(if (complete) AppIcons.CloudDone else AppIcons.Cloud, 14.dp, tinta)
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
