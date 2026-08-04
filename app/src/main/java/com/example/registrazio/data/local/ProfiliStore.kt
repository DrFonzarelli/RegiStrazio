package com.example.registrazio.data.local

import android.content.Context
import com.example.registrazio.data.model.Utente
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Sostituto temporaneo di Firestore per l'elenco dei profili del gruppo.
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

    fun profili(): List<Utente> {
        val raw = prefs.getString(KEY_UTENTI, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<Utente>>(raw, tipoUtenti) }.getOrNull().orEmpty()
    }

    fun registraProfilo(utente: Utente) {
        val aggiornati = (profili().filterNot { it.appUid == utente.appUid } + utente)
            .sortedBy { it.creatoIl }
        prefs.edit().putString(KEY_UTENTI, gson.toJson(aggiornati)).apply()
    }

    /** Strumento di test: "Svuota tutto il cloud simulato". */
    fun svuota() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_UTENTI = "utenti"
    }
}
