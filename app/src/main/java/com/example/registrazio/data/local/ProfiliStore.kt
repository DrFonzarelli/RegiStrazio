package com.example.registrazio.data.local

import android.content.Context
import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Utente
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Sostituto temporaneo di Firestore per i dati condivisi dal gruppo
 * (elenco profili e cartelle collegate).
 *
 * Il prototipo li tiene in `localStorage` sotto la chiave `prove_cloud_v2`,
 * proprio per poter provare il recupero account dopo una reinstallazione.
 * Qui facciamo lo stesso con SharedPreferences: quando `FirestoreRepository`
 * sarà collegato, questa classe sparisce e le firme restano identiche.
 */
class ProfiliStore(context: Context) {

    private val prefs = context.getSharedPreferences("registrazio_cloud_sim", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val tipoUtenti = object : TypeToken<List<Utente>>() {}.type
    private val tipoCartelle = object : TypeToken<List<Cartella>>() {}.type

    fun profili(): List<Utente> {
        val raw = prefs.getString(KEY_UTENTI, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<Utente>>(raw, tipoUtenti) }.getOrNull().orEmpty()
    }

    fun cartelle(): List<Cartella> {
        val raw = prefs.getString(KEY_CARTELLE, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<Cartella>>(raw, tipoCartelle) }.getOrNull().orEmpty()
    }

    fun registraProfilo(utente: Utente) {
        val aggiornati = (profili().filterNot { it.appUid == utente.appUid } + utente)
            .sortedBy { it.creatoIl }
        prefs.edit().putString(KEY_UTENTI, gson.toJson(aggiornati)).apply()
    }

    /**
     * Salva la cartella, sostituendo quella con lo stesso `megaFolderId` se c'è.
     *
     * Sostituire invece di saltare: ricollegando una cartella se ne rileggono
     * nome e contenuto da MEGA, e ignorare la nuova versione vorrebbe dire
     * tenersi per sempre il nome di ripiego salvato la prima volta.
     */
    fun registraCartella(cartella: Cartella) {
        val attuali = cartelle()
        val esistente = attuali.indexOfFirst { it.megaFolderId == cartella.megaFolderId }
        val aggiornate =
            if (esistente >= 0) attuali.toMutableList().also { it[esistente] = cartella }
            else attuali + cartella
        prefs.edit().putString(KEY_CARTELLE, gson.toJson(aggiornate)).apply()
    }

    fun rimuoviCartella(id: String) {
        val rimaste = cartelle().filterNot { it.id == id }
        prefs.edit().putString(KEY_CARTELLE, gson.toJson(rimaste)).apply()
    }

    /** Strumento di test: "Svuota tutto il cloud simulato". */
    fun svuota() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_UTENTI = "utenti"
        const val KEY_CARTELLE = "cartelle"
    }
}
