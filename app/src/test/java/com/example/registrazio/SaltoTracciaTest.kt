package com.example.registrazio

import com.example.registrazio.ui.postoDopoIlSalto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I casi limite del salto fra tracce.
 *
 * Sono tutti aritmetica su un elenco piatto, e si provano qui invece che
 * premendo un tasto trenta volte sul telefono. Interessa soprattutto il verso
 * negativo: in Kotlin `-1 % 5` fa `-1`, e senza il giro in più andare indietro
 * dalla prima traccia darebbe un indice fuori dall'elenco — cioè un crash
 * proprio sul tasto della notifica, dove nessuno vedrebbe cos'è andato storto.
 */
class SaltoTracciaTest {

    @Test
    fun `avanti si sposta di uno`() {
        assertEquals(3, postoDopoIlSalto(posto = 2, passo = 1, quante = 10))
    }

    @Test
    fun `indietro si sposta di uno`() {
        assertEquals(1, postoDopoIlSalto(posto = 2, passo = -1, quante = 10))
    }

    @Test
    fun `avanti dall'ultima torna alla prima`() {
        assertEquals(0, postoDopoIlSalto(posto = 9, passo = 1, quante = 10))
    }

    @Test
    fun `indietro dalla prima va all'ultima`() {
        assertEquals(9, postoDopoIlSalto(posto = 0, passo = -1, quante = 10))
    }

    @Test
    fun `senza niente in ascolto avanti parte dalla prima`() {
        assertEquals(0, postoDopoIlSalto(posto = -1, passo = 1, quante = 10))
    }

    @Test
    fun `senza niente in ascolto indietro parte dall'ultima`() {
        assertEquals(9, postoDopoIlSalto(posto = -1, passo = -1, quante = 10))
    }

    /**
     * Una traccia sola: entrambi i versi restano dove sono.
     *
     * Non è teoria — è una cartella appena collegata con un solo file, e con un
     * `%` scritto male sarebbe l'unico caso in cui l'indice esce dall'elenco.
     */
    @Test
    fun `con una traccia sola si resta li`() {
        assertEquals(0, postoDopoIlSalto(posto = 0, passo = 1, quante = 1))
        assertEquals(0, postoDopoIlSalto(posto = 0, passo = -1, quante = 1))
    }

    @Test
    fun `due tracce si alternano in entrambi i versi`() {
        assertEquals(1, postoDopoIlSalto(posto = 0, passo = 1, quante = 2))
        assertEquals(0, postoDopoIlSalto(posto = 1, passo = 1, quante = 2))
        assertEquals(1, postoDopoIlSalto(posto = 0, passo = -1, quante = 2))
        assertEquals(0, postoDopoIlSalto(posto = 1, passo = -1, quante = 2))
    }

    /** Il giro completo torna al punto di partenza, in entrambi i versi. */
    @Test
    fun `un giro intero riporta all'inizio`() {
        var posto = 0
        repeat(10) { posto = postoDopoIlSalto(posto, 1, 10) }
        assertEquals(0, posto)

        repeat(10) { posto = postoDopoIlSalto(posto, -1, 10) }
        assertEquals(0, posto)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un elenco vuoto e un errore di chi chiama`() {
        postoDopoIlSalto(posto = 0, passo = 1, quante = 0)
    }
}
