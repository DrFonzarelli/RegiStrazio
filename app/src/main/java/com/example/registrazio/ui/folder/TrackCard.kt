package com.example.registrazio.ui.folder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.model.VotoStella
import com.example.registrazio.ui.StatoScaricamento
import com.example.registrazio.ui.components.AppIconButton
import com.example.registrazio.ui.components.AppTextField
import com.example.registrazio.ui.components.Avatar
import com.example.registrazio.ui.components.FilledButton
import com.example.registrazio.ui.components.SecondaryButton
import com.example.registrazio.ui.components.appBorder
import com.example.registrazio.ui.theme.AppIcon
import com.example.registrazio.ui.theme.AppIcons
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.Radius
import com.example.registrazio.util.labelToSec
import com.example.registrazio.util.secToLabel

/** Le azioni che la card gira al ViewModel, raccolte per non avere 12 parametri. */
data class TrackCardActions(
    val onTogglePlay: () -> Unit,
    val onSposta: (Float) -> Unit,
    val onRiproduciDa: (Float) -> Unit,
    val onCambiaVoto: () -> Unit,
    val onCambiaDownload: () -> Unit,
    val onRinomina: (String) -> Unit,
    val onAggiungiCommento: (Float, String) -> Unit,
    val onModificaCommento: (String, Float, String) -> Unit,
    val onEliminaCommento: (String) -> Unit,
    val onApriDettagli: () -> Unit
)

