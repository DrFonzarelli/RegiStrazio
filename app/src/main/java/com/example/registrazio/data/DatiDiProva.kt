package com.example.registrazio.data

import com.example.registrazio.data.model.VotoStella

/**
 * Il banco di prova: due cartelle MEGA vere, già collegate al primo avvio, con
 * sopra commenti e voti di un gruppo immaginario.
 *
 * Ha preso il posto delle cartelle finte di prima. Quelle avevano tracce senza
 * un file dietro, e servivano a guardare l'interfaccia prima che MEGA
 * funzionasse; adesso che funziona sono peggio che inutili — una traccia che
 * suona su un timer finto fa passare per riuscita una prova che non ha provato
 * niente.
 *
 * **I commenti si agganciano per posizione, non per nome file.** Gli id delle
 * tracce sono i node handle di MEGA, che si conoscono solo dopo aver letto la
 * cartella: qui non possono stare scritti. [CommentoDiProva.traccia] è quindi
 * la posizione nell'elenco ordinato (1 = la prima), e le posizioni che non
 * esistono si saltano. Aggiungere o rinominare un file su MEGA non rompe
 * niente, al massimo sposta un commento di una traccia.
 *
 * ## Sui link
 *
 * `linkMega` contiene la chiave di decrittazione dopo il `#`: chi legge questo
 * file può aprire le cartelle. È una scelta consapevole per la fase di prova.
 * Quando non serviranno più non basta cancellarle da qui — restano nella storia
 * dei commit — va **rigenerato il link dalla cartella su MEGA**, che è l'unica
 * cosa che invalida davvero la chiave.
 *
 * ## Cosa non fa
 *
 * Niente di tutto questo va su Firestore. I commenti nascono già
 * [com.example.registrazio.data.model.StatoSync.SINCRONIZZATO] proprio perché
 * il tasto Sincronizza li ignori: sono arredamento, e un arredamento caricato
 * sul database vero non se ne andrebbe più.
 */
object DatiDiProva {

    /** Gli altri membri del gruppo immaginario. Il colore è una chiave di `PALETTE_KEYS`. */
    enum class Autore(val nomeVisibile: String, val colore: String) {
        MARCO("Marco", "m"),
        LUCA("Luca", "l"),
        ALE("Ale", "a");

        /** Stabile fra un avvio e l'altro: i commenti di Marco restano di Marco. */
        val appUid: String get() = "prova-${name.lowercase()}"
    }

    /**
     * @param traccia posizione nell'elenco ordinato della cartella, da 1.
     * @param secondi minutaggio del commento; se supera la durata della traccia
     *   la timeline lo mostra in fondo, senza rompersi.
     */
    data class CommentoDiProva(
        val traccia: Int,
        val autore: Autore,
        val secondi: Int,
        val testo: String
    )

    /**
     * Voti e statistiche di una traccia.
     *
     * [mio] è il voto dell'utente locale, [pieni] e [mezzi] sono i contatori di
     * gruppo — e comprendono già il voto locale, come nel prototipo.
     */
    data class VotoDiProva(
        val traccia: Int,
        val mio: VotoStella = VotoStella.NESSUNO,
        val pieni: Int = 0,
        val mezzi: Int = 0,
        val ascolti: Int = 0
    )

    data class CartellaDiProva(
        val linkMega: String,
        val commenti: List<CommentoDiProva> = emptyList(),
        val voti: List<VotoDiProva> = emptyList()
    )

    val cartelle = listOf(
        CartellaDiProva(
            linkMega = "https://mega.nz/folder/d2UBzSTZ#83ma2FXfvLnmDSthSAjEyw",
            commenti = listOf(
                CommentoDiProva(1, Autore.MARCO, 12, "partenza fortissima, teniamola così"),
                CommentoDiProva(1, Autore.LUCA, 68, "qui secondo me serve un giro di basso diverso, magari più semplice per lasciare respiro alla voce"),
                CommentoDiProva(1, Autore.ALE, 125, "guarda questo accordo, mi sembra simile a uno che avevamo provato l'altra volta"),
                CommentoDiProva(2, Autore.ALE, 38, "bella entrata di basso qui"),
                CommentoDiProva(2, Autore.MARCO, 96, "qui rallenterei un attimo: il fill di batteria arriva un filo presto rispetto al resto e disturba la transizione"),
                CommentoDiProva(3, Autore.LUCA, 40, "qui ci starebbe un delay sulla chitarra"),
                CommentoDiProva(3, Autore.MARCO, 61, "concordo con Luca, magari anche un filo di riverbero in più"),
                CommentoDiProva(4, Autore.ALE, 22, "questo pezzo lo riascolterei a mente fresca, ora non saprei dire"),
                CommentoDiProva(5, Autore.LUCA, 15, "la voce è ancora acerba ma il fraseggio funziona"),
                CommentoDiProva(6, Autore.MARCO, 55, "proviamo a chiudere così invece del solito finale")
            ),
            voti = listOf(
                VotoDiProva(1, mio = VotoStella.PIENA, pieni = 3, mezzi = 1, ascolti = 14),
                VotoDiProva(2, mio = VotoStella.MEZZA, mezzi = 2, ascolti = 7),
                VotoDiProva(3, ascolti = 4),
                VotoDiProva(5, mio = VotoStella.MEZZA, mezzi = 1, ascolti = 2),
                VotoDiProva(6, pieni = 2, ascolti = 9)
            )
        ),
        CartellaDiProva(
            linkMega = "https://mega.nz/folder/A6kViDhL#7Qp6bT7bmU5RAJJBLdkygA",
            commenti = listOf(
                CommentoDiProva(1, Autore.ALE, 8, "proviamo a suonarlo un tono più basso?"),
                CommentoDiProva(1, Autore.LUCA, 52, "per me va bene così com'è adesso"),
                CommentoDiProva(2, Autore.MARCO, 40, "questo pezzo di improvvisazione è oro, non perdiamolo"),
                CommentoDiProva(2, Autore.ALE, 150, "da qui in poi si perde leggermente il filo: potremmo tagliare e tenere solo la prima parte"),
                CommentoDiProva(3, Autore.MARCO, 5, "prima take, incerta, la lascio come riferimento"),
                CommentoDiProva(3, Autore.ALE, 88, "la voce qui è stonata ma la melodia mi piace molto, la terrei come base"),
                CommentoDiProva(4, Autore.LUCA, 30, "per il prossimo giro proviamo con la chitarra pulita invece che distorta")
            ),
            voti = listOf(
                VotoDiProva(1, pieni = 1, mezzi = 2, ascolti = 5),
                VotoDiProva(2, mio = VotoStella.PIENA, pieni = 4, ascolti = 22),
                VotoDiProva(4, mio = VotoStella.MEZZA, mezzi = 1, ascolti = 3)
            )
        )
    )

    /**
     * I 24 secchielli del grafico "punti più ascoltati".
     *
     * Deterministici a partire dal numero di ascolti, invece che a caso: due
     * avvii di fila devono mostrare lo stesso grafico, o guardando l'app non si
     * capirebbe se una differenza è un bug o è il seme che è cambiato. La forma
     * è una gobba spostata verso l'inizio — che è dove si riascolta di più.
     */
    fun buckets(ascolti: Int): List<Float> {
        if (ascolti <= 0) return List(24) { 0f }
        return List(24) { i ->
            val distanza = (i - 8f) / 7f
            val gobba = kotlin.math.exp(-distanza * distanza)
            val increspatura = 1f + 0.25f * kotlin.math.sin(i * 1.7f)
            (ascolti * gobba * increspatura).coerceAtLeast(0f)
        }
    }
}
