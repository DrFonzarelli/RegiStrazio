package com.example.registrazio.data.remote

import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.model.Utente
import com.example.registrazio.data.model.VotoStella
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * L'altra metà dell'archivio: i documenti su Firestore.
 *
 * Non è la fonte di verità e non va letto per sapere "cosa c'è" — a quello
 * risponde [com.example.registrazio.data.local.ArchivioLocale]. Qui si viene
 * solo quando qualcuno preme Sincronizza, ed è anche l'unico momento in cui si
 * vedono le modifiche degli altri.
 *
 * **L'audio non passa mai di qui.** Un documento in `tracce/` è un cartellino
 * segnaletico: porta `idFileMega`, cioè il riferimento con cui andare a
 * prendere il file vero su MEGA. La chiave AES resta sul telefono.
 */
class FirestoreRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    /**
     * Autenticazione anonima, richiesta dalle regole di sicurezza.
     *
     * L'UID che ne esce **non è l'identità dell'utente**: quella è `appUid`,
     * generato dall'app e salvato sul telefono. Anonymous Auth ne assegna uno
     * nuovo a ogni reinstallazione, quindi usarlo come chiave vorrebbe dire
     * perdere i propri commenti reinstallando. Serve solo a far passare le
     * regole, che chiedono "un utente autenticato qualsiasi".
     */
    suspend fun assicuraAccesso() {
        if (auth.currentUser != null) return
        auth.signInAnonymously().await()
    }

    // ---------- profili ----------

    /** L'elenco per la schermata "Ho già un account", in ordine di creazione. */
    suspend fun profili(): List<Utente> =
        db.collection(UTENTI).get().await().documents
            .mapNotNull { it.aUtente() }
            .sortedBy { it.creatoIl }

    suspend fun salvaProfilo(utente: Utente) {
        db.collection(UTENTI).document(utente.appUid).set(
            mapOf(
                "nome" to utente.nome,
                "colore" to utente.colore,
                "creatoIl" to utente.creatoIl
            )
        ).await()
    }

    // ---------- cartelle ----------

    suspend fun cartelle(): List<Cartella> =
        db.collection(CARTELLE).get().await().documents.mapNotNull { it.aCartella() }

    suspend fun salvaCartella(cartella: Cartella) {
        db.collection(CARTELLE).document(cartella.id).set(
            mapOf(
                "nome" to cartella.nome,
                "linkMega" to cartella.linkMega,
                "megaFolderId" to cartella.megaFolderId,
                "aggiuntoIl" to cartella.aggiuntoIl,
                "aggiuntoDa" to cartella.aggiuntoDa
            )
        ).await()
    }

    suspend fun eliminaCartella(cartellaId: String) {
        db.collection(CARTELLE).document(cartellaId).delete().await()
    }

    // ---------- tracce ----------

    /**
     * Le tracce di una cartella.
     *
     * Una query per cartella invece di una `whereIn` sola: quella accetta al
     * massimo dieci valori, e un gruppo che collega l'undicesima cartella si
     * ritroverebbe le tracce che spariscono senza un errore da nessuna parte.
     */
    suspend fun tracce(cartellaId: String): List<Traccia> =
        db.collection(TRACCE)
            .whereEqualTo("cartellaId", cartellaId)
            .get().await().documents
            .mapNotNull { it.aTraccia() }

    suspend fun salvaTraccia(traccia: Traccia) {
        db.collection(TRACCE).document(traccia.id).set(
            mapOf(
                "cartellaId" to traccia.cartellaId,
                "nomeFile" to traccia.titolo,
                "idFileMega" to traccia.idFileMega,
                "dimensioneByte" to traccia.dimensioneByte,
                "durataSecondi" to traccia.durataSecondi,
                "waveformData" to traccia.waveformData,
                "ascolti" to traccia.ascolti,
                // I voti sono di gruppo. `mioVoto` no: è di questo telefono e
                // resta in Room, o la stella di uno diventerebbe quella di tutti.
                "votiPieni" to traccia.votiPieni,
                "votiMezzi" to traccia.votiMezzi,
                "playBuckets" to traccia.playBuckets,
                "creatoIl" to traccia.creatoIl
            )
        ).await()
    }

    suspend fun eliminaTraccia(tracciaId: String) {
        db.collection(TRACCE).document(tracciaId).delete().await()
    }

    // ---------- commenti ----------

    /**
     * Tutti i commenti di tutte le tracce, in una chiamata sola.
     *
     * `collectionGroup` interroga ogni sottoraccolta `commenti/` ovunque si
     * trovi, invece di una query per traccia — che con cinquanta tracce
     * sarebbero cinquanta viaggi. L'id della traccia si ricava dal percorso del
     * documento: è il padre del padre.
     */
    suspend fun tuttiICommenti(): List<Commento> =
        db.collectionGroup(COMMENTI).get().await().documents.mapNotNull { it.aCommento() }

    suspend fun salvaCommento(commento: Commento) {
        db.collection(TRACCE).document(commento.tracciaId)
            .collection(COMMENTI).document(commento.id)
            .set(
                mapOf(
                    "appUid" to commento.appUid,
                    "autoreNome" to commento.autoreNome,
                    "autoreColore" to commento.autoreColore,
                    "timestampSecondi" to commento.timestampSecondi,
                    "testo" to commento.testo,
                    "creatoIl" to commento.creatoIl
                )
            ).await()
    }

    suspend fun eliminaCommento(tracciaId: String, commentoId: String) {
        db.collection(TRACCE).document(tracciaId)
            .collection(COMMENTI).document(commentoId)
            .delete().await()
    }

    private companion object {
        const val UTENTI = "utenti"
        const val CARTELLE = "cartelle"
        const val TRACCE = "tracce"
        const val COMMENTI = "commenti"
    }
}