/**
 * `.track-card`: la riga completa di una traccia.
 *
 * Preview commento, riquadro di scrittura e lista completa sono tre blocchi
 * mutuamente esclusivi — aprirne uno chiude gli altri, come nel prototipo:
 * tenerli aperti insieme renderebbe la card altissima e illeggibile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(
    traccia: Traccia,
    posizioneSecondi: Float,
    inRiproduzione: Boolean,
    audioAttivo: Boolean,
    /** Avanzamento e stato del download, `null` quando non c'è un download in corso. */
    scaricamento: StatoScaricamento?,
    mioAppUid: String,
    /**
     * Contatore che cambia quando la barra in ascolto chiede di aprire il
     * riquadro del commento su questa traccia. `null` = nessuna richiesta.
     */
    apriCommento: Int? = null,
    azioni: TrackCardActions,
    onChiediEliminazione: (Commento) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors

    var indiceSelezionato by remember { mutableStateOf<Int?>(null) }
    var previewEspansa by remember { mutableStateOf(false) }
    var accordionAperto by remember { mutableStateOf(false) }
    var addBoxAperta by remember { mutableStateOf(false) }
    var idInModifica by remember { mutableStateOf<String?>(null) }
    var tempoBloccato by remember { mutableStateOf(true) }
    var testoNuovo by remember { mutableStateOf("") }
    var tempoInput by remember { mutableStateOf("0:00") }
    var inRinomina by remember { mutableStateOf(false) }
    var bozzaTitolo by remember(traccia.titolo) { mutableStateOf(traccia.titolo) }

    val commenti = traccia.commenti
    val previewAperta = indiceSelezionato != null && !addBoxAperta

    // Se un commento sparisce, l'indice selezionato non deve restare appeso nel vuoto.
    LaunchedEffect(commenti.size) {
        indiceSelezionato?.let { if (it >= commenti.size) indiceSelezionato = null }
    }

    fun apriAddBox(commento: Commento?) {
        idInModifica = commento?.id
        testoNuovo = commento?.testo.orEmpty()
        tempoInput = secToLabel(commento?.timestampSecondi ?: posizioneSecondi)
        tempoBloccato = true
        addBoxAperta = true
    }

    fun chiudiAddBox() {
        addBoxAperta = false
        idInModifica = null
        tempoBloccato = true
    }

    // Il tasto commento della barra in ascolto arriva qui: apre esattamente il
    // riquadro che si aprirebbe premendo ✎ sulla card, senza una seconda UI.
    //
    // `rememberSaveable` e non `remember`: uscendo e rientrando nella lista la
    // card viene distrutta e ricreata, e con essa il `LaunchedEffect`. Senza
    // memoria di quale richiesta è già stata servita, il riquadro si riaprirebbe
    // da solo ogni volta che si torna a scorrere lì sopra.
    var richiestaServita by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(apriCommento) {
        if (apriCommento != null && apriCommento != richiestaServita) {
            richiestaServita = apriCommento
            indiceSelezionato = null
            accordionAperto = false
            apriAddBox(null)
        }
    }

    // Minutaggio sbloccato: segue il cursore in tempo reale, come `setPct` nel
    // prototipo (`if(!timeLocked) timeInput.value = ...`). Bloccato resta fermo
    // sul punto in cui l'hai fissato — è tutto il senso del lucchetto.
    //
    // La chiave è l'etichetta e non i secondi grezzi: il cursore si aggiorna
    // quattro volte al secondo, ma il minutaggio cambia una volta al secondo, e
    // non vale la pena rilanciare un effetto per scrivere lo stesso testo.
    val etichettaCursore = secToLabel(posizioneSecondi)
    LaunchedEffect(etichettaCursore, tempoBloccato, addBoxAperta) {
        if (addBoxAperta && !tempoBloccato) tempoInput = etichettaCursore
    }

    fun selezionaMarker(indice: Int) {
        if (indiceSelezionato == indice) {
            indiceSelezionato = null
            previewEspansa = false
        } else {
            indiceSelezionato = indice
            addBoxAperta = false
        }
    }

    /** Tap sul minutaggio di un commento: salta lì e riparte sempre da quel punto. */
    fun attivaChip(indice: Int) {
        indiceSelezionato = indice
        addBoxAperta = false
        azioni.onRiproduciDa(commenti[indice].timestampSecondi)
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(Radius.cardLg)
            .background(colors.surface)
            // Solo la riproduzione colora il bordo. Un download in corso non è
            // "la traccia di cui ti stai occupando": puoi scaricarne una mentre
            // ne ascolti un'altra, e due card accese confonderebbero. La
            // percentuale accanto all'icona basta come segnale.
            .appBorder(if (inRiproduzione) colors.accent else colors.border, Radius.lg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // ---------- testata ----------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            PlayButton(inRiproduzione, audioAttivo, azioni.onTogglePlay)

            Column(Modifier.weight(1f)) {
                if (inRinomina) {
                    RenameRow(
                        valore = bozzaTitolo,
                        onValore = { bozzaTitolo = it },
                        onConferma = { inRinomina = false; azioni.onRinomina(bozzaTitolo) }
                    )
                } else {
                    Text(
                        traccia.titolo,
                        color = colors.text,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onDoubleClick = { bozzaTitolo = traccia.titolo; inRinomina = true },
                            onClick = {}
                        )
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        // Durata 0 = ancora sconosciuta: la sapremo quando il
                        // player aprirà il file. Meglio ammetterlo che stampare 0:00.
                        if (traccia.durataSecondi > 0) "${secToLabel(traccia.durataSecondi)} min"
                        else "--:-- min",
                        color = colors.textMuted,
                        fontSize = 11.5.sp
                    )
                    FavButton(traccia, azioni.onCambiaVoto)
                }
            }

            CommentiChip(
                numero = commenti.size,
                aperto = accordionAperto,
                onClick = {
                    accordionAperto = !accordionAperto
                    if (accordionAperto) addBoxAperta = false
                }
            )

            AddButton(
                aperto = addBoxAperta,
                onClick = { if (addBoxAperta) chiudiAddBox() else apriAddBox(null) }
            )

            TrackMenu(
                traccia = traccia,
                scaricamento = scaricamento,
                onCambiaDownload = azioni.onCambiaDownload,
                onRinomina = { bozzaTitolo = traccia.titolo; inRinomina = true },
                onDettagli = azioni.onApriDettagli
            )
        }

        Spacer(Modifier.height(7.dp))

        // ---------- timeline ----------
        Timeline(
            durataSecondi = traccia.durataSecondi,
            posizioneSecondi = posizioneSecondi,
            commenti = commenti,
            indiceSelezionato = indiceSelezionato,
            inRiproduzione = audioAttivo,
            onMarkerCliccato = ::selezionaMarker,
            onSposta = azioni.onSposta
        )

        Spacer(Modifier.height(2.dp))

        // ---------- riga tempo ----------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(secToLabel(posizioneSecondi), color = colors.textMuted, fontSize = 11.sp)
            when {
                // Mentre scarica il numero dice a che punto siamo; a fine corsa
                // resta la sola icona, come `.dl-indicator` nel prototipo.
                // Toccarla mette in pausa e la ripresa riparte da lì: è il posto
                // dove viene da cercarlo, molto prima che nel menu.
                //
                // Niente padding verticale: farebbe questa riga più alta di
                // quando il download non c'è, e la card salterebbe su e giù ogni
                // volta che compare la percentuale.
                scaricamento != null -> {
                    val tinta = if (scaricamento.inPausa) colors.textMuted else colors.accent
                    Row(
                        Modifier
                            .clip(Radius.pillShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = azioni.onCambiaDownload
                            )
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("${(scaricamento.frazione * 100).toInt()}%", color = tinta, fontSize = 11.sp)
                        // Pausa mentre scarica, play quando è fermo: l'icona dice
                        // cosa succede se la tocchi, non in che stato sei.
                        AppIcon(
                            if (scaricamento.inPausa) AppIcons.Play else AppIcons.Pause,
                            10.dp,
                            tinta
                        )
                    }
                }
                traccia.scaricata -> AppIcon(AppIcons.CloudDone, 13.dp, colors.accent)
            }
        }

        Spacer(Modifier.height(6.dp))

        // ---------- preview del commento selezionato ----------
        AnimatedVisibility(
            visible = previewAperta,
            enter = expandVertically(tween(200)),
            exit = shrinkVertically(tween(160))
        ) {
            indiceSelezionato?.let { indice ->
                if (indice < commenti.size) {
                    PreviewBox(
                        commento = commenti[indice],
                        espansa = previewEspansa,
                        onPrecedente = {
                            indiceSelezionato = (indice - 1 + commenti.size) % commenti.size
                        },
                        onSuccessivo = { indiceSelezionato = (indice + 1) % commenti.size },
                        onChip = { attivaChip(indice) },
                        onTesto = { previewEspansa = !previewEspansa }
                    )
                }
            }
        }

        // ---------- riquadro di scrittura ----------
        AnimatedVisibility(
            visible = addBoxAperta,
            enter = expandVertically(tween(200)),
            exit = shrinkVertically(tween(160))
        ) {
            AddBox(
                testo = testoNuovo,
                onTesto = { testoNuovo = it },
                tempo = tempoInput,
                onTempo = { tempoInput = it },
                bloccato = tempoBloccato,
                onCambiaBlocco = {
                    tempoBloccato = !tempoBloccato
                    if (!tempoBloccato) tempoInput = secToLabel(posizioneSecondi)
                },
                inModifica = idInModifica != null,
                onAnnulla = ::chiudiAddBox,
                onSalva = {
                    if (testoNuovo.isNotBlank()) {
                        val secondi = labelToSec(tempoInput)
                            .coerceIn(0, traccia.durataSecondi)
                            .toFloat()
                        val idCorrente = idInModifica
                        if (idCorrente != null) {
                            azioni.onModificaCommento(idCorrente, secondi, testoNuovo)
                        } else {
                            azioni.onAggiungiCommento(secondi, testoNuovo)
                        }
                        chiudiAddBox()
                    }
                }
            )
        }

        // ---------- lista completa ----------
        AnimatedVisibility(
            visible = accordionAperto,
            enter = expandVertically(tween(200)),
            exit = shrinkVertically(tween(160))
        ) {
            CommentiList(
                commenti = commenti,
                indiceSelezionato = indiceSelezionato,
                mioAppUid = mioAppUid,
                onChip = ::attivaChip,
                onTesto = { indice ->
                    if (indiceSelezionato == indice) {
                        if (!previewEspansa) previewEspansa = true
                        else {
                            previewEspansa = false
                            indiceSelezionato = null
                        }
                    } else {
                        indiceSelezionato = indice
                        addBoxAperta = false
                    }
                },
                onModifica = { apriAddBox(it) },
                onElimina = onChiediEliminazione
            )
        }
    }
}

