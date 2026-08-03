package com.example.registrazio.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.ui.components.appBorder
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIconSpec
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme

/**
 * Il menu "⋯" della traccia (`.more-btn` + `.track-dropdown`).
 *
 * Qui dentro finisce tutto ciò che non è un gesto quotidiano: i dettagli in
 * particolare stanno di proposito dietro due tap, così scorrere la lista non
 * espone passivamente contatori e statistiche di chi ha ascoltato cosa.
 */
@Composable
fun TrackMenu(
    traccia: Traccia,
    onCambiaDownload: () -> Unit,
    onRinomina: () -> Unit,
    onDettagli: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    var aperto by remember { mutableStateOf(false) }

    Box(modifier) {
        Box(
            Modifier
                .width(22.dp)
                .height(34.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { aperto = true },
            contentAlignment = Alignment.Center
        ) {
            AppIcon(AppIcons.More, 15.dp, colors.textMuted)
        }

        if (aperto) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 39.dp.roundToPx() }),
                onDismissRequest = { aperto = false }
            ) {
                Column(
                    Modifier
                        .shadow(10.dp, RoundedCornerShape(11.dp))
                        .clip(RoundedCornerShape(11.dp))
                        .background(colors.surface)
                        .appBorder(colors.border, 11.dp)
                        .widthIn(min = 190.dp)
                        .padding(4.dp)
                ) {
                    MenuItem(
                        icona = if (traccia.scaricata) AppIcons.CloudDone else AppIcons.Cloud,
                        etichetta = if (traccia.scaricata) "Rimuovi dal locale" else "Scarica in locale"
                    ) { aperto = false; onCambiaDownload() }

                    MenuItem(AppIcons.Edit, "Rinomina traccia") { aperto = false; onRinomina() }

                    MenuItem(AppIcons.Info, "Dettagli traccia") { aperto = false; onDettagli() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icona: AppIconSpec, etichetta: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
            AppIcon(icona, 17.dp, colors.textSecondary)
        }
        Text(etichetta, color = colors.text, fontSize = 13.5.sp)
    }
}
