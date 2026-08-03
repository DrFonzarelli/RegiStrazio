package com.example.registrazio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme

/**
 * `header.topbar` del prototipo.
 *
 * Il `backdrop-filter: blur(10px)` dell'originale non ha un equivalente diretto
 * in Compose (`Modifier.blur` sfoca il contenuto proprio, non ciò che sta
 * sotto). Il colore `topbarBg` porta però già la sua alpha — su questi fondi
 * chiari la resa è pressoché la stessa, senza aggiungere dipendenze.
 */
@Composable
fun AppTopBar(
    titolo: String,
    mostraIndietro: Boolean,
    avatar: (@Composable () -> Unit)?,
    temaScuro: Boolean,
    onIndietro: () -> Unit,
    onCambiaTema: () -> Unit,
    onAggiorna: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors

    // Ogni tap fa compiere all'icona un giro completo, come `.refresh-spin`.
    var giri by remember { mutableIntStateOf(0) }
    val rotazione by animateFloatAsState(
        targetValue = giri * 360f,
        animationSpec = tween(500),
        label = "refresh"
    )

    Row(
        modifier
            .fillMaxWidth()
            .background(colors.topbarBg)
            .drawBehind {
                drawLine(
                    color = colors.border,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (mostraIndietro) {
            AppIconButton(
                icon = AppIcons.Back,
                contentDescription = "Indietro",
                onClick = onIndietro,
                iconSize = 19.dp
            )
        }

        Text(
            text = titolo,
            color = colors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.16).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        avatar?.invoke()

        AppIconButton(
            icon = if (temaScuro) AppIcons.Sun else AppIcons.Moon,
            contentDescription = if (temaScuro) "Passa al tema chiaro" else "Passa al tema scuro",
            onClick = onCambiaTema,
            iconSize = if (temaScuro) 17.dp else 16.dp
        )

        AppIconButton(
            icon = AppIcons.Refresh,
            contentDescription = "Aggiorna",
            onClick = { giri++; onAggiorna() },
            modifier = Modifier.rotate(rotazione)
        )
    }
}
