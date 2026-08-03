package com.example.registrazio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.registrazio.data.DemoData
import com.example.registrazio.data.local.ProfiliStore
import com.example.registrazio.data.remote.EsitoElenco
import com.example.registrazio.data.remote.LinkMega
import com.example.registrazio.data.remote.MegaApi
import com.example.registrazio.data.remote.MegaException
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.model.Utente
import com.example.registrazio.domain.identity.IdentityManager
import com.example.registrazio.util.OrdineNaturale
import kotlinx.coroutines.Job
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
    val posizioneSecondi: Float = 0f
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
    val collegamento: StatoCollegamento = StatoCollegamento()
)

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
    val chiediNome: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val identityManager = IdentityManager(app)
    private val profiliStore = ProfiliStore(app)
    private val megaApi = MegaApi()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var playJob: Job? = null
    private var bulkJob: Job? = null
    private var seqMessaggi = 0L

    /** Secondi ascoltati di fila nella sessione corrente, per il conteggio degli ascolti. */
    private var ascoltoAccumulato = 0f
    private var ascoltoGiaContato = false

    init {
        val identita = identityManager.identita
        _state.update {
            it.copy(
                identita = identita,
                schermata = if (identita != null) Schermata.Home else Schermata.Gate,
                cartelle = DemoData.cartelle + profiliStore.cartelle(),
                tracce = DemoData.tracce,
                profiliDisponibili = profiliStore.profili()
            )
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
        return null
    }

    /** Percorso B del Gate: l'utente ha riconosciuto il proprio avatar. */
    fun entraComeUtente(utente: Utente) {
        identityManager.adottaIdentita(utente)
        _state.update { it.copy(identita = utente, schermata = Schermata.Home) }
        mostra("Bentornato, ${utente.nome}!")
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

    fun aggiorna() = mostra("Aggiornato — nessuna novità")

    // ---------- riproduzione ----------

    fun togglePlay(tracciaId: String) {
        val r = _state.value.riproduzione
        if (r.tracciaId == tracciaId && r.inRiproduzione) mettiInPausa()
        else avvia(tracciaId, if (r.tracciaId == tracciaId) r.posizioneSecondi else 0f)
    }

    /** Salta a un punto e riparte: usato dai chip dei commenti e dal grafico dettagli. */
    fun riproduciDa(tracciaId: String, secondi: Float) = avvia(tracciaId, secondi)

    /** Trascinamento del playhead: sposta senza cambiare stato play/pausa. */
    fun spostaCursore(tracciaId: String, secondi: Float) {
        val durata = traccia(tracciaId)?.durataSecondi?.toFloat() ?: return
        _state.update {
            it.copy(
                riproduzione = it.riproduzione.copy(
                    tracciaId = tracciaId,
                    posizioneSecondi = secondi.coerceIn(0f, durata)
                )
            )
        }
    }

    private fun avvia(tracciaId: String, da: Float) {
        val durata = traccia(tracciaId)?.durataSecondi?.toFloat() ?: return
        playJob?.cancel()
        ascoltoAccumulato = 0f
        ascoltoGiaContato = false
        _state.update {
            it.copy(
                riproduzione = StatoRiproduzione(
                    tracciaId = tracciaId,
                    inRiproduzione = true,
                    posizioneSecondi = da.coerceIn(0f, durata)
                )
            )
        }
        // Finto avanzamento a 250ms come nel prototipo: ExoPlayer prenderà
        // il posto di questo loop senza che la UI debba cambiare.
        playJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                avanzaDiUnTick(tracciaId, durata)
            }
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
                riproduzione = r.copy(posizioneSecondi = pos),
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
        _state.update { it.copy(riproduzione = it.riproduzione.copy(inRiproduzione = false)) }
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
        mostra("Commento aggiornato")
    }

    fun eliminaCommento(tracciaId: String, commentoId: String) {
        aggiornaTraccia(tracciaId) { t -> t.copy(commenti = t.commenti.filterNot { it.id == commentoId }) }
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
                profiliStore.registraCartella(cartella)

                // MEGA restituisce i nodi senza un ordine utile: senza questo le
                // tracce comparirebbero sparse.
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

    private fun aggiornaCollegamento(blocco: (StatoCollegamento) -> StatoCollegamento) =
        _state.update { it.copy(collegamento = blocco(it.collegamento)) }

    fun rimuoviCartella(cartellaId: String) {
        val nome = _state.value.cartelle.find { it.id == cartellaId }?.nome ?: return
        profiliStore.rimuoviCartella(cartellaId)
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
        // prima chiusura dell'app. Le cartelle demo non stanno nello store e
        // vanno lasciate fuori, o al riavvio comparirebbero doppie.
        _state.value.cartelle.find { it.id == cartellaId }?.let { cartella ->
            if (profiliStore.cartelle().any { it.id == cartellaId }) {
                profiliStore.registraCartella(cartella)
            }
        }
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

    private fun aggiornaTraccia(id: String, blocco: (Traccia) -> Traccia) {
        _state.update { s ->
            s.copy(tracce = s.tracce.map { if (it.id == id) blocco(it) else it })
        }
    }

    override fun onCleared() {
        playJob?.cancel()
        bulkJob?.cancel()
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
