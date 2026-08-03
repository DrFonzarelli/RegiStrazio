package com.example.registrazio.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.ui.components.AppIconButton
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppMaxWidth
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.util.secToLabel
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * `#mini-player`: la barra che compare quando la traccia in ascolto è uscita
 * dallo schermo, per riportartici sopra con un tap.
 *
 * Il tasto commento non apre un riquadro suo: ti porta sulla card e apre
 * quella vera. Una scorciatoia in meno da tenere allineata a due posti.
 */
@Composable
fun MiniPlayer(
    titolo: String,
    posizioneSecondi: Float,
    inRiproduzione: Boolean,
    onVaiAllaTraccia: () -> Unit,
    onTogglePlay: () -> Unit,
    onCommenta: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.miniplayerBg)
            .drawBehind {
                drawLine(
                    color = colors.border,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = AppMaxWidth),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MiniEqualizer(inRiproduzione)

            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onVaiAllaTraccia
                    )
            ) {
                Text(
                    titolo,
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${secToLabel(posizioneSecondi)} — tocca per tornare alla traccia",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AppIconButton(
                icon = AppIcons.Comment,
                contentDescription = "Vai alla traccia per commentare",
                onClick = onCommenta,
                size = 38.dp,
                iconSize = 20.dp,
                tint = colors.accent,
                background = colors.accentSoft
            )

            AppIconButton(
                icon = if (inRiproduzione) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (inRiproduzione) "Pausa" else "Riprendi",
                onClick = onTogglePlay,
                size = 38.dp,
                iconSize = 15.dp,
                tint = colors.accent,
                background = colors.accentSoft
            )
        }
    }
}

/** `.mini-eq`: cinque barrette accent che ballano solo mentre suona. */
@Composable
private fun MiniEqualizer(attivo: Boolean) {
    val colors = AppTheme.colors
    var altezze by remember { mutableStateOf(List(5) { 5f }) }

    LaunchedEffect(attivo) {
        if (!attivo) {
            altezze = List(5) { 5f }
            return@LaunchedEffect
        }
        while (true) {
            altezze = List(5) { 4f + Random.nextFloat() * 11f }
            delay(220)
        }
    }

    Row(
        Modifier.width(24.dp).height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        altezze.forEach { altezza ->
            val h by animateDpAsState(altezza.dp, tween(120), label = "miniEqBar")
            Box(
                Modifier
                    .weight(1f)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.accent)
            )
        }
    }
}
