package com.example.registrazio.data.remote

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Link pubblico a una cartella MEGA, spezzato nelle sue due parti.
 *
 * La chiave sta dopo il `#`, che i browser non mandano mai al server: è il
 * motivo per cui MEGA può non conoscere il contenuto di ciò che ospita. Come
 * conseguenza pratica, **chi ha il link ha accesso**: va trattato come una
 * password, non come un indirizzo.
 */
data class LinkMega(val folderId: String, val chiave: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is LinkMega && folderId == other.folderId && chiave.contentEquals(other.chiave)

    override fun hashCode(): Int = 31 * folderId.hashCode() + chiave.contentHashCode()

    companion object {
        /**
         * Riconosce i due formati in circolazione:
         * - attuale: `https://mega.nz/folder/<id>#<chiave>`
         * - vecchio: `https://mega.nz/#F!<id>!<chiave>`
         *
         * Restituisce `null` se manca l'id, se manca la chiave, o se la chiave
         * non decodifica a 16 byte — meglio un errore subito che una cartella
         * collegata che poi mostra nomi illeggibili.
         */
        fun parse(link: String): LinkMega? {
            val pulito = link.trim()
            val (id, chiaveB64) = when {
                pulito.contains("/folder/") -> {
                    val dopo = pulito.substringAfter("/folder/")
                    dopo.substringBefore('#').substringBefore('/').trim() to
                        dopo.substringAfter('#', "").substringBefore('/').trim()
                }
                pulito.contains("#F!") -> {
                    val pezzi = pulito.substringAfter("#F!").split("!")
                    (pezzi.getOrNull(0)?.trim() ?: "") to (pezzi.getOrNull(1)?.trim() ?: "")
                }
                else -> return null
            }
            if (id.isEmpty() || chiaveB64.isEmpty()) return null
            val chiave = runCatching { MegaCrypto.base64Decode(chiaveB64) }.getOrNull() ?: return null
            if (chiave.size != 16) return null
            return LinkMega(id, chiave)
        }

        /**
         * Pesca un link di cartella dentro un testo qualsiasi.
         *
         * Serve per la condivisione: MEGA non manda l'indirizzo nudo ma una
         * frase intorno ("Guarda questa cartella: https://mega.nz/folder/…"),
         * e passarla così com'è a [parse] non funzionerebbe.
         *
         * Restituisce il testo del link, non il [LinkMega] già decomposto: chi
         * chiama lo mette nel campo perché l'utente lo veda e lo confermi.
         */
        fun cercaNelTesto(testo: String): String? {
            for (candidato in SPEZZA_TESTO.split(testo)) {
                // Un link dentro una frase si porta dietro la punteggiatura,
                // spesso da entrambi i lati: «(https://mega.nz/folder/…),».
                // Toglierla è sicuro perché nessuno di questi caratteri
                // appartiene all'alfabeto base64 della chiave.
                val ripulito = candidato
                    .trimStart('(', '[', '<', '"', '\'')
                    .trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'')
                if (parse(ripulito) != null) return ripulito
            }
            return null
        }

        /** Spazi, a capo e tabulazioni: tutto ciò che separa un URL dal resto. */
        private val SPEZZA_TESTO = Regex("\\s+")
    }
}

/** Un file audio trovato dentro una cartella MEGA, con il nome già in chiaro. */
data class FileMega(
    /** Node handle: serve per chiedere l'URL di download. */
    val handle: String,
    val nome: String,
    val dimensioneByte: Long,
    /** Chiave AES e nonce del file, per decifrarne il contenuto. */
    val chiave: MegaCrypto.ChiaveFile
)

/** Errore restituito da MEGA, o problema nel parlarci. */
class MegaException(val codice: Int?, message: String) : Exception(message)

/**
 * Esito della lettura di una cartella, con il conteggio di ciò che è stato
 * scartato e perché.
 *
 * Senza questi numeri "non ho trovato niente" resta ambiguo: una cartella di
 * sole foto e una cartella di cui non sappiamo decifrare i nomi darebbero lo
 * stesso messaggio, e sono due problemi opposti.
 */
