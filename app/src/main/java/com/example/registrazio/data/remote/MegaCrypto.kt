package com.example.registrazio.data.remote

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crittografia dei link pubblici MEGA.
 *
 * MEGA cifra tutto sul client: il server non ha mai le chiavi. La chiave sta
 * nel frammento del link, dopo il `#`, che il browser non manda mai al server.
 *
 * Tre operazioni distinte, da non confondere:
 * - **chiavi dei nodi** → AES-128-**ECB** con la chiave della cartella
 * - **attributi** (il nome del file) → AES-128-**CBC** con IV a zero
 * - **contenuto del file** → AES-128-**CTR** (vedi [ivPerOffset])
 *
 * Questo oggetto non fa rete: prende stringhe e byte e restituisce byte.
 * Le chiamate HTTP stanno in [MegaApi].
 */
object MegaCrypto {

    /**
     * MEGA usa base64 URL-safe **senza padding**: `-` e `_` al posto di `+` e `/`.
     * Il decodificatore standard vuole il padding, quindi va rimesso.
     */
    fun base64Decode(testo: String): ByteArray {
        val normalizzato = testo.trim().replace('-', '+').replace('_', '/')
        val mancanti = (4 - normalizzato.length % 4) % 4
        return Base64.getDecoder().decode(normalizzato + "=".repeat(mancanti))
    }

    fun aesEcbDecrypt(chiave: ByteArray, dati: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(chiave, "AES"))
        }.doFinal(dati)

    fun aesCbcZeroIvDecrypt(chiave: ByteArray, dati: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(chiave, "AES"), IvParameterSpec(ByteArray(16)))
        }.doFinal(dati)

    /**
     * Chiave AES e nonce di un file, ricavati dalla sua chiave di nodo da 32 byte.
     *
     * I 32 byte non sono la chiave: la chiave vera sono i primi 16 in XOR con i
     * secondi 16. Gli 8 byte da 16 a 23 sono il nonce; gli ultimi 8 sono il
     * meta-MAC, che serve solo a verificare l'integrità e qui non usiamo.
     */
    data class ChiaveFile(val aes: ByteArray, val nonce: ByteArray) {
        // data class con ByteArray: equals/hashCode generati confrontano i
        // riferimenti, non il contenuto. Riscritti per evitare sorprese nei test.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChiaveFile) return false
            return aes.contentEquals(other.aes) && nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int = 31 * aes.contentHashCode() + nonce.contentHashCode()
    }

    fun chiaveFileDa(chiaveNodo: ByteArray): ChiaveFile? {
        if (chiaveNodo.size < 32) return null
        val aes = ByteArray(16) { i ->
            (chiaveNodo[i].toInt() xor chiaveNodo[i + 16].toInt()).toByte()
        }
        return ChiaveFile(aes, chiaveNodo.copyOfRange(16, 24))
    }

    /**
     * Chiave con cui sono cifrati gli attributi del nodo.
     *
     * Per i file è la chiave derivata in XOR; per le cartelle la chiave del nodo
     * è già di 16 byte e si usa così com'è.
     */
    fun chiaveAttributi(chiaveNodo: ByteArray): ByteArray? = when {
        chiaveNodo.size >= 32 -> chiaveFileDa(chiaveNodo)?.aes
        chiaveNodo.size >= 16 -> chiaveNodo.copyOf(16)
        else -> null
    }

    /**
     * Decifra il campo `k` di un nodo, cifrato con la chiave della cartella.
     *
     * Il campo ha forma `<handle>:<chiaveBase64>`, e può contenere più voci
     * separate da `/` quando il nodo è condiviso per più vie. Con un link
     * pubblico ne basta una qualsiasi che si decifri a una lunghezza sensata.
     */
    fun decifraChiaveNodo(campoK: String, chiaveCartella: ByteArray): ByteArray? {
        for (voce in campoK.split("/")) {
            val parte = voce.substringAfter(':', "").takeIf { it.isNotBlank() } ?: continue
            val cifrata = runCatching { base64Decode(parte) }.getOrNull() ?: continue
            // AES/ECB/NoPadding pretende un multiplo esatto del blocco.
            if (cifrata.isEmpty() || cifrata.size % 16 != 0) continue
            val chiara = runCatching { aesEcbDecrypt(chiaveCartella, cifrata) }.getOrNull() ?: continue
            if (chiara.size >= 16) return chiara
        }
        return null
    }

    /**
     * Decifra gli attributi del nodo e ne estrae il nome del file.
     *
     * In chiaro sono la stringa `MEGA` seguita da un JSON, riempito di byte zero
     * fino al multiplo di 16. Se il prefisso non c'è, la chiave era sbagliata:
     * meglio accorgersene qui che ritrovarsi nomi illeggibili nell'elenco.
     */
    fun nomeDaAttributi(attributiBase64: String, chiaveAttributi: ByteArray): String? {
        val cifrati = runCatching { base64Decode(attributiBase64) }.getOrNull() ?: return null
        if (cifrati.isEmpty() || cifrati.size % 16 != 0) return null
        val chiari = runCatching { aesCbcZeroIvDecrypt(chiaveAttributi, cifrati) }.getOrNull()
            ?: return null

        val testo = String(chiari, Charsets.UTF_8).trimEnd('\u0000')
        if (!testo.startsWith("MEGA{")) return null

        // Estrazione mirata del solo campo "n": tirare dentro un parser JSON per
        // una chiave sola non serve, e il valore può contenere sequenze escaped.
        val json = testo.removePrefix("MEGA")
        val nome = Regex("\"n\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1)
            ?: return null
        return nome
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Blocco IV per decifrare il contenuto a partire dal byte [offsetByte].
     *
     * CTR è seekabile: l'IV è `nonce (8 byte) || indice del blocco (8 byte, big
     * endian)`. Per leggere dal byte N si parte dal blocco `N / 16` e si buttano
     * via i primi `N % 16` byte — vedi [scartoPerOffset].
     *
     * L'indice sta su 8 byte, non su 4: un file da più di 64 GB manderebbe in
     * overflow un contatore a 32 bit.
     */
    fun ivPerOffset(nonce: ByteArray, offsetByte: Long): ByteArray {
        require(nonce.size >= 8) { "Il nonce MEGA è di 8 byte" }
        val blocco = offsetByte / BLOCCO
        val iv = ByteArray(16)
        System.arraycopy(nonce, 0, iv, 0, 8)
        for (i in 0 until 8) {
            iv[15 - i] = ((blocco ushr (8 * i)) and 0xFFL).toByte()
        }
        return iv
    }

    /** Byte da scartare dopo aver iniziato a decifrare dal blocco che contiene [offsetByte]. */
    fun scartoPerOffset(offsetByte: Long): Int = (offsetByte % BLOCCO).toInt()

    /** Offset del blocco allineato che contiene [offsetByte]. */
    fun inizioBloccoPer(offsetByte: Long): Long = (offsetByte / BLOCCO) * BLOCCO

    const val BLOCCO = 16L
}
