package com.example.registrazio

import com.example.registrazio.data.remote.LinkMega
import com.example.registrazio.data.remote.MegaCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Test della crittografia MEGA. Girano sulla JVM: niente emulatore, niente rete.
 *
 * Non possono provare che il protocollo sia quello giusto — per quello serve un
 * link vero — ma provano che le trasformazioni siano coerenti e che il seek di
 * AES-CTR cada sul byte esatto, che è la cosa più facile da sbagliare.
 */
class MegaCryptoTest {

    private fun cifraCtr(chiave: ByteArray, iv: ByteArray, dati: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(chiave, "AES"), IvParameterSpec(iv))
        }.doFinal(dati)

    private fun cifraCbcZeroIv(chiave: ByteArray, dati: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(chiave, "AES"), IvParameterSpec(ByteArray(16)))
        }.doFinal(dati)

    private fun cifraEcb(chiave: ByteArray, dati: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(chiave, "AES"))
        }.doFinal(dati)

    private fun base64Url(dati: ByteArray): String =
        Base64.getEncoder().encodeToString(dati)
            .replace('+', '-').replace('/', '_').trimEnd('=')

    // ---------- base64 ----------

    @Test
    fun `base64 url-safe senza padding viene decodificato`() {
        val originale = ByteArray(16) { it.toByte() }
        assertArrayEquals(originale, MegaCrypto.base64Decode(base64Url(originale)))
    }

    @Test
    fun `base64 accetta tutte le lunghezze di padding mancante`() {
        for (lunghezza in 1..24) {
            val originale = ByteArray(lunghezza) { (it * 7).toByte() }
            assertArrayEquals(
                "fallito con $lunghezza byte",
                originale,
                MegaCrypto.base64Decode(base64Url(originale))
            )
        }
    }

    // ---------- derivazione della chiave ----------

    @Test
    fun `la chiave del file e' lo XOR delle due meta'`() {
        val chiaveNodo = ByteArray(32) { it.toByte() }
        val chiave = MegaCrypto.chiaveFileDa(chiaveNodo)
        assertNotNull(chiave)
        val attesa = ByteArray(16) { i -> (i xor (i + 16)).toByte() }
        assertArrayEquals(attesa, chiave!!.aes)
        assertArrayEquals(chiaveNodo.copyOfRange(16, 24), chiave.nonce)
    }

    @Test
    fun `una chiave di nodo troppo corta viene rifiutata`() {
        assertNull(MegaCrypto.chiaveFileDa(ByteArray(16)))
    }

    // ---------- seek di AES-CTR ----------

    @Test
    fun `decifrare da un offset qualsiasi da' lo stesso risultato del flusso intero`() {
        val chiaveNodo = ByteArray(32) { (it * 11 + 3).toByte() }
        val chiave = MegaCrypto.chiaveFileDa(chiaveNodo)!!

        val chiaro = ByteArray(5000) { (it * 31 + 7).toByte() }
        val cifrato = cifraCtr(chiave.aes, MegaCrypto.ivPerOffset(chiave.nonce, 0), chiaro)

        // Gli offset scelti coprono i casi limite: inizio, dentro un blocco,
        // sul confine del blocco, e in coda al file.
        for (offset in listOf(0L, 1L, 15L, 16L, 17L, 373L, 1024L, 4095L, 4999L)) {
            val inizio = MegaCrypto.inizioBloccoPer(offset).toInt()
            val decifrato = cifraCtr(
                chiave.aes,
                MegaCrypto.ivPerOffset(chiave.nonce, offset),
                cifrato.copyOfRange(inizio, cifrato.size)
            )
            val ottenuto = decifrato.copyOfRange(MegaCrypto.scartoPerOffset(offset), decifrato.size)
            assertArrayEquals(
                "seek all'offset $offset",
                chiaro.copyOfRange(offset.toInt(), chiaro.size),
                ottenuto
            )
        }
    }

    @Test
    fun `il contatore dei blocchi non va in overflow oltre i 32 bit`() {
        val nonce = ByteArray(8) { 0 }
        // Blocco numero 2^32: se il contatore fosse a 32 bit tornerebbe a zero.
        val iv = MegaCrypto.ivPerOffset(nonce, 4294967296L * MegaCrypto.BLOCCO)
        assertEquals("il byte del blocco 2^32 deve essere valorizzato", 1.toByte(), iv[11])
        assertTrue("i primi 8 byte sono il nonce", iv.copyOfRange(0, 8).all { it == 0.toByte() })
    }

    // ---------- chiavi dei nodi e attributi ----------

    @Test
    fun `la chiave del nodo si decifra con la chiave della cartella`() {
        val chiaveCartella = ByteArray(16) { (it + 1).toByte() }
        val chiaveNodo = ByteArray(32) { (it * 5).toByte() }
        val campoK = "AbCdEfGh:" + base64Url(cifraEcb(chiaveCartella, chiaveNodo))

        assertArrayEquals(chiaveNodo, MegaCrypto.decifraChiaveNodo(campoK, chiaveCartella))
    }

    @Test
    fun `il nome del file si estrae dagli attributi cifrati`() {
        val chiaveNodo = ByteArray(32) { (it * 3 + 1).toByte() }
        val chiaveAttr = MegaCrypto.chiaveAttributi(chiaveNodo)!!

        val chiaro = """MEGA{"n":"Prova 12 - ritornello.mp3"}"""
        val riempito = chiaro.toByteArray().let {
            it + ByteArray((16 - it.size % 16) % 16)   // padding a zero fino al blocco
        }
        val attributi = base64Url(cifraCbcZeroIv(chiaveAttr, riempito))

        assertEquals("Prova 12 - ritornello.mp3", MegaCrypto.nomeDaAttributi(attributi, chiaveAttr))
    }

    @Test
    fun `con la chiave sbagliata il nome non viene inventato`() {
        val chiaveGiusta = ByteArray(16) { it.toByte() }
        val chiaveSbagliata = ByteArray(16) { (it + 99).toByte() }
        val chiaro = """MEGA{"n":"traccia.mp3"}""".toByteArray().let {
            it + ByteArray((16 - it.size % 16) % 16)
        }
        val attributi = base64Url(cifraCbcZeroIv(chiaveGiusta, chiaro))

        // Senza il controllo del prefisso "MEGA" qui uscirebbe spazzatura
        // interpretata come nome di file.
        assertNull(MegaCrypto.nomeDaAttributi(attributi, chiaveSbagliata))
    }

    // ---------- parsing del link ----------

    @Test
    fun `riconosce il formato attuale del link`() {
        val chiave = ByteArray(16) { it.toByte() }
        val link = LinkMega.parse("https://mega.nz/folder/AbCd1234#${base64Url(chiave)}")
        assertNotNull(link)
        assertEquals("AbCd1234", link!!.folderId)
        assertArrayEquals(chiave, link.chiave)
    }

    @Test
    fun `riconosce il vecchio formato con F!`() {
        val chiave = ByteArray(16) { (it * 2).toByte() }
        val link = LinkMega.parse("https://mega.nz/#F!AbCd1234!${base64Url(chiave)}")
        assertNotNull(link)
        assertEquals("AbCd1234", link!!.folderId)
        assertArrayEquals(chiave, link.chiave)
    }

    @Test
    fun `rifiuta i link che non portano una chiave utilizzabile`() {
        val corta = base64Url(ByteArray(8))
        listOf(
            "",
            "non un link",
            "https://mega.nz/folder/AbCd1234",              // manca la chiave
            "https://mega.nz/folder/#chiaveSenzaId",        // manca l'id
            "https://mega.nz/file/AbCd1234#chiave",         // file singolo, non cartella
            "https://mega.nz/folder/AbCd1234#$corta"        // chiave di 8 byte invece di 16
        ).forEach { assertNull("avrebbe dovuto rifiutare: $it", LinkMega.parse(it)) }
    }
}
