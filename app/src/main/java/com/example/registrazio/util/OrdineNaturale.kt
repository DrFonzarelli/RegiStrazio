package com.example.registrazio.util

/**
 * Confronto fra nomi che tratta i numeri come numeri.
 *
 * In ordine alfabetico puro "Take 10" verrebbe prima di "Take 2", perché il
 * carattere `1` viene prima del `2`. Per dei nomi di prove — che quasi sempre
 * finiscono con un numero progressivo — è l'ordine sbagliato.
 *
 * Il confronto procede a pezzi: le cifre consecutive si confrontano come
 * numero, tutto il resto carattere per carattere ignorando maiuscole e
 * minuscole. A parità, decide il confronto normale, così l'ordine resta stabile
 * anche fra nomi che differiscono solo per gli zeri iniziali ("02" e "2").
 */
object OrdineNaturale : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0

        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]

            if (ca.isDigit() && cb.isDigit()) {
                val fineA = fineCifre(a, i)
                val fineB = fineCifre(b, j)

                // Confronto per lunghezza dopo aver tolto gli zeri iniziali:
                // regge numeri più lunghi di quanto entri in un Long.
                val numA = a.substring(i, fineA).trimStart('0')
                val numB = b.substring(j, fineB).trimStart('0')

                if (numA.length != numB.length) return numA.length - numB.length
                if (numA != numB) return numA.compareTo(numB)

                i = fineA
                j = fineB
            } else {
                val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (diff != 0) return diff
                i++
                j++
            }
        }

        val resto = (a.length - i) - (b.length - j)
        // Ultimo appiglio: senza questo "02" e "2" risulterebbero equivalenti e
        // il loro ordine dipenderebbe da come sono arrivati.
        return if (resto != 0) resto else a.compareTo(b)
    }

    private fun fineCifre(testo: String, da: Int): Int {
        var fine = da
        while (fine < testo.length && testo[fine].isDigit()) fine++
        return fine
    }
}
