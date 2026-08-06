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
        var confermaSvuotaCloud by remember { mutableStateOf(false) }
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

        // Le tracce della cartella aperta, filtrate e ordinate **una volta**.
        //
        // Erano ricalcolate dentro il `derivedStateOf` qui sotto, che dipende
        // da `layoutInfo` e quindi si rivaluta a ogni frame di scorrimento: un
        // filtro e un ordinamento completo sessanta volte al secondo, che su
        // una cartella con molte tracce si sente eccome. `derivedStateOf`
        // evita le ricomposizioni inutili, non i calcoli inutili.
        val tracceCartella = remember(state.tracce, state.ordinamento, schermata) {
            (schermata as? Schermata.DettaglioCartella)?.let { s ->
                state.tracce.filter { it.cartellaId == s.cartellaId }.ordinate(state.ordinamento)
            } ?: emptyList()
        }

        // Anche la ricerca dell'indice sta fuori: cambia quando cambia la
        // traccia in ascolto, non quando si scorre.
        val indiceInAscolto = remember(tracceCartella, state.riproduzione.tracciaId) {
            val id = state.riproduzione.tracciaId ?: return@remember -1
            tracceCartella.indexOfFirst { it.id == id }
        }

        // La card della traccia in ascolto è a schermo in questo momento?
        // Qui dentro resta solo ciò che dipende davvero dallo scorrimento.
        // La chiave non è decorativa: `indiceInAscolto` è un `Int` normale, e
        // senza di essa il blocco lo catturerebbe una volta sola, continuando
        // a guardare per sempre la posizione della prima traccia ascoltata.
        val cardVisibile by remember(indiceInAscolto) {
            derivedStateOf {
                if (indiceInAscolto < 0) return@derivedStateOf false

                // +1: il primo elemento della lista è la sort bar
                val info = listaTracce.layoutInfo
                val card = info.visibleItemsInfo.find { it.index == indiceInAscolto + 1 }
                    ?: return@derivedStateOf false

                // Serve che se ne veda più di un terzo, come la soglia 0.35
                // dell'IntersectionObserver nel prototipo. Con "anche un pixel
                // basta" la barra sparirebbe quando della card si intravede solo
                // il bordo, che è esattamente quando serve di più.
                val visibile = minOf(card.offset + card.size, info.viewportEndOffset) -
                    maxOf(card.offset, info.viewportStartOffset)
                visibile > card.size * 0.35f
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
                // Anche il Gate ha la sua topbar: senza, l'app si apriva su una
                // schermata impaginata diversamente da tutte le altre. Lì il
                // titolo è il nome dell'app, e Sincronizza non c'è — non c'è
                // ancora un account da sincronizzare.
                val gate = schermata == Schermata.Gate
                val utenteInBarra = if (gate) null else state.identita
                AppTopBar(
                    titolo = if (gate) "RegiStrazio" else (cartellaCorrente?.nome ?: "Le tue prove"),
                    mostraIndietro = schermata is Schermata.DettaglioCartella,
                    avatar = utenteInBarra?.let { io ->
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
                    onAggiorna = vm::aggiorna,
                    mostraAggiorna = !gate,
                    sincronizzando = state.sincronizzazioneInCorso
                )

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
                            // La nuvoletta sulla riga della cartella: quante
                            // sue tracce sono in corso o in fila. Da fuori
                            // "sta scaricando qualcosa" non basta — serve
                            // sapere dove.
                            conteggioInCoda = { id ->
                                state.tracce.count {
                                    it.cartellaId == id &&
                                        state.scaricamenti[it.id]?.let { s -> !s.inPausa } == true
                                }
                            },
                            // Ora sono tutte cartelle vere, comprese quelle di
                            // prova: si rinominano e si scollegano tutte.
                            cartelleRinominabili = state.cartelle.map { it.id }.toSet(),
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
                            // Già filtrate e ordinate sopra: rifarlo qui
                            // significherebbe ripetere il lavoro a ogni tick
                            // del cursore mentre una traccia suona.
                            val tracce = tracceCartella
                            val scaricate = tracce.count { it.scaricata }

                            // Lo stato del tasto in cima si legge dalle tracce
                            // che ha davanti, non da un campo suo: la coda è
                            // una sola e queste sono le sue righe in questa
                            // cartella. Un secondo posto dove scriverlo
                            // sarebbe un secondo posto da tenere allineato.
                            val fasiQui = tracce.mapNotNull { state.scaricamenti[it.id]?.fase }
                            val bulkInCorso = fasiQui.any {
                                it == FaseDownload.CORSO || it == FaseDownload.ATTESA
                            }
                            val bulkInPausa = !bulkInCorso && fasiQui.any { it == FaseDownload.PAUSA }

                            FolderScreen(
                                tracce = tracce,
                                ordinamento = state.ordinamento,
                                scaricateSuTotali = scaricate to tracce.size,
                                bulkInCorso = bulkInCorso,
                                bulkInPausa = bulkInPausa,
                                tracciaInRiproduzione = state.riproduzione.tracciaId,
                                inRiproduzione = state.riproduzione.inRiproduzione,
                                audioAttivo = state.riproduzione.audioAttivo,
                                statoLista = listaTracce,
                                apriCommentoPer = state.richiestaCommento,
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
                                    if (bulkInCorso || bulkInPausa) vm.scaricaTutte(schermata.cartellaId)
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
                            // Nessun riquadro tutto suo: ti porta sulla card e
                            // apre quella vera, come il prototipo che preme il
                            // `.add-btn`. Una UI in meno da tenere allineata.
                            vm.chiediCommento(traccia.id)
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

        if (confermaSvuotaCloud) {
            ConfermaSheet(
                titolo = "Svuotare il database del gruppo?",
                testo = "Cancella cartelle, tracce, commenti e profili di TUTTI, " +
                    "non solo i tuoi. Quello che hai sul telefono resta, e tornerà " +
                    "da caricare al prossimo Sincronizza.",
                etichettaConferma = "Svuota tutto",
                coloreConferma = colors.danger,
                onAnnulla = { confermaSvuotaCloud = false },
                onConferma = {
                    vm.svuotaFirestore()
                    confermaSvuotaCloud = false
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
                    // Solo le cartelle che hai collegato tu. Scollegare non è un
                    // gesto locale: toglie la cartella a tutto il gruppo, quindi
                    // spetta a chi l'ha portata. Quelle senza un proprietario
                    // scritto restano elencate — nessuno le rivendica, e
                    // nasconderle vorrebbe dire renderle irremovibili.
                    cartelleCollegate = state.cartelle.filter {
                        it.aggiuntoDa.isBlank() || it.aggiuntoDa == io.appUid
                    },
                    onChiudi = { accountAperto = false },
                    onRimuoviCartella = vm::rimuoviCartella,
                    onSimulaReinstallazione = {
                        accountAperto = false
                        vm.simulaReinstallazione()
                    },
                    onSvuotaCloud = {
                        accountAperto = false
                        vm.svuotaCloudSimulato()
                    },
                    onSvuotaFirestore = {
                        accountAperto = false
                        confermaSvuotaCloud = true
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
