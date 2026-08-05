package com.example.registrazio.ui

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.registrazio.data.DatiDiProva
import com.example.registrazio.data.local.ArchivioLocale
import com.example.registrazio.data.local.DatiDiProvaStore
import com.example.registrazio.data.local.ProfiliStore
import com.example.registrazio.data.local.db.ConteggioPendenti
import com.example.registrazio.data.remote.EsitoElenco
import com.example.registrazio.data.remote.LinkMega
import com.example.registrazio.data.remote.FirestoreRepository
import com.example.registrazio.data.remote.MegaApi
import com.example.registrazio.data.remote.MegaException
import com.example.registrazio.data.remote.ScaricatoreMega
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.StatoSync
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.model.Utente
import androidx.media3.common.util.UnstableApi
import com.example.registrazio.data.remote.MegaCrypto
import com.example.registrazio.domain.EsitoSync
import com.example.registrazio.domain.SyncManager
import com.example.registrazio.domain.identity.IdentityManager
import com.example.registrazio.domain.player.CommentiDaFuori
import com.example.registrazio.domain.player.PlayerCondiviso
import com.example.registrazio.domain.player.PlayerMega
import com.example.registrazio.domain.player.ServizioRiproduzione
import com.example.registrazio.domain.player.TracciaInAscolto
import com.example.registrazio.util.OrdineNaturale
import com.example.registrazio.util.senzaRete
import com.example.registrazio.util.trasferimentoFermo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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
    val messaggio: Messaggio? = null,
    val collegamento: StatoCollegamento = StatoCollegamento(),
    /** Quante righe aspettano di finire su Firestore: è il badge del tasto Sincronizza. */
    val pendenti: ConteggioPendenti = ConteggioPendenti(0, 0, 0),
    /** Il giro di sincronizzazione è in corso: l'icona in topbar gira. */
    val sincronizzazioneInCorso: Boolean = false,
    /**
     * Avanzamento dei download, da 0 a 1, per id traccia.
     *
     * Qui c'è una percentuale vera — byte scaricati sul totale — a differenza
     * della lettura di una cartella, che è una chiamata sola e non si misura.
     */
    val scaricamenti: Map<String, StatoScaricamento> = emptyMap(),
    /** Vedi [RichiestaCommento]: la barra in ascolto chiede alla card di aprirsi. */
    val richiestaCommento: RichiestaCommento? = null
)

/**
 * Richiesta di aprire il riquadro del commento su una traccia.
 *
 * Arriva dalla barra in ascolto, che non ha un riquadro suo: ti porta sulla card
 * e apre quella vera, come fa il prototipo premendo il `.add-btn`. Una
 * scorciatoia in meno da tenere allineata in due posti.
 *
 * [seq] serve perché la richiesta è un evento, non uno stato: chiederlo due
 * volte di fila sulla stessa traccia deve funzionare entrambe le volte.
 */
data class RichiestaCommento(val tracciaId: String, val seq: Int)

/**
 * Un download che è a metà strada.
 *
 * [inPausa] distingue "sta scaricando" da "si è fermato lì": il file parziale
 * resta sul telefono in entrambi i casi, e riprendere non ricomincia da zero.
 */
/**
 * Dove si trova una traccia nella coda dei download.
 *
 * Tre stati e non due: prima "in attesa" non esisteva, e una traccia messa in
 * fila si presentava identica a una che stava scaricando — con la percentuale
 * ferma, perché nessuno la stava toccando. Metà della confusione stava lì.
 */
enum class FaseDownload { ATTESA, CORSO, PAUSA }

data class StatoScaricamento(val frazione: Float, val fase: FaseDownload) {
    /** Ferma per volontà di qualcuno, non perché sta aspettando il suo turno. */
    val inPausa: Boolean get() = fase == FaseDownload.PAUSA

    val inAttesa: Boolean get() = fase == FaseDownload.ATTESA
}

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
    val seqPrecompilato: Int = 0,

    /**
     * Quale cartella già collegata si sta ricaricando, per mostrarlo sulla sua
     * card invece che con un messaggio generico.
     *
     * `null` quando si collega una cartella nuova: quella una card non ce l'ha
     * ancora, e l'attesa la mostra la ghost card.
     */
    val cartellaInAggiornamento: String? = null
)