/**
 * `.play-btn`: cerchio 38dp che diventa pieno accent mentre suona.
 *
 * Tre stati, non due. Fra il tocco e il primo suono c'è il tempo di chiedere
 * l'indirizzo a MEGA e riempire il buffer — può essere un secondo abbondante —
 * e mostrare subito la pausa colorata prometteva un audio che ancora non
 * c'era: restava solo da fissare un tasto che diceva "sto suonando" nel
 * silenzio. Il cerchio ora resta scarico e gira finché il suono non parte
 * davvero, e solo allora si riempie.
 *
 * @param inRiproduzione play premuto — l'intenzione.
 * @param audioAttivo il suono sta uscendo davvero. Fra i due c'è il caricamento.
 */
@Composable
private fun PlayButton(
    inRiproduzione: Boolean,
    audioAttivo: Boolean,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    val inAttesa = inRiproduzione && !audioAttivo
    val pieno = inRiproduzione && audioAttivo

    val rotazione = if (inAttesa) {
        val giro = rememberInfiniteTransition(label = "attesa")
        giro.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "giro"
        ).value
    } else {
        0f
    }

    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (pieno) colors.accent else colors.surface)
            .border(
                1.dp,
                if (inRiproduzione) colors.accent else colors.borderStrong,
                CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            inAttesa -> AppIcon(
                AppIcons.Refresh,
                15.dp,
                colors.accent,
                Modifier.rotate(rotazione)
            )

            pieno -> AppIcon(AppIcons.Pause, 16.dp, Color.White)
            else -> AppIcon(AppIcons.Play, 16.dp, colors.text)
        }
    }
}

