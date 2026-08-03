package com.example.registrazio.domain.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.registrazio.data.model.Utente
import java.util.UUID

/**
 * Custode dell'`appUid`: l'UUID che identifica davvero l'utente nel gruppo.
 *
 * Firebase Anonymous Auth non può fare questo lavoro perché rigenera l'UID a
 * ogni reinstall — chi reinstalla perderebbe la proprietà dei propri commenti.
 * L'UUID vive invece qui, in preferenze cifrate incluse nel backup automatico
 * Android, così nella maggior parte dei casi torna da solo dopo un ripristino.
 */
class IdentityManager(context: Context) {

    private val prefs: SharedPreferences = buildPrefs(context)

    var identita: Utente? = leggiIdentita()
        private set

    val haIdentita: Boolean
        get() = identita != null

    /** Percorso A del Gate: profilo nuovo, UUID generato adesso. */
    fun creaNuovaIdentita(nome: String, colore: String): Utente {
        val utente = Utente(
            appUid = UUID.randomUUID().toString(),
            nome = nome.trim(),
            colore = colore
        )
        salva(utente)
        return utente
    }

    /** Percorso B del Gate: l'utente si è riconosciuto in un profilo esistente. */
    fun adottaIdentita(utente: Utente) = salva(utente)

    /** Usato dallo strumento di test "Simula reinstallazione". */
    fun dimentica() {
        prefs.edit().clear().apply()
        identita = null
    }

    private fun salva(utente: Utente) {
        prefs.edit()
            .putString(KEY_UID, utente.appUid)
            .putString(KEY_NOME, utente.nome)
            .putString(KEY_COLORE, utente.colore)
            .putLong(KEY_CREATO, utente.creatoIl)
            .apply()
        identita = utente
    }

    private fun leggiIdentita(): Utente? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val nome = prefs.getString(KEY_NOME, null) ?: return null
        val colore = prefs.getString(KEY_COLORE, null) ?: return null
        return Utente(uid, nome, colore, prefs.getLong(KEY_CREATO, 0L))
    }

    private companion object {
        const val FILE = "registrazio_identity"
        const val KEY_UID = "app_uid"
        const val KEY_NOME = "nome"
        const val KEY_COLORE = "colore"
        const val KEY_CREATO = "creato_il"

        /**
         * Il Keystore può fallire su alcuni dispositivi (chiave corrotta dopo un
         * ripristino, OEM con implementazioni parziali). In quel caso è meglio
         * un fallback in chiaro che un'app che non parte: qui dentro c'è un UUID
         * e un nome scelto dall'utente, non credenziali.
         */
        fun buildPrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("IdentityManager", "EncryptedSharedPreferences non disponibile, uso il fallback", e)
            context.getSharedPreferences("${FILE}_plain", Context.MODE_PRIVATE)
        }
    }
}
