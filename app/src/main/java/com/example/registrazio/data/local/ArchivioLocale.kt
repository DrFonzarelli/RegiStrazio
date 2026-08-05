package com.example.registrazio.data.local

import android.content.Context
import com.example.registrazio.data.DatiDiProva
import com.example.registrazio.data.local.db.ArchivioDb
import com.example.registrazio.data.local.db.CartellaEntity
import com.example.registrazio.data.local.db.CommentoEntity
import com.example.registrazio.data.local.db.ConteggioPendenti
import com.example.registrazio.data.local.db.DownloadEntity
import com.example.registrazio.data.local.db.TracciaEntity
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.StatoSync
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.remote.MegaCrypto
import java.io.File

/**
 * L'archivio sul telefono: cartelle, tracce e commenti, con lo stato di
 * sincronizzazione di ognuno.
 *
 * È qui che l'app legge all'avvio e scrive a ogni modifica. Firestore non
 * viene mai interrogato per sapere "cosa c'è": lo dice questo archivio. La
 * sincronizzazione è un'operazione separata, che l'utente chiede.
 */
class ArchivioLocale(context: Context) {

    private val dao = ArchivioDb.apri(context).dao()

    // ---------- lettura all'avvio ----------

    suspend fun cartelle(): List<Cartella> = dao.cartelle().map { it.aModello() }

    /** Le tracce con i loro commenti già attaccati, come le vuole l'interfaccia. */
    suspend fun tracce(): List<Traccia> {
        val commentiPerTraccia = dao.commenti().groupBy { it.tracciaId }
        return dao.tracce().map { entita ->
            entita.aModello(
                commentiPerTraccia[entita.id].orEmpty().map { it.aModello() }
            )
        }
    }

    /** Chiavi dei file, per poter premere play senza rileggere MEGA. */
    suspend fun chiaviFile(): Map<String, MegaCrypto.ChiaveFile> =
        dao.tracce().mapNotNull { entita ->
            val aes = entita.chiaveAes ?: return@mapNotNull null
            val nonce = entita.nonce ?: return@mapNotNull null
            entita.id to MegaCrypto.ChiaveFile(aes, nonce)
        }.toMap()

    /**
     * Le tracce scaricate, per id, con il percorso del file.
     *
     * Le righe che puntano a un file non più sul disco vengono buttate: può
     * essere stato cancellato da fuori (pulizia della cache, gestore file), e
     * tenerle vorrebbe dire provare a suonare qualcosa che non c'è.
     */
    suspend fun download(): Map<String, File> {
        val vivi = mutableMapOf<String, File>()
        for (riga in dao.download()) {
            val file = File(riga.percorso)
            if (file.exists() && file.length() > 0) vivi[riga.tracciaId] = file
            else dao.cancellaDownload(riga.tracciaId)
        }
        return vivi
    }

    suspend fun registraDownload(tracciaId: String, file: File) {
        dao.salvaDownload(
            DownloadEntity(
                tracciaId = tracciaId,
                percorso = file.absolutePath,
                dimensioneByte = file.length(),
                scaricatoIl = System.currentTimeMillis()
            )
        )
    }

    /** Toglie il file dal telefono: al play successivo si torna in streaming. */
    suspend fun rimuoviDownload(tracciaId: String) {
        dao.download(tracciaId)?.let { File(it.percorso).delete() }
        dao.cancellaDownload(tracciaId)
    }

    suspend fun pendenti(): ConteggioPendenti = ConteggioPendenti(
        cartelle = dao.cartelleDaSincronizzare(),
        tracce = dao.tracceDaSincronizzare(),
        commenti = dao.commentiDaSincronizzare()
    )

    // ---------- scrittura ----------

    suspend fun salvaCartella(cartella: Cartella, stato: StatoSync = StatoSync.LOCALE) {
        dao.salvaCartella(cartella.aEntita(stato))
    }

    /**
     * Sostituisce in blocco le tracce di una cartella.
     *
     * Rileggendo la cartella da MEGA, un file sparito da lì deve sparire anche
     * qui. I commenti restano: sono agganciati all'id della traccia, che è il
     * node handle di MEGA e non cambia.
     *
     * @param stato da forzare su tutte le righe. Lasciandolo `null` — il caso
     *   normale — ogni riga **conserva lo stato che aveva**, e passa a `LOCALE`
     *   solo se qualcosa è davvero cambiato.
     *
     * Quel dettaglio decide se il contatore dei pendenti torna a zero. Il tasto
     * Sincronizza rilegge le cartelle da MEGA a ogni giro, e marcando tutto
     * `LOCALE` si ritroverebbe l'intero catalogo da ricaricare ogni volta —
     * lavoro creato dalla sincronizzazione stessa, che al giro dopo se lo
     * ritrova davanti daccapo.
     */
    suspend fun sostituisciTracce(
        cartellaId: String,
        tracce: List<Traccia>,
        chiavi: Map<String, MegaCrypto.ChiaveFile>,
        stato: StatoSync? = null
    ) {
        val prima = dao.tracce().filter { it.cartellaId == cartellaId }.associateBy { it.id }
        val nuove = tracce.map { traccia ->
            if (stato != null) return@map traccia.aEntita(chiavi[traccia.id], stato)

            val vecchia = prima[traccia.id]
            // Costruita con lo stato di prima: se il confronto torna, allora
            // *solo* lo stato sarebbe cambiato, cioè non è cambiato niente.
            val candidata = traccia.aEntita(chiavi[traccia.id], vecchia?.statoSync ?: StatoSync.LOCALE)
            if (vecchia != null && candidata == vecchia) candidata
            else candidata.copy(statoSync = StatoSync.LOCALE)
        }
        dao.cancellaTracceDi(cartellaId)
        dao.salvaTracce(nuove)
    }

