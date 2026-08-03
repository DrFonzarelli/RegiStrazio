package com.example.registrazio.data

import com.example.registrazio.data.model.Cartella
import com.example.registrazio.data.model.Commento
import com.example.registrazio.data.model.Traccia
import com.example.registrazio.data.model.Utente
import com.example.registrazio.data.model.VotoStella

/**
 * Gli stessi dati finti che stanno in `const folders = [...]` nel prototipo.
 *
 * Servono per verificare la UI contro l'originale prima che Firestore e MEGA
 * siano collegati: cambiando solo la sorgente, le schermate restano identiche.
 */
object DemoData {

    /** L'utente locale nel prototipo è `ME = 'T'`, mostrato come "Tu". */
    const val MY_UID = "demo-me"

    val me = Utente(appUid = MY_UID, nome = "Tu", colore = "t")

    private fun c(
        id: Int,
        tracciaId: String,
        autore: String,
        nome: String,
        time: Int,
        testo: String
    ) = Commento(
        id = "demo-$id",
        tracciaId = tracciaId,
        appUid = if (autore == "T") MY_UID else "demo-${autore.lowercase()}",
        autoreNome = nome,
        autoreColore = autore.lowercase(),
        timestampSecondi = time.toFloat(),
        testo = testo
    )

    val cartelle = listOf(
        Cartella(id = "f1", nome = "Prova 12 giugno", linkMega = "", megaFolderId = "DEMO_F1", numTracce = 10),
        Cartella(id = "f2", nome = "Prova 5 giugno", linkMega = "", megaFolderId = "DEMO_F2", numTracce = 2),
        Cartella(id = "f3", nome = "Prova 29 maggio", linkMega = "", megaFolderId = "DEMO_F3", numTracce = 1)
    )

