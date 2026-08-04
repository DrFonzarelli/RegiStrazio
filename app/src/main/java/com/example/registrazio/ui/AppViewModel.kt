package com.example.registrazio.ui

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.registrazio.data.DemoData
import com.example.registrazio.data.local.ArchivioLocale
import com.example.registrazio.data.local.ProfiliStore
import com.example.registrazio.data.local.db.ConteggioPendenti
import com.example.registrazio.data.remote.EsitoElenco
import com.example.registrazio.data.remote.LinkMega
import com.example.registrazio.data.remote.MegaApi
import com.example.registrazio.data.remote.MegaException
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.StatoSync
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.model.Utente
import androidx.media3.common.util.UnstableApi
import com.example.registrazio.data.remote.MegaCrypto
import com.example.registrazio.domain.identity.IdentityManager
import com.example.registrazio.domain.player.PlayerMega
import com.example.registrazio.util.OrdineNaturale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Schermata {
    data object Gate : Schermata
    data object Home : Schermata
    data class DettaglioCartella(val cartellaId: String) : Schermata
}

enum class Ordinamento { DEFAULT, PREFERITE }

/**
 * Cosa sta suonando, indipendentemente da quale cartella stai guardando.
 * Una traccia lasciata in play resta qui anche se la sua card non è più a schermo.
 */
data class StatoRiproduzione(
    val tracciaId: String? = null,
    val inRiproduzione: Boolean = false,
    val posizioneSecondi: Float = 0f,
    /**
     * L'audio sta uscendo davvero, non "abbiamo premuto play".
     *
     * Fra il tocco e il primo suono passa il tempo di chiedere l'indirizzo a
     * MEGA e riempire il buffer: l'equalizzatore deve seguire questo, non
     * l'intenzione, o si mette a ballare sul silenzio.
     */
    val audioAttivo: Boolean = false
)

/** Avanzamento dello "Scarica tutte" sulla cartella aperta. */
data class StatoBulkDownload(
    val cartellaId: String,
    val completate: Int,
    val totali: Int
)

/** Messaggio effimero; [seq] serve a rimostrare lo stesso testo due volte di fila. */
data class Messaggio(val testo: String, val seq: Long)

data class AppState(
    val temaScuro: Boolean = false,
    val identita: Utente? = null,
    val schermata: Schermata = Schermata.Gate,
    val cartelle: List<Cartella> = emptyList(),
    val tracce: List<Traccia> = emptyList(),
    val profiliDisponibili: List<Utente> = emptyList(),
    val ordinamento: Ordinamento = Ordinamento.DEFAULT,
    val riproduzione: StatoRiproduzione = StatoRiproduzione(),
    val bulkDownload: StatoBulkDownload? = null,
    val messaggio: Messaggio? = null,
    val collegamento: StatoCollegamento = StatoCollegamento(),
    /** Quante righe aspettano di finire su Firestore: è il badge del tasto Sincronizza. */
    val pendenti: ConteggioPendenti = ConteggioPendenti(0, 0, 0)
)

/**
 * Testo arrivato dal menu "Condividi" di un'altra app.
 *
 * [seq] distingue due invii identici di fila: senza, condividere due volte lo
 * stesso link non farebbe succedere niente la seconda volta.
 */
data class TestoCondiviso(val testo: String, val seq: Int)

/**
 * Collegamento di una cartella MEGA in corso.
 *
 * Serve uno stato perché ora c'è di mezzo la rete: prima la funzione rispondeva
 * subito con l'eventuale errore, adesso può metterci qualche secondo.
 */
data class StatoCollegamento(
    val inCorso: Boolean = false,
    val errore: String? = null,
    /** Cresce a ogni collegamento riuscito: la ghost card lo osserva per richiudersi. */
    val completati: Int = 0,
    /**
     * L'ultimo collegamento non ha saputo leggere il nome della cartella da MEGA
     * e ha ripiegato su "Cartella A6kViD": vale la pena chiederlo subito.
     * Quando il nome vero c'è, aprire la rinomina sarebbe solo un intralcio.
     */
    val chiediNome: Boolean = false,

    /**
     * Link arrivato da una condivisione, da mettere nel campo già pronto.
     *
     * Il collegamento **non** parte da solo: chiunque può condividere testo
     * verso l'app, e una cartella che si aggiunge da sé sarebbe difficile
     * perfino da spiegare. La conferma resta un gesto dell'utente.
     */
    val linkPrecompilato: String? = null,
    val seqPrecompilato: Int = 0
)

