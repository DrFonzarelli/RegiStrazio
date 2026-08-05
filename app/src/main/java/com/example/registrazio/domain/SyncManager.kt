package com.example.registrazio.domain

import android.util.Log
import com.example.registrazio.data.local.ArchivioLocale
import com.example.registrazio.data.remote.FirestoreRepository
import com.example.registrazio.data.remote.MegaCrypto
import com.example.registrazio.data.model.Utente

/**
 * Cos'è successo in un giro di sincronizzazione, in numeri.
 *
 * Serve a dire all'utente qualcosa di più utile di "fatto": quanto è partito,
 * quanto è arrivato, e soprattutto quanto è rimasto indietro.
 */
data class EsitoSync(
    val caricati: Int = 0,
    val scaricati: Int = 0,
    val falliti: Int = 0,
    /** Compilato solo se il giro si è fermato del tutto. */
    val errore: String? = null
) {
    val riuscito: Boolean get() = errore == null
}

/**
 * Il tasto Sincronizza, per esteso.
 *
 * Fa tre cose in quest'ordine, e l'ordine conta:
 *
 * 1. **Pull** — si prende quello che hanno scritto gli altri.
 * 2. **Push** — manda quello che è stato scritto qui.
 * 3. **Cancellazioni** — propaga quello che è stato tolto qui.
 *
 * Il pull viene prima perché è l'unica parte che può fallire senza lasciare
 * danni: se cade la linea a metà, in Room resta quello che c'era e il push non
 * è nemmeno cominciato. Al contrario, un push interrotto lascia righe caricate
 * e righe no — situazione legittima, che il giro dopo sistema, ma non è il caso
 * da cui conviene partire.
 *
 * **Il pull non sovrascrive mai il lavoro locale.** Una riga in `LOCALE`,
 * `ERRORE` o `DA_ELIMINARE` porta qualcosa che il cloud non ha ancora visto:
 * accettare la versione remota vorrebbe dire buttarla via proprio nel momento
 * in cui l'utente ha chiesto di salvarla. La regola vive in
 * [ArchivioLocale.accettaDalCloud] ed è la seconda legge del progetto — il
 * telefono è la fonte di verità, Firestore la destinazione.
 *
 * Quello che **non** fa: rileggere le cartelle da MEGA. Quello resta al
 * ViewModel, che ha già il codice per farlo (`costruisciDaMega`) e le chiavi di
 * decifratura in memoria. Qui si parla solo con Firestore.
 */
class SyncManager(
    private val archivio: ArchivioLocale,
    private val firestore: FirestoreRepository
) {

    /**
     * @param chiaviFile chiavi AES per id traccia, per non perderle scrivendo
     *   una traccia arrivata dal cloud. Firestore non le conosce e non deve.
     */
    suspend fun sincronizza(
        identita: Utente?,
        chiaviFile: Map<String, MegaCrypto.ChiaveFile>
    ): EsitoSync {
        firestore.assicuraAccesso()

        var scaricati = 0
        var caricati = 0
        var falliti = 0

        // Il proprio profilo per primo: senza, chi ha creato l'account su
        // questo telefono non comparirebbe nell'elenco di recupero degli altri,
        // e i suoi commenti resterebbero firmati da uno sconosciuto.
        identita?.let {
            runCatching { firestore.salvaProfilo(it) }
                .onFailure { e -> Log.w(TAG, "profilo non caricato", e); falliti++ }
        }

        // ---------- 1. pull ----------

        val cartelleRemote = firestore.cartelle()
        for (cartella in cartelleRemote) {
            if (archivio.accettaDalCloud(cartella)) scaricati++
        }

        // Le tracce si chiedono per cartella, e per **tutte** quelle che
        // conosciamo — comprese quelle collegate qui e non ancora caricate: le
        // loro tracce potrebbero già esserci, messe da un altro membro che ha
        // collegato la stessa cartella prima.
        val idCartelle = (cartelleRemote.map { it.id } + archivio.cartelle().map { it.id }).distinct()
        for (cartellaId in idCartelle) {
            for (traccia in firestore.tracce(cartellaId)) {
                if (archivio.accettaDalCloud(traccia, chiaviFile[traccia.id])) scaricati++
            }
        }

        val commentiRemoti = firestore.tuttiICommenti()
        for (commento in commentiRemoti) {
            if (archivio.accettaDalCloud(commento)) scaricati++
        }
        // Quello che qualcun altro ha cancellato sparisce anche qui. Solo fra
        // le righe già in pari col cloud: le altre non sono "sparite", non ci
        // sono mai arrivate.
        archivio.rimuoviCommentiSpariti(commentiRemoti.map { it.id }.toSet())

        // ---------- 2. push ----------

        for (cartella in archivio.cartelleDaCaricare()) {
            runCatching { firestore.salvaCartella(cartella) }
                .onSuccess { archivio.segnaCartellaCaricata(cartella.id); caricati++ }
                .onFailure { e -> Log.w(TAG, "cartella ${cartella.id} non caricata", e); archivio.segnaCartellaFallita(cartella.id); falliti++ }
        }

        for (traccia in archivio.tracceDaCaricare()) {
            runCatching { firestore.salvaTraccia(traccia) }
                .onSuccess { archivio.segnaTracciaCaricata(traccia.id); caricati++ }
                .onFailure { e -> Log.w(TAG, "traccia ${traccia.id} non caricata", e); archivio.segnaTracciaFallita(traccia.id); falliti++ }
        }

        for (commento in archivio.commentiDaCaricare()) {
            runCatching { firestore.salvaCommento(commento) }
                .onSuccess { archivio.segnaCommentoCaricato(commento.id); caricati++ }
                .onFailure { e ->
                    Log.w(TAG, "commento ${commento.id} non caricato", e)
                    archivio.segnaCommentoFallito(commento.id); falliti++
                }
        }

        // ---------- 3. cancellazioni ----------
        //
        // Per ultime: una riga cancellata qui è già invisibile nell'interfaccia,
        // quindi nessuno sta aspettando di vederla sparire. Se il giro si
        // interrompe prima, resta marcata e riparte al prossimo.

        for ((tracciaId, commentoId) in archivio.commentiDaCancellare()) {
            runCatching { firestore.eliminaCommento(tracciaId, commentoId) }
                .onSuccess { archivio.dimenticaCommento(commentoId); caricati++ }
                .onFailure { e -> Log.w(TAG, "cancellazione non riuscita", e); falliti++ }
        }

        for (cartellaId in archivio.cartelleDaCancellare()) {
            runCatching { firestore.eliminaCartella(cartellaId) }
                .onSuccess { archivio.dimenticaCartella(cartellaId); caricati++ }
                .onFailure { e -> Log.w(TAG, "cancellazione non riuscita", e); falliti++ }
        }

        return EsitoSync(caricati = caricati, scaricati = scaricati, falliti = falliti)
    }

    /**
     * L'elenco dei profili per "Ho già un account".
     *
     * Sta qui e non nel giro grande perché serve **prima** di avere
     * un'identità, cioè nel Gate — dove non c'è ancora niente da
     * sincronizzare.
     */
    suspend fun profili(): List<Utente> {
        firestore.assicuraAccesso()
        return firestore.profili()
    }

    private companion object {
        const val TAG = "RegiStrazio"
    }
}
