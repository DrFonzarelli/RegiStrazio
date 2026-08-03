package com.example.registrazio.ui.gate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Utente
import com.example.registrazio.ui.components.AppTextField
import com.example.registrazio.ui.components.Avatar
import com.example.registrazio.ui.components.GateActionButton
import com.example.registrazio.ui.components.appBorder
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.PALETTE_KEYS
import com.example.registrazio.ui.theme.Radius

/**
 * Il Gate del prototipo: due riquadri a fisarmonica, uno solo aperto per volta.
 *
 * Non c'è password da nessuna parte — chi rientra si riconosce dalla lista dei
 * profili. Per cinque persone che si conoscono è una scelta deliberata, non una
 * scorciatoia: vedi la nota nel README sulla mancanza di verifica di proprietà.
 */
@Composable
fun GateScreen(
    profili: List<Utente>,
    onCreaAccount: (nome: String, colore: String) -> String?,
    onEntraComeUtente: (Utente) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    var apertoCrea by remember { mutableStateOf(false) }
    var apertoRecupera by remember { mutableStateOf(false) }

    Column(modifier.padding(top = 8.dp)) {
        Text(
            "Ciao!",
            color = colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Nessuna password: scegli come iniziare.",
            color = colors.textSecondary,
            fontSize = 13.5.sp
        )
        Spacer(Modifier.height(18.dp))

        GateOption(
            titolo = "Crea nuovo account",
            espanso = apertoCrea,
            onToggle = {
                apertoCrea = !apertoCrea
                if (apertoCrea) apertoRecupera = false
            }
        ) {
            CreaAccountForm(onCreaAccount)
        }

        Spacer(Modifier.height(6.dp))

        GateOption(
            titolo = "Ho già un account",
            espanso = apertoRecupera,
            onToggle = {
                apertoRecupera = !apertoRecupera
                if (apertoRecupera) apertoCrea = false
            }
        ) {
            RecuperaAccountList(profili, onEntraComeUtente)
        }
    }
}

/** `.gate-option`: testata cliccabile con chevron che ruota di 90° all'apertura. */
@Composable
private fun GateOption(
    titolo: String,
    espanso: Boolean,
    onToggle: () -> Unit,
    contenuto: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    val rotazione by animateFloatAsState(
        targetValue = if (espanso) 90f else 0f,
        animationSpec = tween(220),
        label = "chevron"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.cardMd)
            .background(colors.surface)
            .appBorder(colors.border, Radius.md)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                titolo,
                color = colors.text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false)
            )
            AppIcon(
                AppIcons.ChevronRightSmall,
                15.dp,
                colors.textMuted,
                Modifier.rotate(rotazione)
            )
        }

        AnimatedVisibility(
            visible = espanso,
            enter = expandVertically(tween(260)),
            exit = shrinkVertically(tween(260))
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
                contenuto()
            }
        }
    }
}

@Composable
private fun CreaAccountForm(onCrea: (String, String) -> String?) {
    val colors = AppTheme.colors
    var nome by remember { mutableStateOf("") }
    var coloreScelto by remember { mutableStateOf<String?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }

    AppTextField(
        value = nome,
        onValueChange = { nome = it; errore = null },
        placeholder = "Il tuo nome",
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(12.dp))
    Text("Scegli un colore", color = colors.textMuted, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PALETTE_KEYS.forEach { chiave ->
            val selezionato = coloreScelto == chiave
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.paletteFor(chiave))
                    .border(
                        width = 2.dp,
                        color = if (selezionato) colors.text else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        coloreScelto = chiave
                        errore = null
                    }
            )
        }
    }

    errore?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = colors.danger, fontSize = 12.5.sp, lineHeight = 17.5.sp)
    }

    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        GateActionButton("Crea account →") {
            val colore = coloreScelto
            errore = when {
                nome.isBlank() -> "Scrivi un nome."
                colore == null -> "Scegli un colore."
                else -> onCrea(nome, colore)
            }
        }
    }
}

/**
 * Lista dei profili esistenti. Il primo tap seleziona (e mostra "Entra →"),
 * il secondo deseleziona: entrare nell'account di un altro per sbaglio
 * sarebbe fastidioso da disfare, quindi serve una conferma esplicita.
 */
@Composable
private fun RecuperaAccountList(
    profili: List<Utente>,
    onEntra: (Utente) -> Unit
) {
    val colors = AppTheme.colors
    var selezionato by remember { mutableStateOf<String?>(null) }

    if (profili.isEmpty()) {
        Text(
            "Nessun account ancora. Creane uno dal riquadro sopra.",
            color = colors.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(6.dp)
        )
        return
    }

    Column {
        profili.forEach { utente ->
            val attivo = selezionato == utente.appUid
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius.cardSm)
                    .background(if (attivo) colors.surfaceAlt else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { selezionato = if (attivo) null else utente.appUid }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Avatar(
                        lettera = utente.iniziale,
                        colore = colors.paletteFor(utente.colore),
                        size = 22.dp,
                        fontSize = 10.5.sp
                    )
                    Text(utente.nome, color = colors.text, fontSize = 14.5.sp)
                }

                if (attivo) {
                    GateActionButton("Entra →", { onEntra(utente) }, Modifier.padding(end = 6.dp))
                }
            }
        }
    }
}
