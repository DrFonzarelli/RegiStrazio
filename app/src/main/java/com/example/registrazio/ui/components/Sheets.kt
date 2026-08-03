package com.example.registrazio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.registrazio.ui.theme.AppMaxWidth
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.Radius

/**
 * Foglio ancorato in basso con velo scuro sopra il resto — lo schema che il
 * prototipo usa per conferme, dettagli traccia e account.
 *
 * Il tap sul velo chiude, come negli overlay originali.
 */
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    contenuto: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    val veloInteraction = remember { MutableInteractionSource() }
    val foglioInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(
                    interactionSource = veloInteraction,
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier
                    .fillMaxWidth()
                    .widthIn(max = AppMaxWidth)
                    .clip(Radius.sheetShape)
                    .background(colors.surface)
                    // assorbe il tap: toccare il foglio non deve chiuderlo
                    .clickable(
                        interactionSource = foglioInteraction,
                        indication = null,
                        onClick = {}
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)
            ) {
                contenuto()
            }
        }
    }
}

/** Conferma a due tasti: `#confirm-sheet` e `#bulk-download-sheet` del prototipo. */
@Composable
fun ConfermaSheet(
    titolo: String,
    testo: String,
    etichettaConferma: String,
    coloreConferma: Color,
    onAnnulla: () -> Unit,
    onConferma: () -> Unit
) {
    val colors = AppTheme.colors
    AppBottomSheet(onDismiss = onAnnulla) {
        Text(titolo, color = colors.text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(testo, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 19.6.sp)
        Spacer(Modifier.height(16.dp))
        SheetActions(
            testoAnnulla = "Annulla",
            testoConferma = etichettaConferma,
            coloreConferma = coloreConferma,
            onAnnulla = onAnnulla,
            onConferma = onConferma
        )
    }
}
