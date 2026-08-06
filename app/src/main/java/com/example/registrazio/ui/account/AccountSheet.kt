package com.example.registrazio.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Utente
import com.example.registrazio.ui.components.AppBottomSheet
import com.example.registrazio.ui.components.AppIconButton
import com.example.registrazio.ui.components.Avatar
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * `#account-sheet`: chi sei, quali cartelle hai collegato, e gli strumenti di
 * test per simulare reinstallazione e svuotamento del cloud.
 */
@Composable
fun AccountSheet(
    utente: Utente,
    cartelleCollegate: List<Cartella>,
    onChiudi: () -> Unit,
    onRimuoviCartella: (String) -> Unit,
    onSimulaReinstallazione: () -> Unit,
    onSvuotaCloud: () -> Unit,
    onSvuotaFirestore: () -> Unit
) {
    val colors = AppTheme.colors

    AppBottomSheet(onDismiss = onChiudi) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Avatar(
                lettera = utente.iniziale,
                colore = colors.paletteFor(utente.colore),
                size = 36.dp,
                fontSize = 14.sp
            )
            Column(Modifier.weight(1f)) {
                Text(
                    utente.nome,
                    color = colors.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text("Il tuo account", color = colors.textMuted, fontSize = 12.5.sp)
            }
            AppIconButton(AppIcons.X, "Chiudi", onChiudi, iconSize = 14.dp)
        }

        Spacer(Modifier.height(18.dp))

        Text(
            "CARTELLE COLLEGATE",
            color = colors.textMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.345.sp
        )
        Spacer(Modifier.height(10.dp))

        if (cartelleCollegate.isEmpty()) {
            Text(
                "Nessuna cartella collegata.",
                color = colors.textMuted,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp)
            )
        } else {
            cartelleCollegate.forEachIndexed { indice, cartella ->
                RigaCartella(
                    nome = cartella.nome,
                    ultima = indice == cartelleCollegate.lastIndex,
                    onRimuovi = { onRimuoviCartella(cartella.id) }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = colors.border,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .padding(top = 14.dp)
        ) {
            Text(
                "STRUMENTI DI TEST",
                color = colors.textMuted,
                fontSize = 11.sp,
                letterSpacing = 0.33.sp
            )
            Spacer(Modifier.height(4.dp))
            DevButton(
                "Simula reinstallazione (cancella solo questo dispositivo)",
                colors.textSecondary,
                onSimulaReinstallazione
            )
            DevButton(
                "Riparti dai dati di prova (cancella tutto il resto)",
                colors.danger,
                onSvuotaCloud
            )
            // Distruttivo per **tutto il gruppo**, non solo per questo
            // telefono. Sta qui perché in questa fase l'unico che scrive è chi
            // sviluppa; va tolto prima che l'app arrivi agli altri.
            DevButton("Svuota il database del gruppo", colors.danger, onSvuotaFirestore)
        }
    }
}

/**
 * `.acc-folder-row` con rimozione in due tempi: il cestino si trasforma in
 * "annulla" e accanto compare la conferma rossa, che si ritira da sola dopo
 * qualche secondo. Scollegare una cartella per sbaglio è facile, disfarlo no.
 */
@Composable
private fun RigaCartella(nome: String, ultima: Boolean, onRimuovi: () -> Unit) {
    val colors = AppTheme.colors
    var inConferma by remember { mutableStateOf(false) }

    LaunchedEffect(inConferma) {
        if (inConferma) {
            delay(4000)
            inConferma = false
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (!ultima) Modifier.drawBehind {
                    drawLine(
                        color = colors.border,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                } else Modifier
            )
            .padding(horizontal = 2.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            nome,
            color = colors.text,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (inConferma) {
                RowAction(AppIcons.Check, "Conferma rimozione", colors.danger, Color.White) {
                    inConferma = false
                    onRimuovi()
                }
            }
            RowAction(
                icona = if (inConferma) AppIcons.X else AppIcons.Trash,
                descrizione = if (inConferma) "Annulla rimozione" else "Rimuovi collegamento",
                sfondo = colors.surfaceAlt,
                tinta = if (inConferma) colors.text else colors.textSecondary
            ) { inConferma = !inConferma }
        }
    }
}

/** `.row-action`: cerchietto 30dp con bordo. */
@Composable
private fun RowAction(
    icona: com.example.registrazio.ui.theme.AppIconSpec,
    descrizione: String,
    sfondo: Color,
    tinta: Color,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(sfondo)
            .border(
                1.dp,
                if (sfondo == colors.danger) colors.danger else colors.borderStrong,
                CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(icona, 14.dp, tinta)
    }
}

/** `.dev-btn`: riga di testo cliccabile, senza cornice. */
@Composable
private fun DevButton(testo: String, colore: Color, onClick: () -> Unit) {
    Text(
        testo,
        color = colore,
        fontSize = 12.5.sp,
        lineHeight = 17.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 6.dp)
    )
}
