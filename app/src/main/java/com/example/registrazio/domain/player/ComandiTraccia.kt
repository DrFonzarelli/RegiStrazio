package com.example.registrazio.domain.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "Traccia precedente" e "traccia successiva" chieste da fuori l'interfaccia.
 *
 * Qui passa solo la **richiesta**, mai la decisione. Quale sia la traccia dopo
 * dipende da quali cartelle sono collegate, da come sono ordinate le tracce
 * dentro ognuna e da dove siamo adesso — cose che sa il ViewModel e che
 * nessun altro dovrebbe ricalcolare. Un `BroadcastReceiver` che si mettesse a
 * leggere Room per rispondere alla stessa domanda darebbe una seconda risposta
 * possibile, e prima o poi le due divergerebbero: due tasti identici, due
 * comportamenti diversi a seconda di dove li premi.
 *
 * Quindi i tasti della notifica e quelli della barra in ascolto finiscono
 * nello stesso punto. La notifica non salta a niente: chiede, e a saltare è
 * sempre lo stesso codice.
 *
 * **Se l'app non è in memoria non succede niente.** Il ViewModel è chi
 * ascolta, e senza di lui la richiesta cade. In pratica il caso non si
 * presenta: la notifica esiste finché esiste il servizio, e Android che uccide
 * il processo porta via entrambi. Se un domani i comandi dovranno funzionare a
 * processo morto, la strada non è duplicare la logica qui — è farla vivere
 * sotto il ViewModel, dove il servizio possa raggiungerla.
 *
 * `extraBufferCapacity = 4`: chi emette è un receiver che non può sospendere,
 * e un `SharedFlow` senza buffer lo bloccherebbe quando nessuno ascolta.
 */
object ComandiTraccia {

    enum class Direzione { AVANTI, INDIETRO }

    private val _richieste = MutableSharedFlow<Direzione>(extraBufferCapacity = 4)

    val richieste: SharedFlow<Direzione> = _richieste.asSharedFlow()

    fun chiedi(direzione: Direzione) {
        _richieste.tryEmit(direzione)
    }
}