    val tracce: List<Traccia> = listOf(
        Traccia(
            id = "t1", cartellaId = "f1", titolo = "Idea ritornello nuovo",
            durataSecondi = 158, scaricata = false,
            mioVoto = VotoStella.PIENA, votiPieni = 3, votiMezzi = 1, ascolti = 14,
            playBuckets = listOf(
                2f, 3f, 2f, 4f, 3f, 5f, 6f, 9f, 14f, 18f, 16f, 10f,
                6f, 4f, 3f, 5f, 7f, 8f, 6f, 4f, 3f, 2f, 3f, 2f
            ),
            commenti = listOf(
                c(5, "t1", "M", "Marco", 12, "partenza fortissima, teniamola così"),
                c(6, "t1", "L", "Luca", 70, "qui secondo me serve un giro di basso diverso, magari più semplice per lasciare respiro alla voce"),
                c(7, "t1", "A", "Ale", 130, "guarda questo accordo, mi sembra simile a uno che avevamo provato")
            )
        ),
        Traccia(
            id = "t2", cartellaId = "f1", titolo = "Strofa base + drum",
            durataSecondi = 205, scaricata = true,
            mioVoto = VotoStella.MEZZA, votiPieni = 0, votiMezzi = 2, ascolti = 7,
            commenti = listOf(
                c(1, "t2", "A", "Ale", 38, "bella entrata di basso qui"),
                c(2, "t2", "M", "Marco", 102, "qui rallenterei un attimo, secondo me il fill di batteria arriva un filo presto rispetto al resto e disturba la transizione verso il ritornello"),
                c(3, "t2", "A", "Ale", 170, "ottimo il break qui, teniamolo")
            )
        ),
        Traccia(
            id = "t3", cartellaId = "f1", titolo = "Outro strumentale",
            durataSecondi = 96,
            commenti = listOf(
                c(4, "t3", "L", "Luca", 40, "qui ci starebbe un delay sulla chitarra"),
                c(8, "t3", "M", "Marco", 60, "concordo con Luca, magari anche un filo di riverbero in più")
            )
        ),
        Traccia(id = "t3b", cartellaId = "f1", titolo = "Ponte strumentale", durataSecondi = 88),
        Traccia(
            id = "t3c", cartellaId = "f1", titolo = "Improvvisazione chitarra",
            durataSecondi = 142, mioVoto = VotoStella.MEZZA, votiMezzi = 1,
            commenti = listOf(
                c(20, "t3c", "A", "Ale", 50, "questo assolo mi piace, lo terrei")
            )
        ),
        Traccia(id = "t3d", cartellaId = "f1", titolo = "Idea strofa 2", durataSecondi = 110, scaricata = true),
        Traccia(
            id = "t3e", cartellaId = "f1", titolo = "Prova voce sola", durataSecondi = 75,
            commenti = listOf(
                c(21, "t3e", "L", "Luca", 20, "la voce qui è ancora acerba ma il fraseggio funziona")
            )
        ),
        Traccia(
            id = "t3f", cartellaId = "f1", titolo = "Groove basso e batteria",
            durataSecondi = 130, mioVoto = VotoStella.PIENA, votiPieni = 2
        ),
        Traccia(
            id = "t3g", cartellaId = "f1", titolo = "Finale alternativo", durataSecondi = 95,
            commenti = listOf(
                c(22, "t3g", "M", "Marco", 60, "proviamo a chiudere così invece del solito finale")
            )
        ),
        Traccia(
            id = "t3h", cartellaId = "f1", titolo = "Take completo prova",
            durataSecondi = 225, scaricata = true, mioVoto = VotoStella.MEZZA, votiMezzi = 1
        ),

        Traccia(
            id = "t4", cartellaId = "f2", titolo = "Riff verse A",
            durataSecondi = 120, votiPieni = 1, votiMezzi = 2,
            commenti = listOf(
                c(9, "t4", "A", "Ale", 8, "proviamo a suonarlo un tono più basso?"),
                c(10, "t4", "L", "Luca", 55, "per me va bene così com è adesso")
            )
        ),
        Traccia(
            id = "t5", cartellaId = "f2", titolo = "Jam libera",
            durataSecondi = 340, mioVoto = VotoStella.PIENA, votiPieni = 4, ascolti = 22,
            commenti = listOf(
                c(11, "t5", "M", "Marco", 40, "questo pezzo di improvvisazione è oro, non perdiamolo"),
                c(12, "t5", "A", "Ale", 210, "da qui in poi si perde leggermente il filo, potremmo tagliare e usare solo la prima parte per farne una canzone vera"),
                c(13, "t5", "L", "Luca", 298, "il finale mi ricorda vagamente un altro giro nostro, bello")
            )
        ),

        Traccia(
            id = "t6", cartellaId = "f3", titolo = "Take 1 canzone nuova", durataSecondi = 180,
            commenti = listOf(
                c(14, "t6", "M", "Marco", 5, "prima take, incerta ma la lascio come riferimento"),
                c(15, "t6", "A", "Ale", 95, "la voce qui è stonata ma la melodia mi piace molto, la terrei come base"),
                c(16, "t6", "L", "Luca", 150, "per il prossimo giro proviamo con la chitarra pulita invece che distorta")
            )
        )
    )

    /**
     * Tracce placeholder per una cartella MEGA appena collegata — `generateFakeTracks`
     * nel prototipo. Spariranno quando la lista arriverà davvero dall'API MEGA.
     */
    fun generateFakeTracks(folderId: String, count: Int): List<Traccia> {
        val names = listOf(
            "Take 1", "Idea riff", "Strofa base", "Ritornello", "Outro",
            "Jam libera", "Intro", "Ponte", "Take 2", "Groove"
        )
        return (0 until count).map { i ->
            Traccia(
                id = "${folderId}_t$i",
                cartellaId = folderId,
                titolo = names[i % names.size],
                durataSecondi = 80 + (i * 37) % 200
            )
        }
    }
}
