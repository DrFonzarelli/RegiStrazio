package com.example.registrazio.ui.folder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.ui.components.AppBottomSheet
import com.example.registrazio.ui.components.SecondaryButton
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.util.secToLabel

/**
 * `#track-details-sheet`: le statistiche della traccia, raggiungibili solo
 * cercandole nel menu "⋯".
 *
 * Il grafico è cliccabile: toccare un punto riprende l'ascolto da lì.
 */
@Composable
fun TrackDetailsSheet(
    traccia: Traccia,
    inRiproduzione: Boolean,
    onChiudi: () -> Unit,
    onTogglePlay: () -> Unit,
    onRiproduciDa: (Float) -> Unit
) {
    val colors = AppTheme.colors
    val haDati = traccia.playBuckets.any { it > 0f }

    AppBottomSheet(onDismiss = onChiudi) {
        Text(
            traccia.titolo,
            color = colors.text,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Stat(secToLabel(traccia.durataSecondi), "durata", Modifier.weight(1f))
            Stat("${traccia.ascolti}", "ascolti", Modifier.weight(1f))
            Stat("${traccia.commenti.size}", "commenti", Modifier.weight(1f))
            Stat("${traccia.downloadEvents}", "download", Modifier.weight(1f))
        }

        Spacer(Modifier.height(18.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Punti più ascoltati",
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (inRiproduzione) colors.accent else colors.surface)
                    .border(
                        1.dp,
                        if (inRiproduzione) colors.accent else colors.borderStrong,
                        CircleShape
                    )
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTogglePlay
                    ),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    if (inRiproduzione) AppIcons.Pause else AppIcons.Play,
                    12.dp,
                    if (inRiproduzione) Color.White else colors.textSecondary
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (haDati) {
            AscoltiChart(
                buckets = traccia.playBuckets,
                onTapFrazione = { frazione -> onRiproduciDa(frazione * traccia.durataSecondi) }
            )
            Spacer(Modifier.height(6.dp))
        }

        Text(
            if (haDati) {
                "Tocca il grafico per riascoltare da quel punto. I download non " +
                    "garantiscono che la traccia sia stata poi ascoltata qui."
            } else {
                "Non ci sono ancora abbastanza ascolti per mostrare un grafico. I download " +
                    "non garantiscono che la traccia sia stata poi ascoltata qui."
            },
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 15.4.sp
        )

        Spacer(Modifier.height(16.dp))
        SecondaryButton("Chiudi", onChiudi, Modifier.fillMaxWidth())
    }
}

/** `.td-stat`: riquadro con valore grande e etichetta piccola. */
@Composable
private fun Stat(valore: String, etichetta: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceAlt)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(valore, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(etichetta, color = colors.textMuted, fontSize = 10.5.sp)
    }
}

/**
 * L'area + linea di `#td-chart`, disegnata sui 24 secchielli di ascolto.
 */
@Composable
private fun AscoltiChart(buckets: List<Float>, onTapFrazione: (Float) -> Unit) {
    val colors = AppTheme.colors
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTapFrazione((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val massimo = buckets.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val passoX = size.width / (buckets.size - 1)
        val punti = buckets.mapIndexed { i, v ->
            // lo 0.88 e il -2 replicano il margine che il grafico ha nel prototipo
            androidx.compose.ui.geometry.Offset(
                i * passoX,
                size.height - (v / massimo) * size.height * 0.88f - 2f
            )
        }

        val linea = Path().apply {
            punti.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        val area = Path().apply {
            addPath(linea)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        drawPath(area, colors.accentSoft)
        drawPath(
            linea,
            colors.accent,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
