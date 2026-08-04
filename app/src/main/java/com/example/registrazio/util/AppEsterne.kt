package com.example.registrazio.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Apre MEGA: l'app se c'è, altrimenti il sito nel browser.
 *
 * Serve a chi non ha il link già copiato. Da lì il percorso comodo è "Ottieni
 * link" → "Condividi" → RegiStrazio, che riporta indietro senza copia-incolla.
 *
 * `getLaunchIntentForPackage` restituisce `null` per un pacchetto non
 * dichiarato in `<queries>` nel manifest, **anche se l'app è installata**: è la
 * regola sulla visibilità dei pacchetti introdotta da Android 11. La
 * dichiarazione c'è; se un giorno il nome del pacchetto MEGA cambiasse, il
 * sintomo sarebbe questo — si apre il browser invece dell'app, senza errori.
 *
 * Restituisce `false` se non è stato possibile aprire nemmeno il browser, così
 * chi chiama può dirlo invece di lasciare il tasto muto.
 */
fun apriMega(context: Context): Boolean {
    val appMega = context.packageManager.getLaunchIntentForPackage(PACCHETTO_MEGA)
    if (appMega != null) {
        return runCatching { context.startActivity(appMega) }.isSuccess
    }
    val browser = Intent(Intent.ACTION_VIEW, Uri.parse(SITO_MEGA))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(browser) }.isSuccess
}

private const val PACCHETTO_MEGA = "mega.privacy.android.app"

/** `/fm` è la schermata dei file, non la pagina di presentazione. */
private const val SITO_MEGA = "https://mega.nz/fm"