    suspend fun salvaTraccia(traccia: Traccia, chiave: MegaCrypto.ChiaveFile?) {
        dao.salvaTraccia(traccia.aEntita(chiave))
    }

    suspend fun salvaCommento(commento: Commento) {
        dao.salvaCommento(commento.aEntita())
    }

    /**
     * Un commento mai arrivato su Firestore si cancella davvero; uno già
     * caricato va marcato, o tornerebbe alla prossima sincronizzazione.
     */
    suspend fun eliminaCommento(id: String) {
        dao.marcaCommentoDaEliminare(id)
        dao.cancellaCommentoMaiCaricato(id)
    }

    suspend fun rimuoviCartella(id: String) {
        // Scollegando la cartella i suoi file scaricati non servono più a
        // nessuno: lasciarli occuperebbe spazio senza modo di ritrovarli.
        for (traccia in dao.tracce().filter { it.cartellaId == id }) {
            rimuoviDownload(traccia.id)
        }
        dao.cancellaCommentiDi(id)
        dao.cancellaTracceDi(id)
        dao.marcaCartellaDaEliminare(id)
        dao.cancellaCartellaMaiCaricata(id)
    }

    // ---------- sincronizzazione ----------

    suspend fun cartelleDaCaricare(): List<Cartella> = dao.cartelleDaCaricare().map { it.aModello() }

    suspend fun tracceDaCaricare(): List<Traccia> = dao.tracceDaCaricare().map { it.aModello(emptyList()) }

    suspend fun commentiDaCaricare(): List<Commento> = dao.commentiDaCaricare().map { it.aModello() }

    suspend fun cartelleDaCancellare(): List<String> = dao.cartelleDaCancellare().map { it.id }

    /** Id commento e id della traccia che lo ospita: su Firestore serve il percorso intero. */
    suspend fun commentiDaCancellare(): List<Pair<String, String>> =
        dao.commentiDaCancellare().map { it.tracciaId to it.id }

    suspend fun segnaCartellaCaricata(id: String) = dao.segnaCartella(id, StatoSync.SINCRONIZZATO)

    suspend fun segnaTracciaCaricata(id: String) = dao.segnaTraccia(id, StatoSync.SINCRONIZZATO)

    suspend fun segnaCommentoCaricato(id: String) = dao.segnaCommento(id, StatoSync.SINCRONIZZATO)

    suspend fun segnaCartellaFallita(id: String) = dao.segnaCartella(id, StatoSync.ERRORE)

    suspend fun segnaTracciaFallita(id: String) = dao.segnaTraccia(id, StatoSync.ERRORE)

    suspend fun segnaCommentoFallito(id: String) = dao.segnaCommento(id, StatoSync.ERRORE)

    suspend fun dimenticaCartella(id: String) = dao.cancellaCartellaSincronizzata(id)

    suspend fun dimenticaCommento(id: String) = dao.cancellaCommentoSincronizzato(id)

    /**
     * Scrive una cartella arrivata da Firestore, **se qui non è stata toccata**.
     *
     * È la regola che tiene in piedi la seconda legge del progetto: il telefono
     * è la fonte di verità. Una riga in `LOCALE`, `ERRORE` o `DA_ELIMINARE`
     * porta un lavoro che non è ancora arrivato dall'altra parte, e lasciarla
     * sovrascrivere dal pull vorrebbe dire buttarlo — proprio nel momento in
     * cui l'utente ha chiesto di salvarlo.
     *
     * @return `true` se la riga è stata scritta.
     */
    suspend fun accettaDalCloud(cartella: Cartella): Boolean {
        if (dao.statoCartella(cartella.id).haLavoroInSospeso()) return false
        dao.salvaCartella(cartella.aEntita(StatoSync.SINCRONIZZATO))
        return true
    }

    /**
     * Come sopra per una traccia, con un'eccezione: [Traccia.mioVoto] non passa
     * da Firestore. È la stella di *questo* telefono, e il documento remoto non
     * la conosce nemmeno — sovrascriverla con il valore vuoto che arriva dal
     * cloud la cancellerebbe a ogni sincronizzazione.
     */
    suspend fun accettaDalCloud(traccia: Traccia, chiave: MegaCrypto.ChiaveFile?): Boolean {
        if (dao.statoTraccia(traccia.id).haLavoroInSospeso()) return false
        val voto = dao.mioVoto(traccia.id) ?: traccia.mioVoto
        dao.salvaTraccia(traccia.copy(mioVoto = voto).aEntita(chiave, StatoSync.SINCRONIZZATO))
        return true
    }

