package com.example.registrazio.data.local

import android.content.Context
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
     */
    suspend fun sostituisciTracce(
        cartellaId: String,
        tracce: List<Traccia>,
        chiavi: Map<String, MegaCrypto.ChiaveFile>
    ) {
        dao.cancellaTracceDi(cartellaId)
        dao.salvaTracce(tracce.map { it.aEntita(chiavi[it.id]) })
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

    /** Strumento di test del foglio account. */
    suspend fun svuota() {
        for (riga in dao.download()) File(riga.percorso).delete()
        dao.svuotaDownload()
        dao.svuotaCommenti()
        dao.svuotaTracce()
        dao.svuotaCartelle()
    }
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

private fun Traccia.aEntita(chiave: MegaCrypto.ChiaveFile?) = TracciaEntity(
    id = id,
    cartellaId = cartellaId,
    titolo = titolo,
    idFileMega = idFileMega,
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
    statoSync = StatoSync.LOCALE
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
