package com.example.registrazio.util

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Vero se in fondo alla catena delle cause c'è un problema di linea.
 *
 * Si guarda il **tipo dell'eccezione** e non lo stato della connessione: leggere
 * lo stato richiederebbe il permesso `ACCESS_NETWORK_STATE`, e comunque una rete
 * "attiva" dietro un portale captive fallisce esattamente come una assente.
 * L'eccezione dice la verità, lo stato di sistema no.
 *
 * Si scende lungo le cause perché chi incarta un errore lo nasconde: `MegaApi`
 * rilancia tutto come `MegaException`, e il tipo che conta sta sotto. Per questo
 * `MegaException` conserva la `cause`.
 *
 * Sta qui e non dentro il ViewModel perché è logica pura, e quindi è provabile
 * sulla JVM senza telefono.
 */
fun senzaRete(e: Throwable?): Boolean = risalendoLeCause(e) {
    // ConnectException e NoRouteToHostException stanno già sotto
    // SocketException: elencarle sarebbe rumore.
    it is UnknownHostException || it is SocketException || it is SocketTimeoutException
}

/**
 * Vero se la connessione si era aperta e poi ha smesso di mandare dati.
 *
 * È un sottoinsieme di [senzaRete], e va guardato **prima**: un timeout le
 * soddisfa entrambe, ma le due situazioni chiedono due frasi diverse. "Sei
 * senza linea" è sbagliato per un download che era partito e ha già preso
 * metà file — la linea c'era, si è piantata a metà strada, e quello che
 * l'utente deve sapere è che riprendendo non ricomincia da capo.
 */
fun trasferimentoFermo(e: Throwable?): Boolean =
    risalendoLeCause(e) { it is SocketTimeoutException }

/**
 * Cerca lungo la catena delle cause qualcosa che soddisfi [predicato].
 *
 * Iterativa e con un tetto: una catena che si morde la coda manderebbe in
 * stack overflow una versione ricorsiva, e succederebbe proprio mentre si sta
 * già gestendo un errore.
 */
private inline fun risalendoLeCause(e: Throwable?, predicato: (Throwable) -> Boolean): Boolean {
    var corrente: Throwable? = e
    var passi = 0
    while (passi < MAX_CAUSE) {
        val c = corrente ?: return false
        if (predicato(c)) return true
        val prossima = c.cause
        if (prossima === c) return false
        corrente = prossima
        passi++
    }
    return false
}

private const val MAX_CAUSE = 12
