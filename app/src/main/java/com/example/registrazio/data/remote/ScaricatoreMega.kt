package com.example.registrazio.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Scarica un file da MEGA sul telefono, decifrandolo lungo la strada.
 *
 * Non si usa il `DownloadManager` di sistema, che pure il README suggeriva: non
 * sa niente di AES e salverebbe su disco byte cifrati, cioè un file che non
 * suona. Decifrando qui, quello che finisce in `cacheDir/audio/` è un file
 * audio normale — e la riproduzione locale non deve sapere niente di MEGA.
 */
class ScaricatoreMega(
    private val megaApi: MegaApi,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Nessun timeout di lettura: un brano lungo su rete lenta impiega
        // quanto impiega, e interromperlo a metà non aiuterebbe nessuno.
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Scarica [handle] dentro [destinazione], riportando l'avanzamento da 0 a 1.
     *
     * Scrive prima su un file temporaneo e rinomina solo alla fine: se il
     * download si interrompe — rete caduta, app chiusa, utente che annulla —
     * non deve restare mezzo brano che sembra completo.
     */
    suspend fun scarica(
        link: LinkMega,
        handle: String,
        chiave: MegaCrypto.ChiaveFile,
        destinazione: File,
        onProgresso: (Float) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val url = megaApi.urlDiDownload(link, handle)

        destinazione.parentFile?.mkdirs()
        val parziale = File(destinazione.absolutePath + ".parziale")

        val cifrario = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(chiave.aes, "AES"),
                IvParameterSpec(MegaCrypto.ivPerOffset(chiave.nonce, 0))
            )
        }

        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { risposta ->
                if (!risposta.isSuccessful) {
                    throw MegaException(null, "MEGA ha risposto con un errore HTTP ${risposta.code}.")
                }
                val corpo = risposta.body
                    ?: throw MegaException(null, "MEGA non ha mandato il contenuto del file.")

                val totale = corpo.contentLength()
                var scaricati = 0L
                val tampone = ByteArray(64 * 1024)

                corpo.byteStream().use { entrata ->
                    parziale.outputStream().buffered().use { uscita ->
                        while (currentCoroutineContext().isActive) {
                            val letti = entrata.read(tampone)
                            if (letti <= 0) break
                            uscita.write(cifrario.update(tampone, 0, letti))
                            scaricati += letti
                            if (totale > 0) onProgresso((scaricati.toFloat() / totale).coerceIn(0f, 1f))
                        }
                        // CTR non ha padding, quindi `doFinal` non produce quasi
                        // mai byte: si scrive comunque, per non perdere una coda.
                        cifrario.doFinal().takeIf { it.isNotEmpty() }?.let { uscita.write(it) }
                    }
                }

                if (!currentCoroutineContext().isActive) {
                    throw MegaException(null, "Download interrotto.")
                }
            }

            destinazione.delete()
            if (!parziale.renameTo(destinazione)) {
                throw MegaException(null, "Non riesco a salvare la traccia sul telefono.")
            }
            onProgresso(1f)
        } catch (e: Throwable) {
            // Un file a metà è peggio di nessun file: al play successivo
            // sembrerebbe scaricato e suonerebbe troncato.
            parziale.delete()
            throw e
        }
    }
}
