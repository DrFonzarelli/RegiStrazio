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
fun senzaRete(e: Throwable?): Boolean {
    // Iterativa e con un tetto: una catena di cause che si morde la coda
    // manderebbe in stack overflow una versione ricorsiva, e succederebbe
    // proprio mentre si sta già gestendo un errore.
    var corrente: Throwable? = e
    var passi = 0
    while (passi < MAX_CAUSE) {
        val c = corrente ?: return false
        // ConnectException e NoRouteToHostException stanno già sotto
        // SocketException: elencarle sarebbe rumore.
        if (c is UnknownHostException || c is SocketException || c is SocketTimeoutException) {
            return true
        }
        val prossima = c.cause
        if (prossima === c) return false
        corrente = prossima
        passi++
    }
    return false
}

private const val MAX_CAUSE = 12
