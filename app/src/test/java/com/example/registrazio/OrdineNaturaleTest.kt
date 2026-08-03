package com.example.registrazio

import com.example.registrazio.util.OrdineNaturale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdineNaturaleTest {

    private fun ordina(vararg nomi: String) = nomi.sortedWith(OrdineNaturale)

    @Test
    fun `i numeri contano come numeri, non come testo`() {
        assertEquals(
            listOf("Take 1.mp3", "Take 2.mp3", "Take 10.mp3"),
            ordina("Take 10.mp3", "Take 2.mp3", "Take 1.mp3")
        )
    }

    @Test
    fun `le maiuscole non spostano l'ordine`() {
        assertEquals(
            listOf("intro.mp3", "Outro.mp3", "prova.mp3"),
            ordina("prova.mp3", "Outro.mp3", "intro.mp3")
        )
    }

    @Test
    fun `gli zeri iniziali non cambiano il valore del numero`() {
        assertEquals(
            listOf("Prova 3", "Prova 20"),
            ordina("Prova 20", "Prova 3")
        )
        // "03" e "3" valgono uguale come numero, ma non sono la stessa stringa:
        // devono comunque avere un ordine stabile invece di risultare equivalenti.
        assertTrue(OrdineNaturale.compare("Prova 03", "Prova 3") != 0)
    }

    @Test
    fun `numeri piu' lunghi di un Long non mandano in errore il confronto`() {
        val enorme = "traccia " + "9".repeat(40)
        val ancoraPiuEnorme = "traccia " + "9".repeat(41)
        assertTrue(OrdineNaturale.compare(enorme, ancoraPiuEnorme) < 0)
    }

    /**
     * `sortedWith` solleva "Comparison method violates its general contract" se il
     * comparatore non è coerente. Meglio scoprirlo qui che con l'app in mano.
     */
    @Test
    fun `il comparatore rispetta il contratto`() {
        val casi = listOf("a", "A", "a1", "a01", "a2", "a10", "b", "1", "01", "", "a1b", "a1B", "10a")

        for (x in casi) for (y in casi) {
            assertEquals(
                "antisimmetria fra \"$x\" e \"$y\"",
                -OrdineNaturale.compare(y, x).coerceIn(-1, 1),
                OrdineNaturale.compare(x, y).coerceIn(-1, 1)
            )
        }

        for (x in casi) for (y in casi) for (z in casi) {
            if (OrdineNaturale.compare(x, y) < 0 && OrdineNaturale.compare(y, z) < 0) {
                assertTrue(
                    "transitività: \"$x\" < \"$y\" < \"$z\"",
                    OrdineNaturale.compare(x, z) < 0
                )
            }
        }
    }
}