    suspend fun accettaDalCloud(commento: Commento): Boolean {
        if (dao.statoCommento(commento.id).haLavoroInSospeso()) return false
        dao.salvaCommento(commento.copy(statoSync = StatoSync.SINCRONIZZATO).aEntita())
        return true
    }

    /**
     * Toglie ciò che su Firestore non c'è più.
     *
     * Solo fra le righe già sincronizzate: una in `LOCALE` non è "sparita dal
     * cloud", non ci è mai arrivata, e cancellarla vorrebbe dire perdere un
     * commento appena scritto perché la sincronizzazione non l'ha ancora
     * caricato.
     */
    suspend fun rimuoviCommentiSpariti(idRimasti: Set<String>) {
        for (riga in dao.commenti()) {
            if (riga.statoSync != StatoSync.SINCRONIZZATO) continue
            if (riga.id in idRimasti) continue
            // I commenti del banco di prova sono `SINCRONIZZATO` per non farsi
            // caricare, non perché su Firestore ci siano. Senza questa riga il
            // primo Sincronizza li scambierebbe per commenti cancellati da
            // qualcun altro e smonterebbe il banco di prova.
            if (riga.id.startsWith(DatiDiProva.PREFISSO_ID)) continue
            dao.cancellaCommentoSincronizzatoDavvero(riga.id)
        }
    }

    /** Strumento di test del foglio account. */
    suspend fun svuota() {
        for (riga in dao.download()) File(riga.percorso).delete()
        dao.svuotaDownload()
        dao.svuotaCommenti()
        dao.svuotaTracce()
        dao.svuotaCartelle()
    }
}

/**
 * La riga porta un lavoro che Firestore non ha ancora visto.
 *
 * `null` vuol dire che la riga non c'è: quello che arriva dal cloud è nuovo e
 * si scrive senza pensarci.
 */
private fun StatoSync?.haLavoroInSospeso(): Boolean = when (this) {
    null, StatoSync.SINCRONIZZATO -> false
    StatoSync.LOCALE, StatoSync.ERRORE, StatoSync.DA_ELIMINARE -> true
}

// ---------- conversioni ----------

private fun CartellaEntity.aModello() = Cartella(
    id = id,
    nome = nome,
    linkMega = linkMega,
    megaFolderId = megaFolderId,
    aggiuntoIl = aggiuntoIl,
    aggiuntoDa = aggiuntoDa
)

private fun Cartella.aEntita(stato: StatoSync) = CartellaEntity(
    id = id,
    nome = nome,
    linkMega = linkMega,
    megaFolderId = megaFolderId,
    aggiuntoIl = aggiuntoIl,
    aggiuntoDa = aggiuntoDa,
    statoSync = stato
)

private fun TracciaEntity.aModello(commenti: List<Commento>) = Traccia(
    id = id,
    cartellaId = cartellaId,
    titolo = titolo,
    idFileMega = idFileMega,
    dimensioneByte = dimensioneByte,
    durataSecondi = durataSecondi,
    waveformData = waveformData,
    ascolti = ascolti,
    mioVoto = mioVoto,
    votiPieni = votiPieni,
    votiMezzi = votiMezzi,
    scaricata = scaricata,
    downloadEvents = downloadEvents,
    playBuckets = playBuckets,
    commenti = commenti,
    creatoIl = creatoIl
)

private fun Traccia.aEntita(
    chiave: MegaCrypto.ChiaveFile?,
    stato: StatoSync = StatoSync.LOCALE
) = TracciaEntity(
    id = id,
    cartellaId = cartellaId,
    titolo = titolo,
    idFileMega = idFileMega,
    dimensioneByte = dimensioneByte,
    durataSecondi = durataSecondi,
    ascolti = ascolti,
    mioVoto = mioVoto,
    votiPieni = votiPieni,
    votiMezzi = votiMezzi,
    scaricata = scaricata,
    downloadEvents = downloadEvents,
    playBuckets = playBuckets,
    waveformData = waveformData,
    creatoIl = creatoIl,
    chiaveAes = chiave?.aes,
    nonce = chiave?.nonce,
    // I commenti hanno una tabella loro: non fanno parte della riga traccia.
    statoSync = stato
)

private fun CommentoEntity.aModello() = Commento(
    id = id,
    tracciaId = tracciaId,
    appUid = appUid,
    autoreNome = autoreNome,
    autoreColore = autoreColore,
    timestampSecondi = timestampSecondi,
    testo = testo,
    creatoIl = creatoIl,
    statoSync = statoSync
)

private fun Commento.aEntita() = CommentoEntity(
    id = id,
    tracciaId = tracciaId,
    appUid = appUid,
    autoreNome = autoreNome,
    autoreColore = autoreColore,
    timestampSecondi = timestampSecondi,
    testo = testo,
    creatoIl = creatoIl,
    statoSync = statoSync ?: StatoSync.LOCALE
)
