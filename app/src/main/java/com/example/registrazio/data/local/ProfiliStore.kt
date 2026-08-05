package com.example.registrazio.data.local

import android.content.Context
import com.example.registrazio.data.model.Utente
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * L'ultima copia nota dei profili del gruppo.
 *
 * Nata come sostituto di Firestore, ora che Firestore c'è ha cambiato mestiere
 * senza cambiare una riga: è la **cache** dell'elenco remoto. Il Gate la mostra
 * subito e poi la sostituisce con quella vera appena la rete risponde.
 *
 * Non è un doppione inutile. Chi ha appena reinstallato apre l'app sul Gate e
 * deve riconoscersi in quella lista: aspettare la rete gliela lascerebbe vuota
 * nel momento peggiore, e senza linea vuota resterebbe — cioè un account
 * irrecuperabile finché non torna il segnale.
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
