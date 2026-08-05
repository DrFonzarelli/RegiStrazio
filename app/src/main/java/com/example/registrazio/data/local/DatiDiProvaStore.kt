package com.example.registrazio.data.local

import android.content.Context

/**
 * Ricorda se le cartelle di prova sono già state seminate su questo telefono.
 *
 * Senza questo segno il seme ripartirebbe a ogni avvio, e scollegare una
 * cartella di prova diventerebbe impossibile: la ritroveresti al riavvio
 * successivo, senza capire perché. Con il segno, il seme è un fatto che
 * succede una volta sola — e il tasto di reset lo cancella per farlo
 * succedere di nuovo.
 *
 * Sta nelle stesse preferenze del cloud simulato perché condivide la sorte di
 * quei dati: quando `ProfiliStore.svuota()` azzera tutto, questo segno deve
 * sparire con loro.
 */
class DatiDiProvaStore(context: Context) {

    private val prefs = context.getSharedPreferences("registrazio_cloud_sim", Context.MODE_PRIVATE)

    fun giaSeminati(): Boolean = prefs.getBoolean(KEY_SEMINATI, false)

    fun segnaSeminati() = prefs.edit().putBoolean(KEY_SEMINATI, true).apply()

    /** Il prossimo avvio riseminerà. */
    fun dimentica() = prefs.edit().remove(KEY_SEMINATI).apply()

    private companion object {
        const val KEY_SEMINATI = "cartelle_di_prova_seminate"
    }
}
