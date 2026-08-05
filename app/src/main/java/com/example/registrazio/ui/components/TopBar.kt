package com.example.registrazio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.R
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme

/**
 * `header.topbar` del prototipo.
 *
 * Il `backdrop-filter: blur(10px)` dell'originale non ha un equivalente diretto
 * in Compose (`Modifier.blur` sfoca il contenuto proprio, non ciò che sta
 * sotto). Il colore `topbarBg` porta però già la sua alpha — su questi fondi
 * chiari la resa è pressoché la stessa, senza aggiungere dipendenze.
 *
 * **La prima casella a sinistra non è mai vuota.** Dentro una cartella ospita
 * la freccia indietro, altrove il logo: sempre la stessa larghezza, quindi il
 * titolo non si sposta passando da una schermata all'altra. Una topbar che
 * cambia impaginazione a ogni sezione si legge come una barra diversa, ed era
 * il punto da togliere di mezzo.
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
    modifier: Modifier = Modifier,
    mostraAggiorna: Boolean = true
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
        } else {
            // Stessa casella da 40dp di AppIconButton, così il titolo parte
            // dalla stessa ascissa con o senza freccia.
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            }
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

        if (mostraAggiorna) {
            AppIconButton(
                icon = AppIcons.Refresh,
                contentDescription = "Aggiorna",
                onClick = { giri++; onAggiorna() },
                modifier = Modifier.rotate(rotazione)
            )
        }
    }
}