@OptIn(UnstableApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val identityManager = IdentityManager(app)
    private val profiliStore = ProfiliStore(app)
    private val archivio = ArchivioLocale(app)
    private val megaApi = MegaApi()

    /**
     * Scope a parte per le scritture su disco.
     *
     * Non `viewModelScope`: quello muore con la schermata, e un commento scritto
     * un istante prima di chiudere l'app resterebbe a metà. Salvare deve
     * arrivare in fondo comunque.
     */
    private val scrittura = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val player = PlayerMega(app)

    /**
     * Chiavi di decrittazione dei file, per id traccia.
     *
     * Copia in memoria di quelle in archivio, caricata all'avvio: serve solo a
     * non interrogare il disco a ogni play. Non escono mai da qui — su Firestore
     * va `idFileMega`, mai la chiave.
     */
    private val chiaviFile = mutableMapOf<String, MegaCrypto.ChiaveFile>()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var playJob: Job? = null

    /** Traccia attualmente caricata nel player: serve a distinguere una ripresa da un avvio. */
    private var tracciaCaricata: String? = null
    private var bulkJob: Job? = null
    private var seqMessaggi = 0L

    /** Condivisione arrivata prima dell'onboarding: si riprende dopo il Gate. */
    private var condivisioneInAttesa: String? = null

    /** Secondi ascoltati di fila nella sessione corrente, per il conteggio degli ascolti. */
    private var ascoltoAccumulato = 0f
    private var ascoltoGiaContato = false

    init {
        // La durata vera si conosce solo quando il player apre il file: fino a
        // quel momento la traccia vale 0 e l'interfaccia mostra "--:--".
        player.onDurata = { secondi ->
            _state.value.riproduzione.tracciaId?.let { id ->
                aggiornaTraccia(id) { it.copy(durataSecondi = secondi) }
            }
        }
        player.onFine = {
            playJob?.cancel()
            playJob = null
            _state.update {
                it.copy(
                    riproduzione = it.riproduzione.copy(
                        inRiproduzione = false,
                        posizioneSecondi = 0f,
                        audioAttivo = false
                    )
                )
            }
        }
        player.onErrore = { messaggio ->
            fermaRiproduzione()
            mostra(messaggio)
        }

        val identita = identityManager.identita
        _state.update {
            it.copy(
                identita = identita,
                schermata = if (identita != null) Schermata.Home else Schermata.Gate,
                cartelle = DemoData.cartelle,
                tracce = DemoData.tracce,
                profiliDisponibili = profiliStore.profili()
            )
        }

        // Il vero contenuto arriva dall'archivio locale, non da Firestore: è lui
        // la fonte di verità finché non si preme Sincronizza.
        viewModelScope.launch {
            val cartelle = archivio.cartelle()
            val tracce = archivio.tracce()
            chiaviFile.putAll(archivio.chiaviFile())
            val conteggio = archivio.pendenti()
            _state.update {
                it.copy(
                    cartelle = DemoData.cartelle + cartelle,
                    tracce = DemoData.tracce + tracce,
                    pendenti = conteggio
                )
            }
        }
    }

    /** Esegue una scrittura su disco e riallinea il conteggio dei pendenti. */
    private fun salva(blocco: suspend () -> Unit) {
        scrittura.launch {
            blocco()
            val conteggio = archivio.pendenti()
            _state.update { it.copy(pendenti = conteggio) }
        }
    }

    // ---------- tema ----------

    fun cambiaTema() = _state.update { it.copy(temaScuro = !it.temaScuro) }

    // ---------- identità ----------

    /**
     * Percorso A del Gate. Il nome duplicato viene rifiutato: è l'unico appiglio
     * che ha l'utente per riconoscersi nella lista di recupero, due "Luca"
     * renderebbero quella schermata inutilizzabile.
     */
    fun creaAccount(nome: String, colore: String): String? {
        val pulito = nome.trim()
        if (pulito.isEmpty()) return "Scrivi un nome."
        val esiste = _state.value.profiliDisponibili.any { it.nome.equals(pulito, ignoreCase = true) }
        if (esiste) return "Questo nome esiste già nel gruppo. Usa \"Ho già un account\" per recuperarlo."

        val utente = identityManager.creaNuovaIdentita(pulito, colore)
        profiliStore.registraProfilo(utente)
        _state.update {
            it.copy(
                identita = utente,
                profiliDisponibili = profiliStore.profili(),
                schermata = Schermata.Home
            )
        }
        mostra("Benvenuto, ${utente.nome}!")
        riprendiCondivisioneInAttesa()
        return null
    }

    /** Percorso B del Gate: l'utente ha riconosciuto il proprio avatar. */
    fun entraComeUtente(utente: Utente) {
        identityManager.adottaIdentita(utente)
        _state.update { it.copy(identita = utente, schermata = Schermata.Home) }
        mostra("Bentornato, ${utente.nome}!")
        riprendiCondivisioneInAttesa()
    }

    // ---------- condivisione da altre app ----------

    /**
     * Un'altra app ha condiviso del testo con RegiStrazio.
     *
     * MEGA non manda l'indirizzo nudo ma una frase intorno, quindi il link va
     * pescato dal testo prima di poterlo usare.
     */
    fun riceviCondivisione(testo: String) {
        val link = LinkMega.cercaNelTesto(testo)
        if (link == null) {
            mostra("Nel testo condiviso non ho trovato un link di cartella MEGA.")
            return
        }
        if (_state.value.identita == null) {
            // Prima dell'onboarding la ghost card non esiste ancora: il link si
            // mette da parte, altrimenti andrebbe perso proprio a chi apre
            // l'app per la prima volta.
            condivisioneInAttesa = link
            return
        }
        precompilaCollegamento(link)
    }

    private fun precompilaCollegamento(link: String) {
        _state.update {
            it.copy(
                // La ghost card sta nella Home: se sei dentro una cartella non
                // la vedresti comparire.
                schermata = Schermata.Home,
                collegamento = it.collegamento.copy(
                    linkPrecompilato = link,
                    seqPrecompilato = it.collegamento.seqPrecompilato + 1,
                    errore = null
                )
            )
        }
    }

    /** Da chiamare appena l'identità esiste, per riprendere una condivisione in attesa. */
    private fun riprendiCondivisioneInAttesa() {
        val link = condivisioneInAttesa ?: return
        condivisioneInAttesa = null
        precompilaCollegamento(link)
    }

    // ---------- navigazione ----------

    fun apriCartella(cartellaId: String) {
        _state.update { it.copy(schermata = Schermata.DettaglioCartella(cartellaId)) }
    }

    fun tornaHome() = _state.update { it.copy(schermata = Schermata.Home) }

    fun cambiaOrdinamento() = _state.update {
        it.copy(
            ordinamento = if (it.ordinamento == Ordinamento.PREFERITE) Ordinamento.DEFAULT
            else Ordinamento.PREFERITE
        )
    }

    /**
     * Per ora il tasto in topbar riporta solo cosa aspetta di essere caricato.
     * Diventerà il tasto Sincronizza quando ci sarà Firestore dall'altra parte.
     *
     * Il conteggio è di **documenti**, non di modifiche fatte: dieci ritocchi
     * allo stesso commento restano un solo documento da caricare. Per questo va
     * mostrato spezzato per tipo — "7 modifiche" faceva pensare a un numero di
     * gesti, che non è quello che verrà caricato.
     */
    fun aggiorna() {
        val p = _state.value.pendenti
        if (!p.ceNeSono) {
            mostra("Tutto sincronizzato")
            return
        }
        val pezzi = buildList {
            if (p.cartelle > 0) add("${p.cartelle} " + if (p.cartelle == 1) "cartella" else "cartelle")
            if (p.tracce > 0) add("${p.tracce} " + if (p.tracce == 1) "traccia" else "tracce")
            if (p.commenti > 0) add("${p.commenti} " + if (p.commenti == 1) "commento" else "commenti")
        }
        mostra("Da sincronizzare: " + pezzi.joinToString(", "))
    }

    // ---------- riproduzione ----------

    fun togglePlay(tracciaId: String) {
        val r = _state.value.riproduzione
        when {
            r.tracciaId == tracciaId && r.inRiproduzione -> mettiInPausa()

            // Stessa traccia già caricata nel player: si riprende e basta.
            // Ripassare da capo vorrebbe dire chiedere a MEGA un altro
            // indirizzo e riscaricare il flusso, con l'attesa che ne segue.
            r.tracciaId == tracciaId && tracciaCaricata == tracciaId -> riprendi(tracciaId)

            else -> avvia(tracciaId, if (r.tracciaId == tracciaId) r.posizioneSecondi else 0f)
        }
    }

    private fun riprendi(tracciaId: String) {
        player.riprendi()
        _state.update { it.copy(riproduzione = it.riproduzione.copy(inRiproduzione = true)) }
        playJob = viewModelScope.launch { seguiPosizione(tracciaId) }
    }

    /** Salta a un punto e riparte: usato dai chip dei commenti e dal grafico dettagli. */
    fun riproduciDa(tracciaId: String, secondi: Float) = avvia(tracciaId, secondi)

    /** Trascinamento del playhead: sposta senza cambiare stato play/pausa. */
    fun spostaCursore(tracciaId: String, secondi: Float) {
        val traccia = traccia(tracciaId) ?: return
        val durata = traccia.durataSecondi.toFloat()
        val nuova = if (durata > 0f) secondi.coerceIn(0f, durata) else secondi.coerceAtLeast(0f)

        if (_state.value.riproduzione.tracciaId == tracciaId && traccia.daMega) {
            player.cerca(nuova)
        }
        _state.update {
            it.copy(
                riproduzione = it.riproduzione.copy(
                    tracciaId = tracciaId,
                    posizioneSecondi = nuova
                )
            )
        }
    }

    private fun avvia(tracciaId: String, da: Float) {
        val traccia = traccia(tracciaId) ?: return
        playJob?.cancel()
        ascoltoAccumulato = 0f
        ascoltoGiaContato = false

        _state.update {
            it.copy(
                riproduzione = StatoRiproduzione(
                    tracciaId = tracciaId,
                    inRiproduzione = true,
                    posizioneSecondi = da.coerceAtLeast(0f),
                    audioAttivo = false
                )
            )
        }

        // Da qui il player non contiene più la traccia buona: o ne carica
        // un'altra, o va fermato perché si passa a una traccia simulata.
        tracciaCaricata = null

        if (traccia.daMega) avviaDaMega(traccia, da)
        else avviaSimulata(tracciaId, traccia.durataSecondi.toFloat(), da)
    }

    /**
     * Riproduzione vera: si chiede a MEGA un indirizzo fresco e lo si passa al
     * player, che dietro le quinte decifra il flusso.
     *
     * L'indirizzo si chiede a ogni play e non si conserva: scade dopo poche ore,
     * e riusarne uno vecchio darebbe un errore proprio mentre si preme play.
     */
    private fun avviaDaMega(traccia: Traccia, da: Float) {
        playJob = viewModelScope.launch {
            val chiave = chiaviFile[traccia.id]
            val link = _state.value.cartelle
                .find { it.id == traccia.cartellaId }
                ?.linkMega
                ?.let { LinkMega.parse(it) }

            if (chiave == null || link == null) {
                // Le chiavi vivono in memoria: dopo un riavvio non ci sono più,
                // e senza chiave il file resta cifrato.
                fermaRiproduzione()
                mostra("Ricollega la cartella per riascoltare questa traccia")
                return@launch
            }

            val url = try {
                megaApi.urlDiDownload(link, traccia.idFileMega)
            } catch (e: Exception) {
                fermaRiproduzione()
                mostra((e as? MegaException)?.message ?: "Non riesco a raggiungere MEGA.")
                return@launch
            }

            player.riproduci(url, chiave, da)
            tracciaCaricata = traccia.id
            seguiPosizione(traccia.id)
        }
    }

    /** Ricopia nello stato la posizione del player, finché il job non viene fermato. */
    private suspend fun seguiPosizione(tracciaId: String) {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            val posizione = player.posizioneSecondi
            registraAscolto(tracciaId, TICK_MS / 1000f, posizione)
            val suonaDavvero = player.staSuonando
            _state.update { s ->
                if (s.riproduzione.tracciaId != tracciaId) s
                else s.copy(
                    riproduzione = s.riproduzione.copy(
                        posizioneSecondi = posizione,
                        audioAttivo = suonaDavvero
                    )
                )
            }
        }
    }

    /** Tracce demo: non hanno un file dietro, l'avanzamento resta simulato. */
    private fun avviaSimulata(tracciaId: String, durata: Float, da: Float) {
        // Se prima suonava una traccia vera, va zittita: qui il player non
        // c'entra più e altrimenti continuerebbe per conto suo.
        player.ferma()
        if (durata <= 0f) return
        _state.update {
            it.copy(riproduzione = it.riproduzione.copy(posizioneSecondi = da.coerceIn(0f, durata)))
        }
        playJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                avanzaDiUnTick(tracciaId, durata)
            }
        }
    }

    /** Ferma tutto e riporta lo stato a "in pausa", senza toccare la posizione. */
    private fun fermaRiproduzione() {
        playJob?.cancel()
        playJob = null
        player.ferma()
        tracciaCaricata = null
        _state.update {
            it.copy(riproduzione = it.riproduzione.copy(inRiproduzione = false, audioAttivo = false))
        }
    }

    private fun avanzaDiUnTick(tracciaId: String, durata: Float) {
        val passo = TICK_MS / 1000f
        _state.update { s ->
            val r = s.riproduzione
            if (r.tracciaId != tracciaId || !r.inRiproduzione) return@update s

            var pos = r.posizioneSecondi + passo
            if (pos >= durata) {
                pos = 0f
                ascoltoAccumulato = 0f
                ascoltoGiaContato = false
            }

            // Un ascolto conta dopo 30 secondi effettivi, non al primo tap.
            var ascoltoInPiu = 0
            if (!ascoltoGiaContato) {
                ascoltoAccumulato += passo
                if (ascoltoAccumulato >= SOGLIA_ASCOLTO) {
                    ascoltoGiaContato = true
                    ascoltoInPiu = 1
                }
            }

            val indice = ((pos / durata) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            s.copy(
                riproduzione = r.copy(posizioneSecondi = pos, audioAttivo = true),
                tracce = s.tracce.map { t ->
                    if (t.id != tracciaId) t
                    else t.copy(
                        ascolti = t.ascolti + ascoltoInPiu,
                        playBuckets = t.playBuckets.mapIndexed { i, v ->
                            if (i == indice) v + passo else v
                        }
                    )
                }
            )
        }
    }

    fun mettiInPausa() {
        playJob?.cancel()
        playJob = null
        player.pausa()
        _state.update {
            it.copy(riproduzione = it.riproduzione.copy(inRiproduzione = false, audioAttivo = false))
        }
        // Ascolti e punti riascoltati si sono accumulati in memoria durante la
        // riproduzione: questo è il momento di metterli via.
        _state.value.riproduzione.tracciaId?.let { salvaTracciaSuDisco(it) }
    }

    /**
     * Conteggio ascolti e punti più riascoltati, per la riproduzione vera.
     * Il percorso simulato ha la sua versione dentro [avanzaDiUnTick].
     */
    private fun registraAscolto(tracciaId: String, passo: Float, posizione: Float) {
        val durata = traccia(tracciaId)?.durataSecondi?.toFloat() ?: return
        if (durata <= 0f) return

        var ascoltoInPiu = 0
        if (!ascoltoGiaContato) {
            ascoltoAccumulato += passo
            // Un ascolto conta dopo 30 secondi effettivi, non al primo tap.
            if (ascoltoAccumulato >= SOGLIA_ASCOLTO) {
                ascoltoGiaContato = true
                ascoltoInPiu = 1
            }
        }

        val indice = ((posizione / durata) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
        aggiornaTraccia(tracciaId, persisti = false) { t ->
            t.copy(
                ascolti = t.ascolti + ascoltoInPiu,
                playBuckets = t.playBuckets.mapIndexed { i, v -> if (i == indice) v + passo else v }
            )
        }
    }

    // ---------- voti ----------

    fun cambiaVoto(tracciaId: String) = aggiornaTraccia(tracciaId) { it.conVotoSuccessivo() }

    // ---------- commenti ----------

    fun aggiungiCommento(tracciaId: String, secondi: Float, testo: String) {
        val io = _state.value.identita ?: return
        val nuovo = Commento(
            id = UUID.randomUUID().toString(),
            tracciaId = tracciaId,
            appUid = io.appUid,
            autoreNome = io.nome,
            autoreColore = io.colore,
            timestampSecondi = secondi,
            testo = testo.trim()
        )
        aggiornaTraccia(tracciaId) { t ->
            t.copy(commenti = (t.commenti + nuovo).sortedBy { it.timestampSecondi })
        }
        salva { archivio.salvaCommento(nuovo) }
        mostra("Commento salvato")
    }

    fun modificaCommento(tracciaId: String, commentoId: String, secondi: Float, testo: String) {
        aggiornaTraccia(tracciaId) { t ->
            t.copy(
                commenti = t.commenti
                    .map { c ->
                        if (c.id == commentoId) c.copy(timestampSecondi = secondi, testo = testo.trim())
                        else c
                    }
                    .sortedBy { it.timestampSecondi }
            )
        }
        // Torna LOCALE anche se era già su Firestore: il testo è cambiato qui e
        // va ricaricato.
        traccia(tracciaId)?.commenti?.find { it.id == commentoId }?.let { modificato ->
            salva { archivio.salvaCommento(modificato.copy(statoSync = StatoSync.LOCALE)) }
        }
        mostra("Commento aggiornato")
    }

    fun eliminaCommento(tracciaId: String, commentoId: String) {
        aggiornaTraccia(tracciaId) { t -> t.copy(commenti = t.commenti.filterNot { it.id == commentoId }) }
        salva { archivio.eliminaCommento(commentoId) }
        mostra("Commento eliminato")
    }

    // ---------- download ----------

    fun cambiaDownload(tracciaId: String) {
        val scaricataOra = !(traccia(tracciaId)?.scaricata ?: return)
        aggiornaTraccia(tracciaId) {
            it.copy(
                scaricata = scaricataOra,
                downloadEvents = it.downloadEvents + if (scaricataOra) 1 else 0
            )
        }
        mostra(
            if (scaricataOra) "Traccia scaricata in locale"
            else "Rimossa dal locale — tornerà in streaming"
        )
    }

    fun scaricaTutte(cartellaId: String) {
        if (bulkJob?.isActive == true) return
        val mancanti = traccePerCartella(cartellaId).filterNot { it.scaricata }
        if (mancanti.isEmpty()) {
            mostra("Sono già tutte scaricate")
            return
        }
        val totali = traccePerCartella(cartellaId).size
        val giaFatte = totali - mancanti.size

        bulkJob = viewModelScope.launch {
            mancanti.forEachIndexed { i, t ->
                aggiornaTraccia(t.id) {
                    it.copy(scaricata = true, downloadEvents = it.downloadEvents + 1)
                }
                _state.update { s ->
                    s.copy(bulkDownload = StatoBulkDownload(cartellaId, giaFatte + i + 1, totali))
                }
                delay(PASSO_BULK_MS)
            }
            _state.update { it.copy(bulkDownload = null) }
            mostra("Tutte le tracce sono state scaricate")
        }
    }

    // ---------- cartelle ----------

    /**
     * Collega una cartella MEGA leggendone davvero il contenuto.
     *
     * L'esito non torna più come valore: passa da [AppState.collegamento],
     * perché ora c'è una chiamata di rete in mezzo.
     */
    fun collegaCartella(link: String) {
        if (_state.value.collegamento.inCorso) return

        val linkMega = LinkMega.parse(link)
        if (linkMega == null) {
            aggiornaCollegamento {
                it.copy(errore = "Non sembra un link di cartella MEGA valido. Serve un link del tipo mega.nz/folder/… con la chiave dopo il #.")
            }
            return
        }
        // Ricollegare una cartella già presente la ricarica invece di dare errore:
        // finché le tracce non stanno su Firestore, è l'unico modo di riaverle
        // dopo aver chiuso l'app senza dover prima scollegare la cartella.
        val giaCollegata = _state.value.cartelle.any { it.megaFolderId == linkMega.folderId }

        aggiornaCollegamento { it.copy(inCorso = true, errore = null) }

        viewModelScope.launch {
            val esito = runCatching { megaApi.elencaFileAudio(linkMega) }

            esito.onSuccess { risultato ->
                val file = risultato.audio
                if (file.isEmpty()) {
                    aggiornaCollegamento {
                        it.copy(inCorso = false, errore = spiegaElencoVuoto(risultato))
                    }
                    return@launch
                }

                val ripiego = Cartella.suggestName(linkMega.folderId)
                val nomePrecedente = _state.value.cartelle
                    .find { it.megaFolderId == linkMega.folderId }?.nome

                val cartella = Cartella(
                    id = linkMega.folderId,
                    // Un nome scelto a mano vince su quello di MEGA: se l'hai
                    // rinominata, ricaricarla non deve disfare la tua scelta.
                    // Il ripiego "Cartella A6kViD" invece si lascia sostituire.
                    nome = nomePrecedente?.takeIf { it.isNotBlank() && it != ripiego }
                        ?: risultato.nomeCartella?.takeIf { it.isNotBlank() }
                        ?: ripiego,
                    linkMega = link.trim(),
                    megaFolderId = linkMega.folderId,
                    aggiuntoDa = _state.value.identita?.appUid.orEmpty(),
                    numTracce = file.size
                )
                // Le chiavi arrivano solo con l'elenco dei file: vanno tenute
                // ora, o al play non si potrebbe decifrare niente.
                file.forEach { chiaviFile[it.handle] = it.chiave }

                val nuoveTracce = file.sortedWith(compareBy(OrdineNaturale) { it.nome }).map { f ->
                    Traccia(
                        id = f.handle,
                        cartellaId = cartella.id,
                        // Il nome su MEGA include l'estensione: nell'elenco è rumore.
                        titolo = f.nome.substringBeforeLast('.', f.nome),
                        idFileMega = f.handle,
                        // 0 = ancora ignota. La durata sta dentro il file audio, che
                        // a questo punto non abbiamo ancora aperto.
                        durataSecondi = 0
                    )
                }

                _state.update { stato ->
                    val cartelle =
                        if (giaCollegata) stato.cartelle.map { if (it.id == cartella.id) cartella else it }
                        else stato.cartelle + cartella
                    stato.copy(
                        cartelle = cartelle,
                        // Le tracce della cartella vengono sostituite in blocco: se
                        // su MEGA un file è sparito, deve sparire anche qui.
                        tracce = stato.tracce.filterNot { it.cartellaId == cartella.id } + nuoveTracce,
                        collegamento = stato.collegamento.copy(
                            inCorso = false,
                            errore = null,
                            completati = stato.collegamento.completati + 1,
                            chiediNome = !giaCollegata && risultato.nomeCartella.isNullOrBlank()
                        )
                    )
                }
                salva {
                    archivio.salvaCartella(cartella)
                    archivio.sostituisciTracce(cartella.id, nuoveTracce, chiaviFile)
                }
                mostra(
                    if (giaCollegata) "Cartella aggiornata — ${file.size} tracce"
                    else "Collegate ${file.size} tracce"
                )
            }

            esito.onFailure { errore ->
                aggiornaCollegamento {
                    it.copy(
                        inCorso = false,
                        errore = (errore as? MegaException)?.message
                            ?: "Non riesco a leggere la cartella. Controlla la connessione."
                    )
                }
            }
        }
    }

    /**
     * Perché la cartella non ha prodotto nessuna traccia.
     *
     * Le tre cause vogliono tre messaggi diversi: cartella vuota, nomi che non si
     * decifrano (chiave sbagliata), file che non sono audio. Con un messaggio
     * unico non si capirebbe da che parte guardare.
     */
    private fun spiegaElencoVuoto(esito: EsitoElenco): String = when {
        esito.fileTotali == 0 ->
            "La cartella è raggiungibile ma è vuota."

        esito.nonDecifrati == esito.fileTotali ->
            "Ho letto ${esito.fileTotali} file ma non riesco a decifrarne i nomi. " +
                "Di solito vuol dire che il link è incompleto: la parte dopo il # è la chiave e serve tutta."

        esito.audio.isEmpty() && esito.estensioniScartate.isNotEmpty() ->
            "Ho letto ${esito.fileTotali} file, ma nessuno è audio " +
                "(ho trovato: ${esito.estensioniScartate.sorted().joinToString(", ")})."

        else ->
            "Ho letto ${esito.fileTotali} file ma non ne è utilizzabile nessuno " +
                "(${esito.nonDecifrati} non decifrati)."
    }

    fun pulisciErroreCollegamento() = aggiornaCollegamento { it.copy(errore = null) }

    /** Né l'app MEGA né un browser hanno raccolto la richiesta: meglio dirlo. */
    fun megaNonApribile() = mostra("Non riesco ad aprire MEGA su questo telefono.")

    private fun aggiornaCollegamento(blocco: (StatoCollegamento) -> StatoCollegamento) =
        _state.update { it.copy(collegamento = blocco(it.collegamento)) }

    fun rimuoviCartella(cartellaId: String) {
        val nome = _state.value.cartelle.find { it.id == cartellaId }?.nome ?: return
        salva { archivio.rimuoviCartella(cartellaId) }
        _state.update {
            it.copy(
                cartelle = it.cartelle.filterNot { c -> c.id == cartellaId },
                tracce = it.tracce.filterNot { t -> t.cartellaId == cartellaId }
            )
        }
        mostra("Collegamento a \"$nome\" rimosso.")
    }

    fun rinominaCartella(cartellaId: String, nome: String) {
        val pulito = nome.trim()
        if (pulito.isEmpty()) return
        _state.update {
            it.copy(cartelle = it.cartelle.map { c -> if (c.id == cartellaId) c.copy(nome = pulito) else c })
        }
        // Va salvata, non solo mostrata: altrimenti il nome scelto sparisce alla
        // prima chiusura. Le cartelle demo non hanno un link e restano fuori
        // dall'archivio, o al riavvio comparirebbero doppie.
        _state.value.cartelle
            .find { it.id == cartellaId && it.linkMega.isNotBlank() }
            ?.let { cartella -> salva { archivio.salvaCartella(cartella) } }
    }

    fun rinominaTraccia(tracciaId: String, titolo: String) {
        val pulito = titolo.trim()
        if (pulito.isEmpty()) return
        aggiornaTraccia(tracciaId) { it.copy(titolo = pulito) }
    }

    // ---------- strumenti di test ----------

    fun simulaReinstallazione() {
        identityManager.dimentica()
        mettiInPausa()
        // Reinstallare cancella i dati dell'app: anche l'archivio locale.
        chiaviFile.clear()
        salva { archivio.svuota() }
        _state.update {
            it.copy(
                identita = null,
                schermata = Schermata.Gate,
                cartelle = DemoData.cartelle,
                tracce = DemoData.tracce
            )
        }
        mostra("Dispositivo reimpostato — recupera il tuo account per ritrovare le cartelle.")
    }

    fun svuotaCloudSimulato() {
        profiliStore.svuota()
        identityManager.dimentica()
        mettiInPausa()
        chiaviFile.clear()
        salva { archivio.svuota() }
        _state.update {
            AppState(
                temaScuro = it.temaScuro,
                cartelle = DemoData.cartelle,
                tracce = DemoData.tracce
            )
        }
        mostra("Cloud simulato svuotato completamente.")
    }

    // ---------- lettura ----------

    fun traccia(id: String): Traccia? = _state.value.tracce.find { it.id == id }

    fun traccePerCartella(cartellaId: String): List<Traccia> =
        _state.value.tracce.filter { it.cartellaId == cartellaId }

    fun messaggioMostrato() = _state.update { it.copy(messaggio = null) }

    // ---------- helper ----------

    private fun mostra(testo: String) {
        _state.update { it.copy(messaggio = Messaggio(testo, ++seqMessaggi)) }
    }

    /**
     * [persisti] a `false` solo dal ciclo di riproduzione: là passa quattro
     * volte al secondo, e scrivere su disco a quel ritmo non ha senso. Quei
     * contatori vengono salvati quando la riproduzione si ferma.
     */
    private fun aggiornaTraccia(
        id: String,
        persisti: Boolean = true,
        blocco: (Traccia) -> Traccia
    ) {
        _state.update { s ->
            s.copy(tracce = s.tracce.map { if (it.id == id) blocco(it) else it })
        }
        if (persisti) salvaTracciaSuDisco(id)
    }

    /**
     * Le tracce demo non stanno in archivio: salvarle le farebbe comparire
     * doppie al riavvio, una volta dalla demo e una dal disco.
     */
    private fun salvaTracciaSuDisco(id: String) {
        val aggiornata = traccia(id)?.takeIf { it.daMega } ?: return
        salva { archivio.salvaTraccia(aggiornata, chiaviFile[id]) }
    }

    override fun onCleared() {
        playJob?.cancel()
        bulkJob?.cancel()
        // Senza questa il player continuerebbe a tenersi socket e decoder.
        player.rilascia()
        // `scrittura` non si annulla apposta: una scrittura partita un istante
        // prima della chiusura deve arrivare in fondo, è tutto il suo scopo.
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
        const val PASSO_BULK_MS = 380L
        const val SOGLIA_ASCOLTO = 30f
        const val BUCKETS = 24
    }
}

/**
 * Ordina le tracce come fa `applySort` nel prototipo: a parità di punteggio
 * resta l'ordine originale di MEGA, così la lista non balla a ogni voto.
 */
fun List<Traccia>.ordinate(modo: Ordinamento): List<Traccia> = when (modo) {
    Ordinamento.DEFAULT -> this
    Ordinamento.PREFERITE -> sortedWith(
        compareByDescending<Traccia> { it.punteggio }.thenBy { indexOf(it) }
    )
}
