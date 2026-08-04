package com.example.registrazio.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.ui.StatoCollegamento
import com.example.registrazio.ui.components.AppIconButton
import com.example.registrazio.ui.components.AppTextField
import com.example.registrazio.ui.components.appBorder
import com.example.registrazio.ui.components.pressScale
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.Radius
import com.example.registrazio.util.apriMega

/**
 * Elenco delle cartelle di prove, più la ghost card per collegarne una nuova.
 */
@Composable
fun HomeScreen(
    cartelle: List<Cartella>,
    conteggioTracce: (String) -> Int,
    cartelleRinominabili: Set<String>,
    collegamento: StatoCollegamento,
    onApriCartella: (String) -> Unit,
    onRinomina: (String, String) -> Unit,
    onCollegaLink: (String) -> Unit,
    onPulisciErrore: () -> Unit,
    onNonApreMega: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues
) {
    // La cartella appena collegata si apre già in modalità rinomina: il nome
    // proposto ("Cartella A1b2C3") non dice nulla, tanto vale chiederlo subito.
    var daRinominare by remember { mutableStateOf<String?>(null) }

    // La cartella nuova è l'ultima arrivata in elenco. Prima l'esito tornava
    // come valore dalla funzione; ora che c'è la rete di mezzo arriva di qui.
    LaunchedEffect(collegamento.completati) {
        if (collegamento.completati > 0 && collegamento.chiediNome) {
            daRinominare = cartelle.lastOrNull()?.id
        }
    }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding) {
        items(cartelle, key = { it.id }) { cartella ->
            FolderCard(
                nome = cartella.nome,
                numTracce = conteggioTracce(cartella.id),
                rinominabile = cartella.id in cartelleRinominabili,
                rinominaSubito = daRinominare == cartella.id,
                inAggiornamento = collegamento.cartellaInAggiornamento == cartella.id,
                onClick = { onApriCartella(cartella.id) },
                onRinomina = { nuovo ->
                    onRinomina(cartella.id, nuovo)
                    daRinominare = null
                }
            )
            Spacer(Modifier.height(6.dp))
        }

        item {
            GhostCard(
                collegamento = collegamento,
                onCollega = onCollegaLink,
                onPulisciErrore = onPulisciErrore,
                onNonApreMega = onNonApreMega
            )
        }
    }
}

/**
 * `.folder-card`. Doppio tap sul nome per rinominare — stessa scorciatoia
 * che il prototipo usa sui titoli delle tracce.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    nome: String,
    numTracce: Int,
    rinominabile: Boolean,
    rinominaSubito: Boolean,
    inAggiornamento: Boolean,
    onClick: () -> Unit,
    onRinomina: (String) -> Unit
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var inRinomina by remember { mutableStateOf(false) }

    // Onda che scorre da sinistra a destra mentre la cartella si ricarica.
    //
    // Scorre e ricomincia invece di riempirsi una volta sola: MEGA risponde in
    // un colpo solo, quindi una percentuale vera non esiste. Una barra che si
    // riempie a tempo direbbe un numero inventato; questa dice solo "sto
    // lavorando", che è tutto quello che sappiamo davvero.
    val onda = rememberInfiniteTransition(label = "aggiornamento")
    val avanzamento by onda.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "avanzamento"
    )
    var bozza by remember(nome) { mutableStateOf(nome) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(rinominaSubito) {
        if (rinominaSubito) {
            bozza = nome
            inRinomina = true
        }
    }
    LaunchedEffect(inRinomina) {
        if (inRinomina) runCatching { focusRequester.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(Radius.cardMd)
            .background(colors.surface)
            // Dietro il contenuto, non sopra: niente si sposta e niente si
            // copre, la card resta leggibile mentre si aggiorna.
            .drawBehind {
                if (inAggiornamento) {
                    drawRect(
                        color = colors.accentSoft,
                        size = androidx.compose.ui.geometry.Size(size.width * avanzamento, size.height)
                    )
                }
            }
            .appBorder(colors.border, Radius.md)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !inRinomina,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(AppIcons.Folder, 19.dp, colors.accent)
        }

        Column(Modifier.weight(1f)) {
            if (inRinomina) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = bozza,
                        onValueChange = { bozza = it },
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        radius = 6.dp,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester)
                    )
                    Spacer(Modifier.size(4.dp))
                    ConfirmChip { inRinomina = false; onRinomina(bozza) }
                }
            } else {
                Text(
                    nome,
                    color = colors.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (rinominabile) {
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onDoubleClick = { bozza = nome; inRinomina = true },
                            onClick = onClick
                        )
                    } else Modifier
                )
            }
            Spacer(Modifier.height(1.dp))
            Text(
                "$numTracce ${if (numTracce == 1) "traccia" else "tracce"}",
                color = colors.textMuted,
                fontSize = 12.sp
            )
        }

        AppIcon(AppIcons.ChevronRightSmall, 15.dp, colors.textMuted)
    }
}

/** `.rename-confirm`: pallino con la spunta accanto al campo di rinomina. */
@Composable
private fun ConfirmChip(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(22.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(colors.surfaceAlt)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(AppIcons.Check, 13.dp, colors.textSecondary)
    }
}