data class EsitoElenco(
    val audio: List<FileMega>,
    /** Nome della cartella su MEGA, `null` se non siamo riusciti a leggerlo. */
    val nomeCartella: String?,
    /** Nodi di tipo file presenti nella cartella, prima di qualunque filtro. */
    val fileTotali: Int,
    /** File di cui non siamo riusciti a decifrare nome o chiave. */
    val nonDecifrati: Int,
    /** Estensioni viste fra i file decifrati ma scartati perché non audio. */
    val estensioniScartate: Set<String>
)

/**
 * Client per l'API pubblica di MEGA — solo le due operazioni che servono:
 * elencare i file di una cartella e farsi dare l'URL da cui scaricarne uno.
 *
 * Non usiamo il MegaSDK ufficiale: richiederebbe librerie native C++ via JNI
 * per due sole chiamate HTTP.
 */
class MegaApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private val sequenza = AtomicInteger(0)

    /**
     * Elenca i file audio della cartella, con i nomi già decifrati.
     *
     * Le sottocartelle vengono ignorate: il gruppo tiene le prove in una
     * cartella piatta e scendere ricorsivamente aprirebbe domande
     * (come si mostrano? si appiattiscono?) che per ora non servono.
     */
    suspend fun elencaFileAudio(link: LinkMega): EsitoElenco {
        val risposta = chiama(link.folderId, jsonComando("f", mapOf("c" to 1, "r" to 1)))
        // I soli nomi dei campi, mai i valori: `a` e `k` sono il contenuto cifrato
        // e la chiave, e questi log finiscono incollati nelle conversazioni.
        Log.d(TAG, "Campi della risposta: ${risposta.asJsonObject.keySet()}")

        val nodi = risposta.asJsonObject.getAsJsonArray("f")
            ?: throw MegaException(null, "MEGA ha risposto senza l'elenco dei file.")
        Log.d(TAG, "Nodi ricevuti: ${nodi.size()}")

        val audio = mutableListOf<FileMega>()
        val estensioniScartate = mutableSetOf<String>()
        val candidatiRadice = mutableListOf<JsonObject>()
        var fileTotali = 0
        var nonDecifrati = 0

        for (elemento in nodi) {
            val nodo = elemento as? JsonObject ?: continue
            val tipo = nodo.get("t")?.asInt

            // Tutto ciò che non è un file è un possibile nodo radice. Quale sia
            // davvero lo decidiamo dopo: due tentativi di indovinarlo dal solo
            // `t` sono già falliti, meglio raccoglierli e provarli tutti.
            if (tipo != 0) {
                candidatiRadice += nodo
                continue
            }
            fileTotali++

            val handle = nodo.get("h")?.asString
            val campoK = nodo.get("k")?.asString
            val attributi = nodo.get("a")?.asString
            if (handle == null || campoK == null || attributi == null) {
                nonDecifrati++
                continue
            }

            val chiaveNodo = MegaCrypto.decifraChiaveNodo(campoK, link.chiave)
            val chiaveFile = chiaveNodo?.let { MegaCrypto.chiaveFileDa(it) }
            val chiaveAttr = chiaveNodo?.let { MegaCrypto.chiaveAttributi(it) }
            val nome = chiaveAttr?.let { MegaCrypto.nomeDaAttributi(attributi, it) }
            if (chiaveFile == null || nome == null) {
                nonDecifrati++
                continue
            }

            val estensione = nome.substringAfterLast('.', "").lowercase()
            if (estensione !in ESTENSIONI_AUDIO) {
                estensioniScartate += estensione.ifEmpty { "(senza estensione)" }
                continue
            }

            audio += FileMega(
                handle = handle,
                nome = nome,
                dimensioneByte = nodo.get("s")?.asLong ?: 0L,
                chiave = chiaveFile
            )
        }

        return EsitoElenco(
            audio = audio,
            nomeCartella = nomeDellaCartella(candidatiRadice, link),
            fileTotali = fileTotali,
            nonDecifrati = nonDecifrati,
            estensioniScartate = estensioniScartate
        )
    }

    /**
     * Nome della cartella condivisa.
     *
     * Non diamo per scontato quale nodo sia la radice: proviamo a decifrare gli
     * attributi di tutti i nodi non-file e teniamo il primo che funziona.
     *
     * Verificato su una cartella vera: la radice di una condivisione ha `t = 1`
     * come una cartella qualsiasi, **ha un genitore**, e il suo handle **non è**
     * l'id del link — quello è un handle di condivisione, un'altra cosa. Quindi
     * l'ordinamento qui sotto è solo una preferenza opportunistica per altre
     * forme di cartella: non fidarsene come criterio di riconoscimento.
     *
     * Anche il modo di cifrare varia: certi nodi portano un campo `k` come tutti
     * gli altri, per altri gli attributi sono cifrati direttamente con la chiave
     * del link. Vengono provate entrambe le strade.
     */
    private fun nomeDellaCartella(candidati: List<JsonObject>, link: LinkMega): String? {
        val ordinati = candidati.sortedBy { nodo ->
            when {
                nodo.get("h")?.asString == link.folderId -> 0
                nodo.get("p")?.asString.isNullOrEmpty() -> 1
                else -> 2
            }
        }

        Log.d(TAG, "Nodi candidati a radice: ${ordinati.size}")
        for ((indice, nodo) in ordinati.withIndex()) {
            val attributi = nodo.get("a")?.asString
            val campoK = nodo.get("k")?.asString
            Log.d(
                TAG,
                "  candidato $indice: t=${nodo.get("t")?.asString} " +
                    "handleUgualeAlLink=${nodo.get("h")?.asString == link.folderId} " +
                    "senzaGenitore=${nodo.get("p")?.asString.isNullOrEmpty()} " +
                    "attributi=${attributi != null} chiave=${campoK != null}"
            )
            if (attributi == null) continue

            campoK?.let { MegaCrypto.decifraChiaveNodo(it, link.chiave) }
                ?.let { MegaCrypto.chiaveAttributi(it) }
                ?.let { MegaCrypto.nomeDaAttributi(attributi, it) }
                ?.let {
                    Log.d(TAG, "  -> nome letto dal candidato $indice tramite il suo campo k")
                    return it
                }

            MegaCrypto.nomeDaAttributi(attributi, link.chiave)?.let {
                Log.d(TAG, "  -> nome letto dal candidato $indice con la chiave del link")
                return it
            }

            Log.d(TAG, "  -> candidato $indice: nessuna delle due chiavi decifra gli attributi")
        }

        Log.d(TAG, "Nome della cartella non ricavato: si usa il ripiego")
        return null
    }

    /**
     * URL temporaneo da cui leggere i byte del file.
     *
     * Attenzione: i byte che arrivano da qui sono **cifrati** (AES-CTR). Vanno
     * decifrati con [FileMega.chiave] prima di poter essere riprodotti o salvati.
     *
     * L'URL scade dopo poche ore, quindi va richiesto al momento del bisogno e
     * non conservato tra una sessione e l'altra.
     */
    suspend fun urlDiDownload(link: LinkMega, handle: String): String {
        val risposta = chiama(
            link.folderId,
            jsonComando("g", mapOf("g" to 1, "n" to handle, "ssl" to 2))
        )
        return risposta.asJsonObject.get("g")?.asString
            ?: throw MegaException(null, "MEGA non ha restituito l'indirizzo del file.")
    }

    // ---------- interno ----------

    private fun jsonComando(azione: String, extra: Map<String, Any>): String {
        val comando = JsonObject().apply {
            addProperty("a", azione)
            extra.forEach { (chiave, valore) ->
                when (valore) {
                    is Number -> addProperty(chiave, valore)
                    else -> addProperty(chiave, valore.toString())
                }
            }
        }
        return JsonArray().apply { add(comando) }.toString()
    }

    /**
     * Esegue un comando e restituisce il primo elemento della risposta.
     *
     * MEGA risponde sempre con un array, e segnala gli errori con un numero
     * negativo al posto dell'oggetto. Il codice -3 significa "riprova tra poco"
     * ed è l'unico su cui ha senso insistere.
     */
    private suspend fun chiama(folderId: String, corpo: String): JsonElement =
        withContext(Dispatchers.IO) {
            var attesa = 500L
            repeat(MAX_TENTATIVI) { tentativo ->
                val elemento = eseguiUnaVolta(folderId, corpo)
                val codice = codiceErrore(elemento)
                if (codice == null) return@withContext elemento
                if (codice != EAGAIN || tentativo == MAX_TENTATIVI - 1) {
                    throw MegaException(codice, messaggioPerCodice(codice))
                }
                delay(attesa)
                attesa *= 2
            }
            throw MegaException(EAGAIN, messaggioPerCodice(EAGAIN))
        }

    private fun eseguiUnaVolta(folderId: String, corpo: String): JsonElement {
        val url = "$BASE?id=${sequenza.getAndIncrement()}&n=$folderId"
        val richiesta = Request.Builder()
            .url(url)
            .post(corpo.toRequestBody("application/json".toMediaType()))
            .build()

        val testo = try {
            client.newCall(richiesta).execute().use { risposta ->
                if (!risposta.isSuccessful) {
                    throw MegaException(null, "MEGA ha risposto con un errore HTTP ${risposta.code}.")
                }
                risposta.body?.string().orEmpty()
            }
        } catch (e: MegaException) {
            throw e
        } catch (e: Exception) {
            throw MegaException(null, "Non riesco a contattare MEGA. Controlla la connessione.")
        }

        val radice = runCatching { JsonParser.parseString(testo) }.getOrNull()
            ?: throw MegaException(null, "Risposta di MEGA illeggibile.")

        // Un errore secco arriva come numero nudo invece che come array.
        if (radice.isJsonPrimitive) return radice
        val array = radice as? JsonArray
            ?: throw MegaException(null, "Risposta di MEGA in un formato inatteso.")
        if (array.size() == 0) throw MegaException(null, "MEGA ha risposto senza dati.")
        return array.get(0)
    }

    /** Il codice d'errore se l'elemento è un numero negativo, altrimenti `null`. */
    private fun codiceErrore(elemento: JsonElement): Int? {
        if (!elemento.isJsonPrimitive) return null
        val numero = runCatching { elemento.asInt }.getOrNull() ?: return null
        return if (numero < 0) numero else null
    }

    private fun messaggioPerCodice(codice: Int): String = when (codice) {
        -9 -> "Cartella non trovata. Il link potrebbe essere stato revocato."
        -11 -> "Accesso negato a questa cartella."
        -16 -> "L'account che ha creato il link è stato sospeso."
        -17 -> "MEGA ha superato il limite di traffico. Riprova più tardi."
        -18 -> "La cartella è temporaneamente non disponibile. Riprova."
        EAGAIN -> "MEGA è occupato. Riprova tra qualche istante."
        -2 -> "Il link non è nel formato che MEGA si aspetta."
        else -> "MEGA ha risposto con l'errore $codice."
    }

    private fun haEstensioneAudio(nome: String): Boolean =
        nome.substringAfterLast('.', "").lowercase() in ESTENSIONI_AUDIO

    companion object {
        private const val TAG = "MegaApi"
        private const val BASE = "https://g.api.mega.co.nz/cs"
        private const val EAGAIN = -3
        private const val MAX_TENTATIVI = 4

        val ESTENSIONI_AUDIO = setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aif", "aiff"
        )
    }
}
