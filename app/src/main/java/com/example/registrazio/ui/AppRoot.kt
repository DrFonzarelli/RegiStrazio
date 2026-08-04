package com.example.registrazio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.registrazio.data.DemoData
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.ui.account.AccountSheet
import com.example.registrazio.ui.components.AppToast
import com.example.registrazio.ui.components.AppTopBar
import com.example.registrazio.ui.components.Avatar
import com.example.registrazio.ui.components.ConfermaSheet
import com.example.registrazio.ui.folder.FolderScreen
import com.example.registrazio.ui.folder.TrackCardActions
import com.example.registrazio.ui.folder.TrackDetailsSheet
import com.example.registrazio.ui.gate.GateScreen
import com.example.registrazio.ui.home.HomeScreen
import com.example.registrazio.ui.player.MiniPlayer
import com.example.registrazio.ui.theme.AppMaxWidth
import com.example.registrazio.ui.theme.AppTheme
import com.example.registrazio.ui.theme.MainPadding
import com.example.registrazio.ui.theme.RegiStrazioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Le cartelle demo non si rinominano né si scollegano: non sono state collegate da nessuno. */
private val ID_DEMO = DemoData.cartelle.map { it.id }.toSet()

@Composable
fun AppRoot(
    testoCondiviso: TestoCondiviso? = null,
    vm: AppViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    // Sulla sequenza e non sul testo: due condivisioni identiche di fila devono
    // arrivare entrambe.
    LaunchedEffect(testoCondiviso?.seq) {
        testoCondiviso?.let { vm.riceviCondivisione(it.testo) }
    }

    RegiStrazioTheme(darkTheme = state.temaScuro) {
        val colors = AppTheme.colors
        val scope = rememberCoroutineScope()
        val listaTracce = rememberLazyListState()

        val avatarInteraction = remember { MutableInteractionSource() }

        var accountAperto by remember { mutableStateOf(false) }
        var daEliminare by remember { mutableStateOf<Pair<Traccia, Commento>?>(null) }
        var confermaBulk by remember { mutableStateOf<String?>(null) }
        var dettagli by remember { mutableStateOf<Traccia?>(null) }
        var toastVisibile by remember { mutableStateOf(false) }

        // Il collegamento con la barra si spezza quando rivedi la card in pausa:
        // a quel punto ti sei già "ricongiunto" con la traccia guardandola.
        var barraCollegata by remember { mutableStateOf(false) }

        val schermata = state.schermata
        val cartellaCorrente = (schermata as? Schermata.DettaglioCartella)
            ?.let { s -> state.cartelle.find { it.id == s.cartellaId } }

        val tracciaAttiva = state.riproduzione.tracciaId?.let { id -> state.tracce.find { it.id == id } }

        LaunchedEffect(state.riproduzione.tracciaId, state.riproduzione.inRiproduzione) {
            if (state.riproduzione.inRiproduzione) barraCollegata = true
        }

        // La card della traccia in ascolto è a schermo in questo momento?
        val cardVisibile by remember {
            derivedStateOf {
                val id = state.riproduzione.tracciaId ?: return@derivedStateOf false
                val cartella = (state.schermata as? Schermata.DettaglioCartella) ?: return@derivedStateOf false
                val tracce = state.tracce
                    .filter { it.cartellaId == cartella.cartellaId }
                    .ordinate(state.ordinamento)
                val indice = tracce.indexOfFirst { it.id == id }
                if (indice < 0) return@derivedStateOf false
                // +1: il primo elemento della lista è la sort bar
                listaTracce.layoutInfo.visibleItemsInfo.any { it.index == indice + 1 }
            }
        }

        LaunchedEffect(cardVisibile, state.riproduzione.inRiproduzione) {
            if (cardVisibile && !state.riproduzione.inRiproduzione) barraCollegata = false
        }

        val mostraBarra = barraCollegata && tracciaAttiva != null && !cardVisibile

        // Il tasto indietro di sistema deve fare quello che fa la freccia in topbar.
        BackHandler(enabled = schermata is Schermata.DettaglioCartella) { vm.tornaHome() }

        LaunchedEffect(state.messaggio?.seq) {
            if (state.messaggio != null) {
                toastVisibile = true
                delay(1800)
                toastVisibile = false
                vm.messaggioMostrato()
            }
        }

        Box(Modifier.fillMaxSize().background(colors.bg)) {
            Column(Modifier.fillMaxSize()) {
                if (schermata != Schermata.Gate) {
                    AppTopBar(
                        titolo = cartellaCorrente?.nome ?: "Le tue prove",
                        mostraIndietro = schermata is Schermata.DettaglioCartella,
                        avatar = state.identita?.let { io ->
                            {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = avatarInteraction,
                                            indication = null
                                        ) { accountAperto = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Avatar(
                                        lettera = io.iniziale,
                                        colore = colors.paletteFor(io.colore),
                                        size = 26.dp,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        },
                        temaScuro = state.temaScuro,
                        onIndietro = vm::tornaHome,
                        onCambiaTema = vm::cambiaTema,
                        onAggiorna = vm::aggiorna
                    )
                }

                val padding = PaddingValues(
                    start = MainPadding.horizontal,
                    end = MainPadding.horizontal,
                    top = MainPadding.top,
                    bottom = MainPadding.bottomForMiniPlayer +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val colonna = Modifier.fillMaxWidth().widthIn(max = AppMaxWidth)

                    when (schermata) {
                        is Schermata.Gate -> GateScreen(
                            profili = state.profiliDisponibili,
                            onCreaAccount = vm::creaAccount,
                            onEntraComeUtente = vm::entraComeUtente,
                            modifier = colonna.padding(padding)
                        )

                        is Schermata.Home -> HomeScreen(
                            cartelle = state.cartelle,
                            conteggioTracce = { id -> state.tracce.count { it.cartellaId == id } },
                            cartelleRinominabili = state.cartelle
                                .map { it.id }.filterNot { it in ID_DEMO }.toSet(),
                            collegamento = state.collegamento,
                            onApriCartella = vm::apriCartella,
                            onRinomina = vm::rinominaCartella,
                            onCollegaLink = vm::collegaCartella,
                            onPulisciErrore = vm::pulisciErroreCollegamento,
                            onNonApreMega = vm::megaNonApribile,
                            modifier = colonna,
                            contentPadding = padding
                        )

                        is Schermata.DettaglioCartella -> {
                            val tracce = state.tracce
                                .filter { it.cartellaId == schermata.cartellaId }
                                .ordinate(state.ordinamento)
                            val scaricate = tracce.count { it.scaricata }

                            FolderScreen(
                                tracce = tracce,
                                ordinamento = state.ordinamento,
                                scaricateSuTotali = scaricate to tracce.size,
                                bulkInCorso = state.bulkDownload?.cartellaId == schermata.cartellaId,
                                bulkInPausa = state.bulkInPausa == schermata.cartellaId,
                                tracciaInRiproduzione = state.riproduzione.tracciaId,
                                audioAttivo = state.riproduzione.audioAttivo,
                                scaricamenti = state.scaricamenti,
                                posizioneSecondi = state.riproduzione.posizioneSecondi,
                                mioAppUid = state.identita?.appUid.orEmpty(),
                                onCambiaOrdinamento = vm::cambiaOrdinamento,
                                onScaricaTutte = {
                                    // La conferma serve solo per far partire un
                                    // download che non c'è: mettere in pausa o
                                    // riprendere non consuma niente di nuovo, e
                                    // farsi chiedere "sei sicuro?" per fermarsi
                                    // sarebbe solo un tap in mezzo ai piedi.
                                    val giaAvviato =
                                        state.bulkDownload?.cartellaId == schermata.cartellaId ||
                                            state.bulkInPausa == schermata.cartellaId
                                    if (giaAvviato) vm.scaricaTutte(schermata.cartellaId)
                                    else confermaBulk = schermata.cartellaId
                                },
                                azioniPerTraccia = { traccia ->
                                    azioniPer(traccia, vm) { dettagli = traccia }
                                },
                                onChiediEliminazione = { t, c -> daEliminare = t to c },
                                contentPadding = padding,
                                modifier = colonna
                            )
                        }
                    }
                }
            }

            // ---------- barra in ascolto ----------
            AnimatedVisibility(
                visible = mostraBarra,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                tracciaAttiva?.let { traccia ->
                    MiniPlayer(
                        titolo = traccia.titolo,
                        posizioneSecondi = state.riproduzione.posizioneSecondi,
                        inRiproduzione = state.riproduzione.inRiproduzione,
                        audioAttivo = state.riproduzione.audioAttivo,
                        onVaiAllaTraccia = {
                            vm.apriCartella(traccia.cartellaId)
                            scope.launch {
                                val indice = state.tracce
                                    .filter { it.cartellaId == traccia.cartellaId }
                                    .ordinate(state.ordinamento)
                                    .indexOfFirst { it.id == traccia.id }
                                if (indice >= 0) listaTracce.animateScrollToItem(indice + 1)
                            }
                        },
                        onTogglePlay = { vm.togglePlay(traccia.id) },
                        onCommenta = {
                            vm.apriCartella(traccia.cartellaId)
                            scope.launch {
                                val indice = state.tracce
                                    .filter { it.cartellaId == traccia.cartellaId }
                                    .ordinate(state.ordinamento)
                                    .indexOfFirst { it.id == traccia.id }
                                if (indice >= 0) listaTracce.animateScrollToItem(indice + 1)
                            }
                        }
                    )
                }
            }

            // ---------- toast ----------
            AnimatedVisibility(
                visible = toastVisibile && state.messaggio != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
            ) {
                state.messaggio?.let { AppToast(it.testo) }
            }
        }

        // ---------- fogli ----------
        daEliminare?.let { (traccia, commento) ->
            ConfermaSheet(
                titolo = "Eliminare il commento?",
                testo = "Non si può annullare. Il commento sparirà per tutto il gruppo al prossimo aggiornamento.",
                etichettaConferma = "Elimina",
                coloreConferma = colors.danger,
                onAnnulla = { daEliminare = null },
                onConferma = {
                    vm.eliminaCommento(traccia.id, commento.id)
                    daEliminare = null
                }
            )
        }

        confermaBulk?.let { cartellaId ->
            val mancanti = state.tracce.count { it.cartellaId == cartellaId && !it.scaricata }
            val totali = state.tracce.count { it.cartellaId == cartellaId }
            ConfermaSheet(
                titolo = "Scaricare tutte le tracce?",
                testo = if (mancanti == totali) "Scaricare tutte le $totali tracce di questa cartella?"
                else "Scaricare le $mancanti tracce mancanti di questa cartella?",
                etichettaConferma = "Scarica",
                coloreConferma = colors.accent,
                onAnnulla = { confermaBulk = null },
                onConferma = {
                    vm.scaricaTutte(cartellaId)
                    confermaBulk = null
                }
            )
        }

        dettagli?.let { traccia ->
            val aggiornata = state.tracce.find { it.id == traccia.id } ?: traccia
            TrackDetailsSheet(
                traccia = aggiornata,
                inRiproduzione = state.riproduzione.tracciaId == traccia.id &&
                    state.riproduzione.inRiproduzione,
                onChiudi = { dettagli = null },
                onTogglePlay = { vm.togglePlay(traccia.id) },
                onRiproduciDa = { vm.riproduciDa(traccia.id, it) }
            )
        }

        if (accountAperto) {
            state.identita?.let { io ->
                AccountSheet(
                    utente = io,
                    cartelleCollegate = state.cartelle.filterNot { it.id in ID_DEMO },
                    onChiudi = { accountAperto = false },
                    onRimuoviCartella = vm::rimuoviCartella,
                    onSimulaReinstallazione = {
                        accountAperto = false
                        vm.simulaReinstallazione()
                    },
                    onSvuotaCloud = {
                        accountAperto = false
                        vm.svuotaCloudSimulato()
                    }
                )
            }
        }
    }
}

private fun azioniPer(
    traccia: Traccia,
    vm: AppViewModel,
    onDettagli: () -> Unit
) = TrackCardActions(
    onTogglePlay = { vm.togglePlay(traccia.id) },
    onSposta = { vm.spostaCursore(traccia.id, it) },
    onRiproduciDa = { vm.riproduciDa(traccia.id, it) },
    onCambiaVoto = { vm.cambiaVoto(traccia.id) },
    onCambiaDownload = { vm.cambiaDownload(traccia.id) },
    onRinomina = { vm.rinominaTraccia(traccia.id, it) },
    onAggiungiCommento = { secondi, testo -> vm.aggiungiCommento(traccia.id, secondi, testo) },
    onModificaCommento = { id, secondi, testo -> vm.modificaCommento(traccia.id, id, secondi, testo) },
    onEliminaCommento = { vm.eliminaCommento(traccia.id, it) },
    onApriDettagli = onDettagli
)
