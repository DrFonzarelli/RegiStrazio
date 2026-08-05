package com.example.registrazio.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
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
 *
 * **Il download si può interrompere e riprendere.** Il pezzo già scaricato
 * resta in un file `.parziale` e alla ripresa si chiede a MEGA solo il resto.
 * Funziona grazie alla stessa proprietà di AES-CTR che rende possibile il seek:
 * la decifratura può cominciare da qualunque punto, purché si sappia da quale.
 */
class ScaricatoreMega(
    private val megaApi: MegaApi,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Il timeout di lettura **non** è il tempo che può durare il download:
        // è il tempo massimo fra un byte e il successivo. Un brano lungo su
        // rete lenta non lo sfiora nemmeno, purché i byte continuino ad
        // arrivare. Metterlo a zero, come era prima, non toglie un limite:
        // toglie l'unico modo di accorgersi che la connessione è morta senza
        // chiudersi — e lì la `read()` resta appesa per sempre, senza errore e
        // senza avanzamento. Sono i download che si piantavano a metà e
        // ripartivano solo mettendoli in pausa a mano.
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /** Il file dove si accumula un download non ancora finito. */
    private fun parzialeDi(destinazione: File): File =
        File(destinazione.absolutePath + ".parziale")

    /**
     * Quanti byte di [destinazione] sono già sul disco, `0` se non c'è niente.
     *
     * Il `.parziale` sopravvive alla chiusura dell'app, la percentuale in
     * memoria no: senza questo, riaprendo l'app un download a metà ripartiva
     * visivamente da zero per poi saltare al 50% al primo aggiornamento.
     * Riprendeva dal punto giusto — lo diceva male.
     */
    fun byteGiaPresi(destinazione: File): Long =
        parzialeDi(destinazione).takeIf { it.exists() }?.length() ?: 0L

    /**
     * Scarica [handle] dentro [destinazione], riportando l'avanzamento da 0 a 1.
     *
     * Se esiste già un `.parziale` riprende da lì. Interrompere il job **non**
     * cancella quel file: è tutto il senso della ripresa. A buttarlo è solo chi
     * rinuncia davvero al download.
     *
     * @param dimensioneAttesa peso del file secondo l'elenco della cartella,
     *   `0` se ignoto. Fa due lavori, ed entrambi contano: è il denominatore
     *   della percentuale — `Content-Length` non arriva sempre, e senza un
     *   totale la barra resta a zero per tutto lo scaricamento — ed è il
     *   metro per dire se il file è arrivato **intero**. Un flusso che si
     *   chiude prima del tempo non alza nessuna eccezione: la `read()`
     *   restituisce `-1` come a fine file, e senza un confronto il troncato
     *   verrebbe rinominato e dato per buono.
     */
    suspend fun scarica(
        link: LinkMega,
        handle: String,
        chiave: MegaCrypto.ChiaveFile,
        destinazione: File,
        dimensioneAttesa: Long,
        onProgresso: (Float) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        destinazione.parentFile?.mkdirs()
        val parziale = parzialeDi(destinazione)

        // Si riprende solo da un confine di blocco: così l'IV di CTR si calcola
        // esattamente e non resta mezzo blocco da riallineare.
        var daByte = allineaABlocco(parziale)

        val url = megaApi.urlDiDownload(link, handle)
        val richiesta = Request.Builder()
            .url(url)
            .apply { if (daByte > 0) header("Range", "bytes=$daByte-") }
            .build()

        client.newCall(richiesta).execute().use { risposta ->
            if (!risposta.isSuccessful) {
                throw MegaException(null, "MEGA ha risposto con un errore HTTP ${risposta.code}.")
            }
            // Abbiamo chiesto un pezzo e ci hanno dato tutto il file: appenderlo
            // al parziale lo raddoppierebbe. Si ricomincia da capo.
            if (daByte > 0 && risposta.code != 206) {
                parziale.delete()
                daByte = 0
            }

            val corpo = risposta.body
                ?: throw MegaException(null, "MEGA non ha mandato il contenuto del file.")

            // Prima quello che ha detto MEGA elencando la cartella, poi il
            // `Content-Length` come ripiego. In quest'ordine perché il primo
            // c'è sempre e non dipende da come la risposta viene trasferita.
            val totale = when {
                dimensioneAttesa > 0 -> dimensioneAttesa
                corpo.contentLength() > 0 -> corpo.contentLength() + daByte
                else -> -1L
            }

            val cifrario = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(chiave.aes, "AES"),
                    IvParameterSpec(MegaCrypto.ivPerOffset(chiave.nonce, daByte))
                )
            }

            var scritti = daByte
            val tampone = ByteArray(64 * 1024)

            corpo.byteStream().use { entrata ->
                java.io.FileOutputStream(parziale, /* append = */ daByte > 0).buffered()
                    .use { uscita ->
                        while (currentCoroutineContext().isActive) {
                            val letti = entrata.read(tampone)
                            if (letti <= 0) break
                            uscita.write(cifrario.update(tampone, 0, letti))
                            scritti += letti
                            if (totale > 0) {
                                onProgresso((scritti.toFloat() / totale).coerceIn(0f, 1f))
                            }
                        }
                        cifrario.doFinal().takeIf { it.isNotEmpty() }?.let { uscita.write(it) }
                    }
            }

            // Interrotto a metà: il parziale resta dov'è, pronto per la ripresa.
            if (!currentCoroutineContext().isActive) return@withContext
        }

        // Il flusso è finito, ma "finito" non vuol dire "completo": una
        // connessione che cade a metà chiude lo stream esattamente come un
        // file arrivato in fondo. AES-CTR non cambia la lunghezza, quindi il
        // decifrato pesa quanto il cifrato e i due numeri si confrontano
        // direttamente. Il parziale resta al suo posto: alla ripresa si
        // riparte da lì invece che da zero.
        val ottenuti = parziale.length()
        if (dimensioneAttesa > 0 && ottenuti != dimensioneAttesa) {
            throw MegaException(
                null,
                "Il file è arrivato incompleto (${ottenuti / 1024} KB su " +
                    "${dimensioneAttesa / 1024} KB). Riprova per continuare da qui."
            )
        }

        destinazione.delete()
        if (!parziale.renameTo(destinazione)) {
            throw MegaException(null, "Non riesco a salvare la traccia sul telefono.")
        }
        onProgresso(1f)
    }

    /**
     * Tronca il parziale al multiplo di 16 più vicino e restituisce la nuova
     * lunghezza.
     *
     * AES-CTR lavora a blocchi di 16 byte: riprendere da un punto qualsiasi
     * costringerebbe a scartare i primi byte del blocco, mentre riprendere da un
     * confine non richiede niente. Buttare al massimo 15 byte è il prezzo
     * migliore che si potesse pagare.
     */
    private fun allineaABlocco(parziale: File): Long {
        if (!parziale.exists()) return 0L
        val lunghezza = parziale.length()
        val allineata = lunghezza - (lunghezza % MegaCrypto.BLOCCO)
        if (allineata != lunghezza) {
            RandomAccessFile(parziale, "rw").use { it.setLength(allineata) }
        }
        return allineata
    }
}
