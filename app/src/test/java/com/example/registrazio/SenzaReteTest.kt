package com.example.registrazio

import com.example.registrazio.data.remote.MegaException
import com.example.registrazio.util.senzaRete
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Prove del riconoscimento "manca la linea".
 *
 * Serve a distinguere due frasi molto diverse: *"sei senza rete, scarica le
 * tracce quando puoi"* e *"MEGA dice di no"*. Sbagliare qui vuol dire dare un
 * consiglio inutile nel momento peggiore.
 */
class SenzaReteTest {

    @Test
    fun `i tipici errori di linea si riconoscono`() {
        assertTrue(senzaRete(UnknownHostException("g.api.mega.co.nz")))
        assertTrue(senzaRete(ConnectException("rifiutata")))
        assertTrue(senzaRete(NoRouteToHostException()))
        assertTrue(senzaRete(SocketTimeoutException()))
    }

    @Test
    fun `si guarda anche sotto a chi incarta l'errore`() {
        // È il caso reale: MegaApi rilancia tutto come MegaException, e senza
        // scendere nella causa ogni assenza di rete sembrerebbe un errore di MEGA.
        val incartato = MegaException(
            null,
            "Non riesco a contattare MEGA. Controlla la connessione.",
            UnknownHostException("g.api.mega.co.nz")
        )
        assertTrue(senzaRete(incartato))
    }

    @Test
    fun `si scende per piu di un livello`() {
        val profondo = RuntimeException("fuori", IOException("mezzo", ConnectException("dentro")))
        assertTrue(senzaRete(profondo))
    }

    @Test
    fun `un no di MEGA non e una mancanza di rete`() {
        // Codice -9: file inesistente. La rete c'era eccome.
        assertFalse(senzaRete(MegaException(-9, "Questa cartella non esiste più su MEGA.")))
        assertFalse(senzaRete(IllegalStateException("chiave mancante")))
        assertFalse(senzaRete(null))
    }

    @Test
    fun `una catena di cause circolare non blocca l'app`() {
        // Con una versione ricorsiva questo test non finiva: andava in stack
        // overflow proprio mentre si stava già gestendo un errore.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(senzaRete(a))
    }

    @Test
    fun `una causa che punta a se stessa non blocca l'app`() {
        val solitaria = object : RuntimeException("io") {
            override val cause: Throwable? get() = this
        }
        assertFalse(senzaRete(solitaria))
    }
}