// ---------- da documento a modello ----------
//
// Ogni campo ha il suo ripiego e nessuna lettura può far esplodere il parsing:
// un documento scritto da una versione più vecchia dell'app, o rimasto a metà,
// deve poter essere letto lo stesso. Una `mapNotNull` scarta solo ciò che non ha
// nemmeno un'identità.

private fun DocumentSnapshot.aUtente(): Utente? {
    val nome = getString("nome")?.takeIf { it.isNotBlank() } ?: return null
    return Utente(
        appUid = id,
        nome = nome,
        colore = getString("colore") ?: "a",
        creatoIl = getLong("creatoIl") ?: 0L
    )
}

private fun DocumentSnapshot.aCartella(): Cartella? {
    val link = getString("linkMega")?.takeIf { it.isNotBlank() } ?: return null
    return Cartella(
        id = id,
        nome = getString("nome").orEmpty().ifBlank { Cartella.suggestName(id) },
        linkMega = link,
        megaFolderId = getString("megaFolderId") ?: id,
        aggiuntoIl = getLong("aggiuntoIl") ?: 0L,
        aggiuntoDa = getString("aggiuntoDa").orEmpty()
    )
}

private fun DocumentSnapshot.aTraccia(): Traccia? {
    val cartellaId = getString("cartellaId")?.takeIf { it.isNotBlank() } ?: return null
    return Traccia(
        id = id,
        cartellaId = cartellaId,
        titolo = getString("nomeFile").orEmpty().ifBlank { id },
        idFileMega = getString("idFileMega").orEmpty(),
        dimensioneByte = getLong("dimensioneByte") ?: 0L,
        durataSecondi = (getLong("durataSecondi") ?: 0L).toInt(),
        waveformData = numeri("waveformData"),
        ascolti = (getLong("ascolti") ?: 0L).toInt(),
        // `mioVoto` non arriva da qui: è di questo telefono. Chi legge
        // sovrascrive solo i contatori di gruppo, vedi SyncManager.
        mioVoto = VotoStella.NESSUNO,
        votiPieni = (getLong("votiPieni") ?: 0L).toInt(),
        votiMezzi = (getLong("votiMezzi") ?: 0L).toInt(),
        playBuckets = numeri("playBuckets") ?: List(24) { 0f },
        creatoIl = getLong("creatoIl") ?: 0L
    )
}

private fun DocumentSnapshot.aCommento(): Commento? {
    // `commenti/{id}` sta sotto `tracce/{tracciaId}`: il padre del padre.
    val tracciaId = reference.parent.parent?.id ?: return null
    val testo = getString("testo") ?: return null
    return Commento(
        id = id,
        tracciaId = tracciaId,
        appUid = getString("appUid").orEmpty(),
        autoreNome = getString("autoreNome").orEmpty().ifBlank { "?" },
        autoreColore = getString("autoreColore") ?: "a",
        timestampSecondi = (getDouble("timestampSecondi") ?: 0.0).toFloat(),
        testo = testo,
        creatoIl = getLong("creatoIl") ?: 0L,
        // Arriva da Firestore, quindi su Firestore c'è già.
        statoSync = com.example.registrazio.data.model.StatoSync.SINCRONIZZATO
    )
}

/** Firestore restituisce gli array numerici come `List<*>` di Double o Long. */
private fun DocumentSnapshot.numeri(campo: String): List<Float>? =
    (get(campo) as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