/**
 * `.ghost-card`: bordo tratteggiato, si apre a fisarmonica sul campo del link.
 */
@Composable
private fun GhostCard(
    collegamento: StatoCollegamento,
    onCollega: (String) -> Unit,
    onPulisciErrore: () -> Unit,
    onNonApreMega: () -> Unit
) {
    val colors = AppTheme.colors
    val contesto = androidx.compose.ui.platform.LocalContext.current
    var aperta by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }

    fun chiudi() {
        aperta = false
        link = ""
        onPulisciErrore()
    }

    // Un link condiviso da un'altra app: la card si apre e si riempie, ma il
    // collegamento resta un gesto dell'utente. Si osserva la sequenza e non il
    // testo, così condividere due volte lo stesso link funziona due volte.
    LaunchedEffect(collegamento.seqPrecompilato) {
        collegamento.linkPrecompilato?.let {
            link = it
            aperta = true
        }
    }

    // Il collegamento è andato a buon fine: la card si richiude da sola.
    LaunchedEffect(collegamento.completati) {
        if (collegamento.completati > 0) {
            aperta = false
            link = ""
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.cardMd)
            .dashedBorder(colors.border, Radius.md)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (aperta) chiudi() else aperta = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceAlt),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(AppIcons.Link, 11.dp, colors.textMuted)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Collega un link Mega",
                    color = colors.textSecondary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(1.dp))
                Text("Aggiungi una cartella al gruppo", color = colors.textMuted, fontSize = 12.sp)
            }
            AppIcon(
                if (aperta) AppIcons.ChevronUp else AppIcons.ChevronDown,
                13.dp,
                colors.textMuted
            )
        }

        AnimatedVisibility(
            visible = aperta,
            enter = expandVertically(tween(200)),
            exit = shrinkVertically(tween(160))
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTextField(
                        value = link,
                        onValueChange = { link = it; onPulisciErrore() },
                        placeholder = "Incolla il link Mega…",
                        modifier = Modifier.weight(1f),
                        // Svuotare è un'azione sul campo, quindi sta dentro al
                        // campo — dove la cerca chiunque abbia mai usato una
                        // barra di ricerca — e compare solo se c'è qualcosa da
                        // svuotare.
                        trailing = if (link.isEmpty()) null else {
                            {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(colors.surfaceAlt)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { link = ""; onPulisciErrore() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AppIcon(AppIcons.X, 10.dp, colors.textSecondary)
                                }
                            }
                        }
                    )
                    if (collegamento.inCorso) {
                        // Leggere la cartella su MEGA richiede una chiamata di rete:
                        // senza un segnale d'attesa sembrerebbe che il tasto non funzioni.
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = colors.accent,
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        // Posizione fissa: se cambiasse mestiere a seconda del
                        // campo, prima o poi si finirebbe fuori dall'app volendo
                        // cancellare, o viceversa.
                        GhostIconButton(
                            AppIcons.Cloud, "Apri MEGA", colors.surfaceAlt, colors.textSecondary
                        ) {
                            if (!apriMega(contesto)) onNonApreMega()
                        }
                        GhostIconButton(
                            AppIcons.Check, "Collega", colors.accent, Color.White
                        ) { onCollega(link) }
                    }
                }
                if (collegamento.inCorso) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Leggo la cartella su MEGA…",
                        color = colors.textMuted,
                        fontSize = 12.5.sp
                    )
                }
                collegamento.errore?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = colors.danger, fontSize = 12.5.sp, lineHeight = 17.5.sp)
                }
            }
        }
    }
}

/** `.ghost-icon-btn`: cerchio 32dp accanto al campo del link. */
@Composable
private fun GhostIconButton(
    icona: com.example.registrazio.ui.theme.AppIconSpec,
    descrizione: String,
    sfondo: Color,
    tinta: Color,
    onClick: () -> Unit
) {
    AppIconButton(
        icon = icona,
        contentDescription = descrizione,
        onClick = onClick,
        size = 32.dp,
        iconSize = 14.dp,
        tint = tinta,
        background = sfondo
    )
}

/** `border-style: dashed` — Compose non ce l'ha, va disegnato. */
private fun Modifier.dashedBorder(colore: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = colore,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
    )
}
