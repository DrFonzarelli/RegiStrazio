package com.example.registrazio

import com.example.registrazio.data.remote.LinkMega
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Estrazione del link dal testo che arriva da "Condividi".
 *
 * MEGA non manda l'indirizzo nudo ma una frase intorno, e il testo può passare
 * per WhatsApp o per una mail prima di arrivare qui, raccogliendo punteggiatura
 * per strada. Gira sulla JVM: niente emulatore, niente rete.
 */
class LinkCondivisoTest {

    private val chiave = ByteArray(16) { (it * 7).toByte() }

    private val chiaveB64 = Base64.getEncoder().encodeToString(chiave)
        .replace('+', '-').replace('/', '_').trimEnd('=')

    private val url = "https://mega.nz/folder/AbCd1234#$chiaveB64"

    @Test
    fun `un link da solo viene riconosciuto`() {
        assertEquals(url, LinkMega.cercaNelTesto(url))
    }

    @Test
    fun `il link viene pescato da dentro una frase`() {
        assertEquals(url, LinkMega.cercaNelTesto("Guarda questa cartella: $url"))
    }

    @Test
    fun `la punteggiatura intorno al link non lo rovina`() {
        assertEquals(url, LinkMega.cercaNelTesto("Ecco il link $url."))
        assertEquals(url, LinkMega.cercaNelTesto("Prove ($url) da ascoltare"))
        assertEquals(url, LinkMega.cercaNelTesto("\"$url\","))
    }

    @Test
    fun `il link si trova anche su piu' righe`() {
        assertEquals(url, LinkMega.cercaNelTesto("Cartella prove\n$url\nbuon ascolto"))
    }

    @Test
    fun `riconosce anche il vecchio formato con F!`() {
        val vecchio = "https://mega.nz/#F!AbCd1234!$chiaveB64"
        assertEquals(vecchio, LinkMega.cercaNelTesto("vecchio stile: $vecchio"))
    }

    @Test
    fun `un testo senza link non inventa niente`() {
        assertNull(LinkMega.cercaNelTesto(""))
        assertNull(LinkMega.cercaNelTesto("ciao come stai"))
        assertNull(LinkMega.cercaNelTesto("https://esempio.it/qualcosa"))
    }

    @Test
    fun `un link a un singolo file non e' una cartella`() {
        // Condividere un brano invece della cartella è un errore facile: va
        // rifiutato qui, non a metà del collegamento.
        assertNull(LinkMega.cercaNelTesto("https://mega.nz/file/AbCd1234#$chiaveB64"))
    }

    @Test
    fun `un link troncato viene rifiutato`() {
        // Il caso classico: si seleziona il link a mano e la chiave dopo il #
        // resta indietro.
        assertNull(LinkMega.cercaNelTesto("https://mega.nz/folder/AbCd1234"))
        assertNull(LinkMega.cercaNelTesto("https://mega.nz/folder/AbCd1234#abc"))
    }

    @Test
    fun `fra piu' link vince il primo valido`() {
        assertEquals(
            url,
            LinkMega.cercaNelTesto("prima https://esempio.it poi $url e infine altro")
        )
    }
}