@OptIn(UnstableApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val identityManager = IdentityManager(app)
    private val profiliStore = ProfiliStore(app)
    private val datiDiProva = DatiDiProvaStore(app)
    private val archivio = ArchivioLocale(app)
    private val firestore = FirestoreRepository()
    private val sync = SyncManager(archivio, firestore)
    private val megaApi = MegaApi()
    private val scaricatore = ScaricatoreMega(megaApi)

    /** File audio già sul telefono, per id traccia. */
    private val fileLocali = mutableMapOf<String, File>()

    private var seqCommento = 1

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

    /**
     * [tracciaCaricata] viene dal file sul telefono e non dallo streaming.
     *
     * Non è ricavabile guardando `fileLocali`: quella mappa dice se il file
     * c'è **adesso**, non da dove il player stava leggendo quando ha
     * cominciato — ed è proprio la differenza fra i due che dice se conviene
     * ricaricare.
     */
    private var caricataDaFile = false
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
        // Play/pausa possono arrivare dalla notifica, dai tasti delle cuffie o
        // da una telefonata. Qui si **riallinea** e basta: nessun comando al
        // player, o si rimbalzerebbe con chi l'ha appena dato.
        player.onPlayPausa = { suona -> allineaAlPlayer(suona) }

        // Un commento scritto dalla notifica è già su Room. Se l'app è viva va
        // portato anche in memoria, o la card resterebbe indietro fino al
        // prossimo avvio.
        viewModelScope.launch {
            CommentiDaFuori.nuovi.collect { tracciaId -> ricaricaCommenti(tracciaId) }
        }

        player.onErrore = { errore ->
            fermaRiproduzione()
            // Il messaggio di ExoPlayer non è per gli occhi di nessuno: o è la
            // rete, e allora c'è una frase apposta, o è un guaio del file.
            mostra(if (senzaRete(errore)) SENZA_RETE else "Non riesco a riprodurre la traccia.")
        }

        val identita = identityManager.identita
        _state.update {
            it.copy(
                identita = identita,
                schermata = if (identita != null) Schermata.Home else Schermata.Gate,
                profiliDisponibili = profiliStore.profili()
            )
        }

        // Il vero contenuto arriva dall'archivio locale, non da Firestore: è lui
        // la fonte di verità finché non si preme Sincronizza.
        viewModelScope.launch {
            val cartelle = archivio.cartelle()
            val tracce = archivio.tracce()
            chiaviFile.putAll(archivio.chiaviFile())
            fileLocali.putAll(archivio.download())
            val conteggio = archivio.pendenti()
            _state.update {
                it.copy(
                    cartelle = cartelle,
                    tracce = tracce,
                    pendenti = conteggio,
                    scaricamenti = scaricamentiLasciatiAMeta(tracce)
                )
            }
            // Dopo l'archivio, mai prima: il seme deve poter vedere cosa c'è
            // già, o riscriverebbe sopra il lavoro fatto sulle stesse cartelle.
            seminaDatiDiProva()
        }

        aggiornaProfiliDalCloud()
    }

    /**
     * L'elenco per "Ho già un account", preso da Firestore.
     *
     * Parte da quello in cache — [ProfiliStore] — che è immediato e funziona
     * senza linea, e lo sostituisce con quello vero appena arriva. Aspettare la
     * rete lascerebbe il Gate con una lista vuota proprio a chi ha appena
     * reinstallato e sta cercando sé stesso; e senza linea resterebbe vuota per
     * sempre, cioè irrecuperabile.
     */
    private fun aggiornaProfiliDalCloud() {
        viewModelScope.launch {
            val remoti = runCatching { sync.profili() }.getOrNull() ?: return@launch
            if (remoti.isEmpty()) return@launch
            remoti.forEach { profiliStore.registraProfilo(it) }
            _state.update { it.copy(profiliDisponibili = profiliStore.profili()) }
        }
    }

    /**
     * I download interrotti, ritrovati sul disco all'avvio.
     *
     * `scaricamenti` vive in memoria e muore con l'app, il `.parziale` no:
     * senza questo, riaprendo l'app una traccia lasciata a metà si presentava
     * come mai toccata, e la percentuale ricompariva solo premendo di nuovo
     * scarica. Riprendeva dal punto giusto — non lo diceva.
     *
     * Tutti in pausa, perché è quello che sono: nessun download riparte da
     * solo alla riapertura.
     */
    private fun scaricamentiLasciatiAMeta(tracce: List<Traccia>): Map<String, StatoScaricamento> =
        tracce.mapNotNull { t ->
            if (t.scaricata || t.dimensioneByte <= 0) return@mapNotNull null
            val presi = scaricatore.byteGiaPresi(File(cartellaAudio(), "${t.id}.audio"))
            if (presi <= 0) return@mapNotNull null
            t.id to StatoScaricamento(
                frazione = (presi.toFloat() / t.dimensioneByte).coerceIn(0f, 1f),
                fase = FaseDownload.PAUSA
            )
        }.toMap()

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
        // Su Firestore appena si può, senza far aspettare il Gate: l'account
        // esiste già sul telefono, e se la rete manca il profilo parte al primo
        // Sincronizza. Bloccare qui vorrebbe dire non poter creare un account
        // senza linea, che è esattamente quando serve poterlo fare.
        viewModelScope.launch {
            runCatching {
                firestore.assicuraAccesso()
                firestore.salvaProfilo(utente)
            }
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

    /**
     * Il tasto commento della barra in ascolto: porta sulla cartella giusta e
     * chiede alla card di aprire il suo riquadro.
     */
    fun chiediCommento(tracciaId: String) {
        val traccia = traccia(tracciaId) ?: return
        _state.update {
            it.copy(
                schermata = Schermata.DettaglioCartella(traccia.cartellaId),
                richiestaCommento = RichiestaCommento(tracciaId, seqCommento++)
            )
        }
    }

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
    /**
     * Il tasto Sincronizza: rilettura di MEGA, poi pull da Firestore, poi push.
     *
     * MEGA **per primo**, e non è un dettaglio di comodo. Rileggere una cartella
     * scrive in Room le tracce nuove in stato `LOCALE`, cioè da caricare: se
     * arrivasse dopo il push, quelle tracce resterebbero in coda fino alla
     * sincronizzazione successiva, e il contatore non tornerebbe mai a zero — a
     * ogni giro ne troverebbe di fresche appena create dal giro stesso.
     * Facendolo prima, tutto quello che MEGA ha da dire parte nello stesso giro.
     */
    fun aggiorna() {
        if (sincronizzando) return
        sincronizzando = true
        _state.update { it.copy(sincronizzazioneInCorso = true) }

        viewModelScope.launch {
            val esito = runCatching {
                rileggiCartelleDaMega()
                sync.sincronizza(_state.value.identita, chiaviFile)
            }

            esito.onSuccess { risultato ->
                ricaricaDaArchivio()
                mostra(riassunto(risultato))
            }
            esito.onFailure { errore ->
                // Room non è stato toccato: quello che c'era è ancora lì, e al
                // prossimo tentativo si riparte da dove eravamo.
                mostra(spiegaErroreDiRete(errore))
            }

            sincronizzando = false
            _state.update { it.copy(sincronizzazioneInCorso = false) }
        }
    }

    private fun riassunto(esito: EsitoSync): String {
        val pezzi = buildList {
            if (esito.caricati > 0) add("${esito.caricati} caricati")
            if (esito.scaricati > 0) add("${esito.scaricati} ricevuti")
            if (esito.falliti > 0) add("${esito.falliti} non riusciti")
        }
        return if (pezzi.isEmpty()) "Già tutto sincronizzato" else pezzi.joinToString(" · ")
    }

    /**
     * Ricontrolla su MEGA il contenuto di ogni cartella collegata.
     *
     * Un fallimento non ferma il giro: una cartella il cui link è scaduto non
     * deve impedire alle altre di aggiornarsi, e il messaggio l'ha già dato la
     * sincronizzazione.
     */
    private suspend fun rileggiCartelleDaMega() {
        for (cartella in _state.value.cartelle) {
            val linkMega = LinkMega.parse(cartella.linkMega) ?: continue
            val risultato = runCatching { megaApi.elencaFileAudio(linkMega) }.getOrNull() ?: continue
            if (risultato.audio.isEmpty()) continue

            val (aggiornata, tracce) = costruisciDaMega(
                link = cartella.linkMega,
                linkMega = linkMega,
                risultato = risultato,
                aggiuntoDa = cartella.aggiuntoDa
            )
            _state.update { stato ->
                stato.copy(
                    cartelle = stato.cartelle.map { if (it.id == aggiornata.id) aggiornata else it },
                    tracce = stato.tracce.filterNot { it.cartellaId == aggiornata.id } + tracce
                )
            }
            archivio.sostituisciTracce(aggiornata.id, tracce, chiaviFile)
        }
    }

    /** Rilegge tutto dall'archivio: dopo un sync la verità sta lì, non in memoria. */
    private suspend fun ricaricaDaArchivio() {
        val cartelle = archivio.cartelle()
        val tracce = archivio.tracce()
        chiaviFile.putAll(archivio.chiaviFile())
        _state.update {
            it.copy(cartelle = cartelle, tracce = tracce, pendenti = archivio.pendenti())
        }
    }

    // ---------- riproduzione ----------

    fun togglePlay(tracciaId: String) {
        val r = _state.value.riproduzione
        when {
            r.tracciaId == tracciaId && r.inRiproduzione -> mettiInPausa()

            // Stessa traccia già caricata nel player: si riprende e basta.
            // Ripassare da capo vorrebbe dire chiedere a MEGA un altro
            // indirizzo e riscaricare il flusso, con l'attesa che ne segue.
            r.tracciaId == tracciaId && tracciaCaricata == tracciaId && !sorgenteSuperata(tracciaId) ->
                riprendi(tracciaId)

            else -> avvia(tracciaId, if (r.tracciaId == tracciaId) r.posizioneSecondi else 0f)
        }
    }

    /**
     * Il player sta ancora leggendo da MEGA una traccia che nel frattempo è
     * arrivata sul telefono.
     *
     * Il download può finire mentre la traccia suona, e interrompere l'audio
     * per rimettere lo stesso brano da un'altra sorgente sarebbe peggio del
     * problema. Però alla **prima pausa** il passaggio va fatto: da lì in poi
     * ogni ripresa e ogni salto sarebbero istantanei, e restare sullo streaming
     * vuol dire pagare un'attesa per un file che è già lì. Prima ci si arrivava
     * solo per caso, andando su un'altra traccia e tornando indietro.
     */
    private fun sorgenteSuperata(tracciaId: String): Boolean =
        !caricataDaFile && fileLocali[tracciaId]?.exists() == true

    private fun riprendi(tracciaId: String) {
        player.riprendi()
        _state.update { it.copy(riproduzione = it.riproduzione.copy(inRiproduzione = true)) }
        // Cancellare prima di rilanciare: senza, un ciclo lasciato indietro
        // continuerebbe a scrivere la stessa posizione insieme al nuovo.
        playJob?.cancel()
        playJob = viewModelScope.launch { seguiPosizione(tracciaId) }
    }

    /**
     * Salta a un punto e riparte: i chip dei commenti e il grafico dei dettagli.
     *
     * Se il player ha **già dentro** questa traccia, saltare è un `seek` e
     * basta. Prima si ripassava sempre da [avvia], che butta via il flusso,
     * richiede a MEGA un indirizzo nuovo e ricarica tutto da capo: mezzo
     * secondo buono di attesa per spostarsi di dieci secondi, sulla funzione
     * che è il motivo per cui l'app esiste.
     *
     * Ed era anche una corsa. [avvia] comincia cancellando il `playJob`
     * precedente; se quello stava aspettando l'indirizzo da MEGA, la
     * cancellazione risaliva fin dentro il suo `catch`, che la scambiava per
     * un errore di rete e chiamava `fermaRiproduzione()` — spegnendo la
     * riproduzione che era appena partita. Da fuori: l'audio va avanti, il
     * cursore si pianta, e ogni tanto compare un "coroutine was cancelled".
     */
    fun riproduciDa(tracciaId: String, secondi: Float) {
        val r = _state.value.riproduzione
        // Anche saltare a un commento è un'interruzione, e va sfruttata come la
        // pausa: se nel frattempo il file è arrivato sul telefono, si riparte
        // da lì. L'audio si interrompe comunque per andare altrove, quindi qui
        // il passaggio non costa niente — e da quel momento i salti successivi
        // sono immediati invece di passare da MEGA.
        if (r.tracciaId != tracciaId || tracciaCaricata != tracciaId || sorgenteSuperata(tracciaId)) {
            avvia(tracciaId, secondi)
            return
        }

        val punto = secondi.coerceAtLeast(0f)
        player.cerca(punto)
        // Se era in pausa il seek non basta: va ripreso.
        if (!r.inRiproduzione) player.riprendi()
        // Il ciclo si riaccende guardando il job, non lo stato: sono due cose
        // diverse, e quando divergono è proprio nei casi che rompono.
        if (playJob?.isActive != true) {
            playJob = viewModelScope.launch { seguiPosizione(tracciaId) }
        }
        _state.update {
            it.copy(
                riproduzione = it.riproduzione.copy(
                    inRiproduzione = true,
                    posizioneSecondi = punto
                )
            )
        }
    }

    /**
     * Trascinamento del playhead: sposta senza cambiare stato play/pausa.
     *
     * Arriva **una volta sola, al rilascio** del dito (vedi `Timeline.kt`), non
     * a ogni movimento: per questo qui si può ricaricare la traccia senza
     * rischiare di farlo cento volte in una trascinata.
     */
    fun spostaCursore(tracciaId: String, secondi: Float) {
        val traccia = traccia(tracciaId) ?: return
        val durata = traccia.durataSecondi.toFloat()
        val nuova = if (durata > 0f) secondi.coerceIn(0f, durata) else secondi.coerceAtLeast(0f)

        // Terza occasione buona, dopo la pausa e il salto a un commento: anche
        // trascinare interrompe l'ascolto, quindi se il file nel frattempo è
        // arrivato sul telefono si riparte da lì.
        val r = _state.value.riproduzione
        if (r.tracciaId == tracciaId && r.inRiproduzione && sorgenteSuperata(tracciaId)) {
            avvia(tracciaId, nuova)
            return
        }

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
        caricataDaFile = false

        // Il servizio deve sapere cosa sta suonando **prima** che parta l'audio:
        // è da qui che la notifica prende il titolo, e il commento rapido la
        // traccia a cui attaccarsi.
        PlayerCondiviso.segnaInAscolto(
            TracciaInAscolto(traccia.id, traccia.titolo, traccia.cartellaId)
        )
        if (traccia.daMega) {
            ServizioRiproduzione.avvia(getApplication())
            avviaDaMega(traccia, da)
        } else {
            // Le tracce dimostrative non hanno un file dietro: niente notifica,
            // che prometterebbe comandi su un audio che non esiste.
            avviaSimulata(tracciaId, traccia.durataSecondi.toFloat(), da)
        }
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
            // Se il file è già sul telefono si suona da lì: nessuna attesa, e
            // funziona anche senza rete. Il ramo locale non decifra niente —
            // su disco il file è già in chiaro.
            fileLocali[traccia.id]?.let { file ->
                if (file.exists()) {
                    player.riproduciFile(file, da, traccia.titolo)
                    tracciaCaricata = traccia.id
                    caricataDaFile = true
                    seguiPosizione(traccia.id)
                    return@launch
                }
                // Sparito da sotto i piedi: si torna in streaming.
                fileLocali.remove(traccia.id)
                salva { archivio.rimuoviDownload(traccia.id) }
                aggiornaTraccia(traccia.id) { it.copy(scaricata = false) }
            }

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Non è un errore: è un altro play che ha soppiantato questo.
                // Trattarla come un guasto di rete spegnerebbe la riproduzione
                // appena partita e mostrerebbe all'utente il testo interno di
                // una cancellazione. Deve risalire e basta.
                throw e
            } catch (e: Exception) {
                fermaRiproduzione()
                mostra(spiegaErroreDiRete(e))
                return@launch
            }

            player.riproduci(url, chiave, da, traccia.titolo)
            tracciaCaricata = traccia.id
            caricataDaFile = false
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
        caricataDaFile = false
        // Niente più audio: via anche la notifica, che prometterebbe comandi su
        // qualcosa che non c'è.
        PlayerCondiviso.segnaInAscolto(null)
        ServizioRiproduzione.ferma(getApplication())
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

    /**
     * Il player ha cominciato o smesso di suonare, per qualunque motivo.
     *
     * È l'unico posto che sa davvero se il suono sta uscendo, quindi è da qui
     * che [StatoRiproduzione.audioAttivo] prende il valore: il ciclo di
     * [seguiPosizione] lo conferma a ogni tick, non lo stabilisce.
     *
     * **Un `isPlaying` a false non è per forza una pausa.** Lo è anche durante
     * un caricamento e nel mezzo di un `seek`, e le due cose vogliono reazioni
     * opposte — a una pausa il ciclo si spegne, a un caricamento no, perché fra
     * un istante l'audio riparte. `vuoleSuonare` è quello che le distingue.
     *
     * Prima la funzione usciva subito se `inRiproduzione` era già allineato, e
     * lì stava il difetto: saltando a un commento, il `seek` faceva passare il
     * player per "fermo" per un attimo e questa spegneva il ciclo; poi
     * `riproduciDa` riscriveva `inRiproduzione = true` dallo stato che aveva
     * letto **prima**. Alla ripartenza vera lo stato risultava già a posto e si
     * usciva senza riaccendere niente: il ciclo restava morto, `audioAttivo`
     * congelato a false, e il tasto girava all'infinito su una traccia che
     * stava suonando.
     */
    private fun allineaAlPlayer(suona: Boolean) {
        val r = _state.value.riproduzione
        val id = r.tracciaId ?: return

        // Dopo un ferma() il player non suona più ma resta "vorrebbe": senza
        // questa uscita il ciclo appena spento ripartirebbe da solo.
        if (!suona && !r.inRiproduzione) {
            _state.update { it.copy(riproduzione = it.riproduzione.copy(audioAttivo = false)) }
            return
        }

        val inPausaVera = !suona && !player.vuoleSuonare

        _state.update { s ->
            s.copy(
                riproduzione = s.riproduzione.copy(
                    audioAttivo = suona,
                    inRiproduzione = !inPausaVera
                )
            )
        }

        if (inPausaVera) {
            playJob?.cancel()
            playJob = null
            salvaTracciaSuDisco(id)
            return
        }

        // Si suona, o si sta per: il ciclo che riporta la posizione dev'essere
        // vivo. Può non esserlo — un seek che ha attraversato una pausa
        // tecnica, o un play arrivato dalla notifica — e da fuori un ciclo
        // morto si vede come un cursore fermo su un audio che va avanti.
        if (playJob?.isActive != true) {
            playJob = viewModelScope.launch { seguiPosizione(id) }
        }
    }

    /**
     * Rilegge da Room i commenti di una traccia.
     *
     * Serve per quelli scritti dalla notifica: li ha scritti un'altra parte del
     * processo, e questa copia in memoria non ne sa niente.
     */
    private fun ricaricaCommenti(tracciaId: String) {
        viewModelScope.launch {
            val freschi = archivio.tracce().find { it.id == tracciaId }?.commenti ?: return@launch
            aggiornaTraccia(tracciaId, persisti = false) { it.copy(commenti = freschi) }
            // Il commento nasce già LOCALE: il badge del tasto Sincronizza deve
            // contarlo, o resterebbe indietro di uno.
            _state.update { it.copy(pendenti = archivio.pendenti()) }
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
    //
    // C'è **una coda sola**, e passa di lì tutto quello che si scarica: il
    // tasto sulla singola traccia e lo "Scarica tutte" accodano, non scaricano.
    //
    // Prima erano due meccanismi paralleli — `avviaDownload` con la sua mappa di
    // job, `avviaBulk` con la sua coda — ognuno con il proprio stato e le
    // proprie regole di pausa. Separatamente funzionavano; ogni guaio nasceva
    // dove si toccavano, e ogni rattoppo ne scopriva un altro, perché domande
    // come "se fermo una singola si ferma anche la coda?" non avevano una
    // risposta: dipendeva da chi arrivava primo. Con una coda sola non ci sono
    // più due strade da far coesistere, e quelle domande hanno una risposta per
    // costruzione.
    //
    // Una traccia alla volta, come fanno le app di musica: su una linea lenta
    // dieci download in parallelo si dividono la banda e finiscono tutti tardi,
    // mentre in fila la prima è ascoltabile quasi subito. In più MEGA è un
    // servizio pubblico, e molte connessioni insieme sono il modo migliore per
    // farsi rallentare.

    /**
     * Gli id in attesa, in ordine di arrivo. Il primo è il prossimo a partire.
     *
     * Non contiene quello in corso: quello è [scaricamentoCorrente].
     */
    private var coda = listOf<String>()

    /** La traccia che sta scaricando adesso, `null` se la coda è ferma. */
    private var scaricamentoCorrente: String? = null

    /** Lo scaricamento in corso, da poter fermare senza fermare la coda. */
    private var jobCorrente: Job? = null

    /** Il ciclo che smaltisce la coda. Ce n'è al massimo uno. */
    private var workerCoda: Job? = null

    /** Un giro di sincronizzazione alla volta: due insieme si pesterebbero i piedi. */
    private var sincronizzando = false

    /**
     * Il tasto download della traccia. Cinque significati, uno per stato.
     *
     * Nessuno di questi avvia un download davvero: mettono o tolgono dalla
     * coda, e a scaricare ci pensa [assicuraWorker].
     */
    fun cambiaDownload(tracciaId: String) {
        val traccia = traccia(tracciaId) ?: return

        when (_state.value.scaricamenti[tracciaId]?.fase) {
            // Sta scaricando -> pausa. Il parziale resta dov'è e la coda
            // prosegue con la prossima: fermare una traccia vuol dire fermare
            // quella, non tutto il resto.
            FaseDownload.CORSO -> fermaScaricamento(tracciaId)

            // In fila e non ancora partita -> esce dalla fila.
            FaseDownload.ATTESA -> {
                coda = coda - tracciaId
                segnaFase(tracciaId, FaseDownload.PAUSA)
            }

            // Ferma a metà -> torna in fila.
            FaseDownload.PAUSA -> accoda(listOf(tracciaId))

            null -> when {
                traccia.scaricata -> rimuoviDalTelefono(traccia)
                traccia.daMega -> accoda(listOf(tracciaId))
                else -> Unit
            }
        }
    }

    /**
     * Mette in fila quello che non c'è già, e sveglia il worker.
     *
     * Le tracce già scaricate o già in fila non si ripetono: la coda non deve
     * poter contenere due volte la stessa traccia, o si scaricherebbe due volte
     * sullo stesso `.parziale`.
     */
    private fun accoda(ids: List<String>) {
        val nuovi = ids.filter { id ->
            id != scaricamentoCorrente &&
                id !in coda &&
                traccia(id)?.let { it.daMega && !it.scaricata } == true
        }
        if (nuovi.isEmpty()) return

        coda = coda + nuovi
        _state.update { s ->
            s.copy(
                scaricamenti = s.scaricamenti + nuovi.associateWith { id ->
                    // La percentuale di partenza è quella già sul disco: una
                    // traccia rimessa in fila dopo una pausa non deve tornare a
                    // zero solo perché è tornata in attesa.
                    StatoScaricamento(frazioneGiaPresa(id), FaseDownload.ATTESA)
                }
            )
        }
        assicuraWorker()
    }

    /** Ferma lo scaricamento in corso lasciando sul posto quello che ha preso. */
    private fun fermaScaricamento(tracciaId: String) {
        segnaFase(tracciaId, FaseDownload.PAUSA)
        if (scaricamentoCorrente == tracciaId) jobCorrente?.cancel()
    }

    /**
     * Il ciclo che smaltisce la coda, uno alla volta.
     *
     * Vive finché c'è qualcosa da fare e poi si spegne: non è un servizio
     * sempre acceso, è il consumatore della coda. Chi accoda lo risveglia.
     *
     * Il ciclo si spegne quando trova la coda vuota, e fra quel controllo e
     * l'azzeramento di `workerCoda` non c'è nessuna sospensione: [accoda] non
     * può infilarsi in mezzo e trovare un worker "vivo" che sta per morire,
     * lasciando la coda senza nessuno che la smaltisca. Regge perché entrambi
     * girano sul dispatcher principale di `viewModelScope` — spostare uno dei
     * due su un thread di fondo riaprirebbe quella finestra.
     */
    private fun assicuraWorker() {
        if (workerCoda?.isActive == true) return

        workerCoda = viewModelScope.launch {
            while (true) {
                val id = coda.firstOrNull() ?: break
                coda = coda.drop(1)

                val traccia = traccia(id)
                if (traccia == null || traccia.scaricata) {
                    _state.update { it.copy(scaricamenti = it.scaricamenti - id) }
                    continue
                }

                scaricamentoCorrente = id
                segnaFase(id, FaseDownload.CORSO)

                // Il download è un figlio a parte, non il corpo del ciclo:
                // mettendo in pausa *questa* traccia si cancella lui, e la coda
                // resta viva per passare alla prossima.
                val figlio = viewModelScope.async { scaricaUna(traccia) }
                jobCorrente = figlio
                val errore = try {
                    figlio.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Pausa chiesta dall'utente: lo stato l'ha già scritto chi
                    // l'ha chiesta, e la coda deve proseguire. Non si rilancia,
                    // o si porterebbe dietro il ciclo che invece deve vivere.
                    ensureActive()
                    null
                } finally {
                    scaricamentoCorrente = null
                    jobCorrente = null
                }

                if (errore != null) {
                    segnaFase(id, FaseDownload.PAUSA)
                    mostra(errore)
                    // Senza linea le prossime fallirebbero tutte allo stesso
                    // modo: si svuota la coda invece di sfilare cinque errori
                    // uno dietro l'altro. Le tracce restano in pausa, pronte.
                    if (errore == SENZA_RETE) {
                        val rimaste = coda
                        coda = emptyList()
                        rimaste.forEach { segnaFase(it, FaseDownload.PAUSA) }
                        break
                    }
                }
            }
            workerCoda = null
        }
    }

    /** Cambia la fase di una traccia lasciando dov'è la percentuale. */
    private fun segnaFase(tracciaId: String, fase: FaseDownload) {
        _state.update { s ->
            val corrente = s.scaricamenti[tracciaId]
                ?: StatoScaricamento(frazioneGiaPresa(tracciaId), fase)
            s.copy(scaricamenti = s.scaricamenti + (tracciaId to corrente.copy(fase = fase)))
        }
    }

    /** Quanto di questa traccia è già sul disco, come frazione da 0 a 1. */
    private fun frazioneGiaPresa(tracciaId: String): Float {
        val peso = traccia(tracciaId)?.dimensioneByte ?: 0L
        if (peso <= 0L) return 0f
        val presi = scaricatore.byteGiaPresi(File(cartellaAudio(), "$tracciaId.audio"))
        return (presi.toFloat() / peso).coerceIn(0f, 1f)
    }

    private fun rimuoviDalTelefono(traccia: Traccia) {
        // Se è proprio quella che sta suonando, il player ha il file aperto:
        // cancellarlo e basta lo lasciava a leggere qualcosa che non c'era più.
        // Si riparte da MEGA dallo stesso punto, senza che si senta niente.
        val riproduzione = _state.value.riproduzione
        val stavaSuonando = riproduzione.tracciaId == traccia.id && riproduzione.inRiproduzione
        val punto = riproduzione.posizioneSecondi

        fileLocali.remove(traccia.id)
        salva { archivio.rimuoviDownload(traccia.id) }
        aggiornaTraccia(traccia.id) { it.copy(scaricata = false) }

        if (stavaSuonando) {
            avvia(traccia.id, punto)
            mostra("Rimossa dal telefono — proseguo in streaming")
        } else {
            mostra("Rimossa dal telefono — tornerà in streaming")
        }
    }

    /**
     * Scarica una traccia. Restituisce `null` se è finita, altrimenti il
     * messaggio d'errore da mostrare.
     */
    private suspend fun scaricaUna(traccia: Traccia): String? {
        if (!traccia.daMega) return null

        val chiave = chiaviFile[traccia.id]
        val link = _state.value.cartelle
            .find { it.id == traccia.cartellaId }
            ?.linkMega
            ?.let { LinkMega.parse(it) }

        if (chiave == null || link == null) {
            return "Ricollega la cartella per poter scaricare questa traccia"
        }

        val destinazione = File(cartellaAudio(), "${traccia.id}.audio")

        return try {
            scaricatore.scarica(
                link,
                traccia.idFileMega,
                chiave,
                destinazione,
                traccia.dimensioneByte
            ) { frazione ->
                _state.update { s ->
                    // **Il progresso aggiorna solo il numero, mai la fase.**
                    //
                    // `cancel()` è cooperativo e non interrompe una `read()` già
                    // in volo: dopo la richiesta di pausa arriva ancora un
                    // aggiornamento, e riscrivendo la fase rimetteva la traccia
                    // in "sta scaricando" un istante dopo che l'utente l'aveva
                    // fermata. Serviva un secondo tap per vedere l'effetto del
                    // primo.
                    //
                    // La fase appartiene a chi dà i comandi, non a chi riporta
                    // i byte.
                    val corrente = s.scaricamenti[traccia.id] ?: return@update s
                    s.copy(
                        scaricamenti = s.scaricamenti +
                            (traccia.id to corrente.copy(frazione = frazione))
                    )
                }
            }
            fileLocali[traccia.id] = destinazione
            salva { archivio.registraDownload(traccia.id, destinazione) }
            aggiornaTraccia(traccia.id) {
                it.copy(scaricata = true, downloadEvents = it.downloadEvents + 1)
            }
            _state.update { it.copy(scaricamenti = it.scaricamenti - traccia.id) }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // La pausa passa di qui: lo stato l'ha già scritto chi l'ha chiesta.
            throw e
        } catch (e: Throwable) {
            // Un trasferimento che si pianta non è "sei senza linea": la linea
            // c'era, e soprattutto metà file è già sul telefono. Dire la frase
            // generica qui farebbe temere di dover ricominciare da capo.
            if (trasferimentoFermo(e)) {
                "Il trasferimento si è fermato. Riprendi per continuare da dove eravamo."
            } else {
                spiegaErroreDiRete(e)
            }
        }
    }

    /**
     * Traduce un guaio di rete in una frase che dica anche cosa fare.
     *
     * Senza linea non si può fare niente di utile lì per lì, ma si può dire la
     * cosa che serve davvero: l'audio in streaming vive su MEGA, quello scaricato
     * no. Chi resta a piedi una volta impara a scaricare prima.
     *
     * Si guarda il tipo dell'eccezione e non lo stato della connessione: leggere
     * lo stato richiederebbe `ACCESS_NETWORK_STATE`, e comunque una rete "attiva"
     * dietro un portale captive fallisce esattamente come una assente.
     */
    private fun spiegaErroreDiRete(e: Throwable): String = when {
        senzaRete(e) -> SENZA_RETE
        e is MegaException -> e.message ?: "MEGA non risponde."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Qualcosa è andato storto."
    }

    private fun cartellaAudio(): File =
        File(getApplication<Application>().cacheDir, "audio").also { it.mkdirs() }

    /**
     * Il tasto in cima alla cartella: accoda tutto quello che manca, oppure
     * ferma tutto se qualcosa è già in ballo.
     *
     * Non è "il tasto della coda" contrapposto a quelli delle singole tracce:
     * la coda è una sola, e questo ne è la scorciatoia per riempirla o
     * svuotarla. Per questo diventa "Ferma tutte" anche quando l'unica cosa in
     * ballo è un download avviato a mano — sono la stessa fila.
     */
    fun scaricaTutte(cartellaId: String) {
        val tracce = traccePerCartella(cartellaId)
        val attive = tracce.filter { t ->
            _state.value.scaricamenti[t.id]?.fase.let {
                it == FaseDownload.CORSO || it == FaseDownload.ATTESA
            }
        }

        if (attive.isNotEmpty()) {
            attive.forEach { t ->
                coda = coda - t.id
                fermaScaricamento(t.id)
                segnaFase(t.id, FaseDownload.PAUSA)
            }
            mostra("Scaricamento in pausa")
            return
        }

        val mancanti = tracce.filterNot { it.scaricata }
        if (mancanti.isEmpty()) {
            mostra("Sono già tutte sul telefono")
            return
        }
        accoda(mancanti.map { it.id })
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

        aggiornaCollegamento {
            it.copy(
                inCorso = true,
                errore = null,
                cartellaInAggiornamento = if (giaCollegata) linkMega.folderId else null
            )
        }

        viewModelScope.launch {
            val esito = runCatching { megaApi.elencaFileAudio(linkMega) }

            esito.onSuccess { risultato ->
                val file = risultato.audio
                if (file.isEmpty()) {
                    aggiornaCollegamento {
                        it.copy(
                            inCorso = false,
                            errore = spiegaElencoVuoto(risultato),
                            cartellaInAggiornamento = null
                        )
                    }
                    return@launch
                }

                val (cartella, nuoveTracce) = costruisciDaMega(
                    link = link,
                    linkMega = linkMega,
                    risultato = risultato,
                    aggiuntoDa = _state.value.identita?.appUid.orEmpty()
                )

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
                            chiediNome = !giaCollegata && risultato.nomeCartella.isNullOrBlank(),
                            cartellaInAggiornamento = null
                        )
                    )
                }
                salva {
                    archivio.salvaCartella(cartella)
                    archivio.sostituisciTracce(cartella.id, nuoveTracce, chiaviFile)
                }
                mostra(
                    if (giaCollegata) "\"${cartella.nome}\" aggiornata — ${file.size} tracce"
                    else "\"${cartella.nome}\" collegata — ${file.size} tracce"
                )
            }

            esito.onFailure { errore ->
                aggiornaCollegamento {
                    it.copy(
                        inCorso = false,
                        errore = (errore as? MegaException)?.message
                            ?: "Non riesco a leggere la cartella. Controlla la connessione.",
                        cartellaInAggiornamento = null
                    )
                }
            }
        }
    }

    /**
     * Da un elenco MEGA alla cartella e alle tracce, senza toccare lo stato.
     *
     * Sta fuori da [collegaCartella] perché serve due volte: quando colleghi un
     * link a mano, e quando all'avvio si seminano le cartelle di prova. È il
     * pezzo che sa fondere quello che dice MEGA con quello che sappiamo già —
     * l'unico che non va scritto due volte, o le due strade divergerebbero al
     * primo ritocco.
     *
     * Effetto collaterale voluto: registra le chiavi in [chiaviFile]. Arrivano
     * solo con l'elenco, e senza non si potrebbe decifrare niente al play.
     */
    private fun costruisciDaMega(
        link: String,
        linkMega: LinkMega,
        risultato: EsitoElenco,
        aggiuntoDa: String
    ): Pair<Cartella, List<Traccia>> {
        val file = risultato.audio
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
            aggiuntoDa = aggiuntoDa,
            numTracce = file.size
        )
        file.forEach { chiaviFile[it.handle] = it.chiave }

        // Le tracce già presenti, per handle: l'id di una traccia è il node
        // handle di MEGA e non cambia mai, quindi si riconoscono.
        val esistenti = _state.value.tracce
            .filter { it.cartellaId == cartella.id }
            .associateBy { it.id }

        val tracce = file.sortedWith(compareBy(OrdineNaturale) { it.nome }).map { f ->
            // Il nome su MEGA include l'estensione: nell'elenco è rumore.
            val titoloDaMega = f.nome.substringBeforeLast('.', f.nome)
            val precedente = esistenti[f.handle]

            if (precedente == null) {
                Traccia(
                    id = f.handle,
                    cartellaId = cartella.id,
                    titolo = titoloDaMega,
                    idFileMega = f.handle,
                    dimensioneByte = f.dimensioneByte,
                    // 0 = ancora ignota. La durata sta dentro il file audio,
                    // che a questo punto non abbiamo ancora aperto.
                    durataSecondi = 0
                )
            } else {
                // Ricaricare la cartella rilegge MEGA, e MEGA sa solo come si
                // chiama il file e quanto pesa. Stelline, rinomine, durata,
                // ascolti e commenti sono roba nostra: ripartire da zero
                // vorrebbe dire cancellare il lavoro di chi usa l'app.
                precedente.copy(
                    // Un titolo scelto a mano vince su quello del file. Se
                    // invece non era mai stato toccato, si aggiorna: il file
                    // potrebbe essere stato rinominato su MEGA.
                    titolo = if (precedente.titolo == titoloDaMega) titoloDaMega
                    else precedente.titolo,
                    cartellaId = cartella.id,
                    idFileMega = f.handle,
                    // Il peso lo dice MEGA, sempre: se il file è stato
                    // risostituito là, quello vecchio non vale più.
                    dimensioneByte = f.dimensioneByte
                )
            }
        }

        return cartella to tracce
    }

    /**
     * Collega le cartelle di prova e ci mette sopra commenti e voti finti.
     *
     * Succede una volta per installazione. Il segno che è successo sta fuori
     * dall'archivio ([DatiDiProvaStore]) apposta: se stesse nelle cartelle,
     * scollegarne una la farebbe tornare al riavvio dopo, e non si potrebbe più
     * provare lo scollegamento.
     *
     * Senza linea non semina niente **e non segna niente**: ci si riprova al
     * prossimo avvio. Un banco di prova nato a metà è peggio di uno che nasce
     * al secondo tentativo — in mezzo ci sarebbero cartelle senza tracce, che
     * somigliano troppo a un bug per essere utili.
     */
    private suspend fun seminaDatiDiProva() {
        if (datiDiProva.giaSeminati()) return

        for (prova in DatiDiProva.cartelle) {
            val linkMega = LinkMega.parse(prova.linkMega) ?: continue
            val risultato = runCatching { megaApi.elencaFileAudio(linkMega) }.getOrNull()
            if (risultato == null || risultato.audio.isEmpty()) return

            val (cartella, tracce) = costruisciDaMega(
                link = prova.linkMega,
                linkMega = linkMega,
                risultato = risultato,
                // Le cartelle di prova arrivano da qualcun altro: è il punto di
                // partenza che si vuole simulare, "il gruppo ha già lavorato".
                aggiuntoDa = DatiDiProva.Autore.MARCO.appUid
            )

            val commenti = prova.commenti.mapIndexedNotNull { i, finto ->
                // La posizione è 1-based e può puntare oltre l'ultimo file:
                // quante tracce ci siano davvero lo dice MEGA, non questo file.
                val traccia = tracce.getOrNull(finto.traccia - 1) ?: return@mapIndexedNotNull null
                Commento(
                    // Deterministico: riseminando non si duplica niente.
                    id = "prova-${cartella.id}-$i",
                    tracciaId = traccia.id,
                    appUid = finto.autore.appUid,
                    autoreNome = finto.autore.nomeVisibile,
                    autoreColore = finto.autore.colore,
                    timestampSecondi = finto.secondi.toFloat(),
                    testo = finto.testo,
                    statoSync = StatoSync.SINCRONIZZATO
                )
            }
            val commentiPerTraccia = commenti.groupBy { it.tracciaId }

            val arredate = tracce.mapIndexed { indice, traccia ->
                val voto = prova.voti.find { it.traccia == indice + 1 }
                traccia.copy(
                    mioVoto = voto?.mio ?: traccia.mioVoto,
                    votiPieni = voto?.pieni ?: traccia.votiPieni,
                    votiMezzi = voto?.mezzi ?: traccia.votiMezzi,
                    ascolti = voto?.ascolti ?: traccia.ascolti,
                    playBuckets = voto?.let { DatiDiProva.buckets(it.ascolti) }
                        ?: traccia.playBuckets,
                    commenti = commentiPerTraccia[traccia.id].orEmpty()
                )
            }

            _state.update { stato ->
                stato.copy(
                    cartelle = stato.cartelle.filterNot { it.id == cartella.id } + cartella,
                    tracce = stato.tracce.filterNot { it.cartellaId == cartella.id } + arredate
                )
            }
            // SINCRONIZZATO, non LOCALE: nulla di finto deve finire nel badge
            // del tasto Sincronizza, e tantomeno su Firestore.
            salva {
                archivio.salvaCartella(cartella, StatoSync.SINCRONIZZATO)
                archivio.sostituisciTracce(
                    cartella.id, arredate, chiaviFile, StatoSync.SINCRONIZZATO
                )
                commenti.forEach { archivio.salvaCommento(it) }
            }
        }

        datiDiProva.segnaSeminati()
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
        fileLocali.clear()
        // ...comprese le cartelle di prova, che sono dati locali come gli altri.
        datiDiProva.dimentica()
        _state.update {
            it.copy(
                identita = null,
                schermata = Schermata.Gate,
                cartelle = emptyList(),
                tracce = emptyList()
            )
        }
        riparti("Dispositivo reimpostato — ricollego le cartelle di prova.")
    }

    /**
     * "Riparti dai dati di prova": butta tutto quello che hai fatto tu e
     * ricostruisce il banco di prova.
     *
     * Non c'è nessun elenco di "cose mie" da tenere separate dalle "cose del
     * seme", ed è il punto: il seme si ricostruisce da codice, quindi
     * cancellare tutto e riseminare dà lo stesso risultato di una cancellazione
     * selettiva, senza uno stato in più da tenere allineato.
     */
    fun svuotaCloudSimulato() {
        profiliStore.svuota()
        identityManager.dimentica()
        mettiInPausa()
        chiaviFile.clear()
        fileLocali.clear()
        // profiliStore.svuota() ha già cancellato il segno del seme: stanno
        // nelle stesse preferenze apposta. Qui è solo per non dipendere da
        // quel dettaglio.
        datiDiProva.dimentica()
        _state.update { AppState(temaScuro = it.temaScuro) }
        riparti("Tutto azzerato — ricollego le cartelle di prova.")
    }

    /** Svuota l'archivio e rimette il banco di prova, in quest'ordine. */
    private fun riparti(messaggio: String) {
        mostra(messaggio)
        viewModelScope.launch {
            archivio.svuota()
            seminaDatiDiProva()
            _state.update { it.copy(pendenti = archivio.pendenti()) }
        }
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
        workerCoda?.cancel()
        // Senza questa il player continuerebbe a tenersi socket e decoder.
        player.scollega()
        // `scrittura` non si annulla apposta: una scrittura partita un istante
        // prima della chiusura deve arrivare in fondo, è tutto il suo scopo.
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
        const val SOGLIA_ASCOLTO = 30f
        const val BUCKETS = 24

        /**
         * Una frase sola per tutti i punti in cui manca la linea: play, download,
         * collegamento. Ripeterla identica la rende riconoscibile.
         */
        const val SENZA_RETE =
            "Sei senza rete. L'audio si ascolta da MEGA: scarica le tracce sul " +
                "telefono quando hai linea e poi le hai anche offline."
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