/**
 * `.fav-btn`: stella a tre stati. La mezza si ottiene sovrapponendo la stella
 * piena tagliata a metà su quella a contorno, come il `clip-path` del prototipo.
 */
@Composable
private fun FavButton(traccia: Traccia, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val attiva = traccia.mioVoto != VotoStella.NESSUNO
    val tinta = if (attiva) colors.star else colors.textMuted

    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // `buildStarVisual` del prototipo: la stella piena sovrapposta a quella a
        // contorno e tagliata con `clip-path: inset(0 50% 0 0)`.
        //
        // Prima il taglio era un `Box(Modifier.width(6.dp))` con dentro l'icona
        // da 12dp: ma un vincolo di larghezza in Compose **rimpicciolisce** il
        // figlio invece di nasconderne metà, e usciva una stellina storta. Qui si
        // ritaglia in fase di disegno, che è quello che fa `clip-path`.
        Box(Modifier.size(12.dp)) {
            AppIcon(AppIcons.Star, 12.dp, tinta)
            if (traccia.mioVoto != VotoStella.NESSUNO) {
                val mezza = traccia.mioVoto == VotoStella.MEZZA
                Box(
                    Modifier
                        .size(12.dp)
                        .drawWithContent {
                            clipRect(right = if (mezza) size.width / 2f else size.width) {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    AppIcon(AppIcons.StarFilled, 12.dp, tinta)
                }
            }
        }
        val punteggio = traccia.punteggio
        if (punteggio > 0f) {
            Text(
                if (punteggio % 1f == 0f) punteggio.toInt().toString()
                else String.format("%.1f", punteggio),
                color = tinta,
                fontSize = 11.sp,
                lineHeight = 11.sp
            )
        }
    }
}

/** `.chip-btn`: pillola col contatore dei commenti e il chevron. */
@Composable
private fun CommentiChip(numero: Int, aperto: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .height(38.dp)
            .clip(Radius.pillShape)
            .background(if (aperto) colors.accentSoft else colors.surface)
            .border(
                1.dp,
                if (aperto) colors.accent else colors.borderStrong,
                Radius.pillShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val tinta = if (aperto) colors.accent else colors.textSecondary
        AppIcon(if (aperto) AppIcons.ChevronUp else AppIcons.ChevronDown, 13.dp, tinta)
        Text("$numero", color = tinta, fontSize = 12.sp)
    }
}

/** `.add-btn`: cerchio che passa da fumetto a "meno" quando il riquadro è aperto. */
@Composable
private fun AddButton(aperto: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (aperto) colors.accentSoft else colors.surface)
            .border(1.dp, if (aperto) colors.accent else colors.borderStrong, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(
            if (aperto) AppIcons.Minus else AppIcons.Comment,
            if (aperto) 12.dp else 18.dp,
            if (aperto) colors.accent else colors.textSecondary
        )
    }
}

/** Campo di rinomina inline + spunta di conferma (`.title-edit-input`). */
@Composable
private fun RenameRow(
    valore: String,
    onValore: (String) -> Unit,
    onConferma: () -> Unit
) {
    val colors = AppTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AppTextField(
            value = valore,
            onValueChange = onValore,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            radius = 6.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConferma() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester)
        )
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(colors.surfaceAlt)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onConferma
                ),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(AppIcons.Check, 13.dp, colors.textSecondary)
        }
    }
}

/** `.preview-box`: il commento selezionato, con frecce per scorrere gli altri. */
@Composable
private fun PreviewBox(
    commento: Commento,
    espansa: Boolean,
    onPrecedente: () -> Unit,
    onSuccessivo: () -> Unit,
    onChip: () -> Unit,
    onTesto: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .clip(Radius.cardSm)
            .background(colors.surfaceAlt)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        AppIconButton(
            AppIcons.ChevronLeft, "Commento precedente", onPrecedente,
            size = 32.dp, iconSize = 18.dp, tint = colors.textMuted
        )

        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Row(
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onChip
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(colors.avatarFor(commento.iniziale))
                )
                Text(
                    "${commento.autoreNome} · ",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                TimeChip(commento.timestampSecondi)
            }

            Spacer(Modifier.height(3.dp))

            val scroll = rememberScrollState()
            Text(
                commento.testo,
                color = colors.text,
                fontSize = 13.sp,
                lineHeight = 18.85.sp,
                maxLines = if (espansa) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (espansa) Modifier.heightIn(max = 95.dp).verticalScroll(scroll) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTesto
                    )
            )
        }

        AppIconButton(
            AppIcons.ChevronRight, "Commento successivo", onSuccessivo,
            size = 32.dp, iconSize = 18.dp, tint = colors.textMuted
        )
    }
}

