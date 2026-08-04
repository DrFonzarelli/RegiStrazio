package com.example.registrazio.domain.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * I commenti scritti **fuori** dall'interfaccia, cioè dalla notifica.
 *
 * Il ricevitore della risposta rapida gira in un `BroadcastReceiver`, che non ha
 * ViewModel e non può toccare lo stato dell'interfaccia. Scrive su Room, che è
 * la fonte di verità, e annuncia qui cosa ha scritto: se l'app è viva, il
 * ViewModel lo raccoglie e la card si aggiorna sotto gli occhi. Se non lo è, al
 * prossimo avvio il commento è già nel database e arriva da lì.
 *
 * `extraBufferCapacity = 8`: chi emette è un receiver che non può sospendere, e
 * un `SharedFlow` senza buffer lo bloccherebbe quando nessuno ascolta.
 */
object CommentiDaFuori {

    private val _nuovi = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Id della traccia su cui è appena comparso un commento scritto dalla notifica. */
    val nuovi: SharedFlow<String> = _nuovi.asSharedFlow()

    fun annuncia(tracciaId: String) {
        _nuovi.tryEmit(tracciaId)
    }
}
