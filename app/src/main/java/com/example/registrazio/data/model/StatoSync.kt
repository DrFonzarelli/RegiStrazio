package com.example.registrazio.data.model

/**
 * Stato di una riga locale rispetto a Firestore.
 *
 * La regola che regge tutta la persistenza: **quello che l'utente fa viene
 * salvato sul telefono all'istante**, e Firestore lo vede solo quando qualcuno
 * preme Sincronizza. Il locale è la fonte di verità finché quel momento non
 * arriva; niente può sparire chiudendo l'app.
 *
 * Non serve uno stato "modificato" distinto da [LOCALE]: l'upload riscrive il
 * documento intero, quindi "creato qui" e "cambiato qui" si caricano allo
 * stesso modo. Modificare una riga già sincronizzata la riporta a [LOCALE].
 */
enum class StatoSync {
    /** C'è solo sul telefono, o è cambiata dopo l'ultima sincronizzazione. */
    LOCALE,

    /** Identica a com'è su Firestore: niente da caricare. */
    SINCRONIZZATO,

    /**
     * Cancellata qui, ma esisteva su Firestore: va tolta anche lì.
     *
     * Resta in archivio, nascosta all'interfaccia, finché la sincronizzazione
     * non l'ha rimossa anche dall'altra parte. Cancellarla subito vorrebbe dire
     * vedersela ricomparire alla sincronizzazione successiva.
     */
    DA_ELIMINARE,

    /** L'ultimo tentativo di caricamento è fallito. Si riprova. */
    ERRORE
}