/** Minutaggio sottolineato seguito dal triangolino di play. */
@Composable
private fun TimeChip(secondi: Float, colore: Color = AppTheme.colors.accent) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            secToLabel(secondi),
            color = colore,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline
        )
        AppIcon(AppIcons.Play, 9.dp, colore)
    }
}

/** `.add-box`: testo, minutaggio (bloccabile) e i due tasti. */
@Composable
private fun AddBox(
    testo: String,
    onTesto: (String) -> Unit,
    tempo: String,
    onTempo: (String) -> Unit,
    bloccato: Boolean,
    onCambiaBlocco: () -> Unit,
    inModifica: Boolean,
    onAnnulla: () -> Unit,
    onSalva: () -> Unit
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .clip(Radius.cardSm)
            .background(colors.surfaceAlt)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        AppTextField(
            value = testo,
            onValueChange = onTesto,
            placeholder = "Scrivi un commento...",
            singleLine = false,
            minHeight = 56.dp,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                AppTextField(
                    value = tempo,
                    onValueChange = onTempo,
                    readOnly = bloccato,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    background = if (bloccato) colors.surfaceAlt else colors.surface,
                    modifier = Modifier.width(58.dp)
                )
                // Il minutaggio nasce bloccato sul punto in cui hai premuto:
                // di solito è quello giusto, e sbloccarlo è un gesto in più
                // solo per chi vuole davvero spostarlo.
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (bloccato) colors.surface else colors.accentSoft)
                        .border(
                            1.dp,
                            if (bloccato) colors.borderStrong else colors.accent,
                            CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCambiaBlocco
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        if (bloccato) AppIcons.Lock else AppIcons.Unlock,
                        13.dp,
                        if (bloccato) colors.textMuted else colors.accent
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Annulla", onAnnulla, fontSize = 14.sp, paddingVertical = 8.dp)
                FilledButton(
                    if (inModifica) "Modifica" else "Salva",
                    colors.accent,
                    onSalva,
                    fontSize = 14.sp,
                    paddingVertical = 8.dp
                )
            }
        }
    }
}

/** `.all-comments`: la lista completa sotto la card. */
@Composable
private fun CommentiList(
    commenti: List<Commento>,
    indiceSelezionato: Int?,
    mioAppUid: String,
    onChip: (Int) -> Unit,
    onTesto: (Int) -> Unit,
    onModifica: (Commento) -> Unit,
    onElimina: (Commento) -> Unit
) {
    val colors = AppTheme.colors

    if (commenti.isEmpty()) {
        Text(
            "Nessun commento su questa traccia — sii il primo a lasciarne uno.",
            color = colors.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 5.dp)
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        commenti.forEachIndexed { indice, commento ->
            val selezionato = indice == indiceSelezionato
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius.cardSm)
                    .background(if (selezionato) colors.accentSoft else colors.surfaceAlt)
                    .then(
                        if (selezionato) Modifier.appBorder(colors.accent, Radius.sm)
                        else Modifier
                    )
                    .padding(start = 7.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onChip(indice) }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Avatar(
                        lettera = commento.iniziale,
                        colore = colors.avatarFor(commento.iniziale),
                        size = 21.dp,
                        fontSize = 9.5.sp
                    )
                    TimeChip(
                        commento.timestampSecondi,
                        if (selezionato) colors.accent else colors.textMuted
                    )
                }

                Text(
                    commento.testo,
                    color = colors.text,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTesto(indice) }
                )

                if (commento.appUid == mioAppUid) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        AppIconButton(
                            AppIcons.Edit, "Modifica il tuo commento",
                            { onModifica(commento) },
                            size = 30.dp, iconSize = 14.dp, tint = colors.textMuted
                        )
                        AppIconButton(
                            AppIcons.Trash, "Elimina il tuo commento",
                            { onElimina(commento) },
                            size = 30.dp, iconSize = 16.dp, tint = colors.danger
                        )
                    }
                }
            }
        }
    }
}
