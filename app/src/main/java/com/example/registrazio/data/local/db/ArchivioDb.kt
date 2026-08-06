package com.example.registrazio.data.local.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.example.registrazio.data.model.StatoSync
import com.example.registrazio.data.model.VotoStella

@Dao
interface ArchivioDao {

    // ---------- lettura ----------
    //
    // Le righe DA_ELIMINARE restano in tabella ma non escono da qui: servono
    // ancora alla sincronizzazione, per cancellarle anche su Firestore.

    @Query("SELECT * FROM cartelle WHERE statoSync != 'DA_ELIMINARE' ORDER BY aggiuntoIl")
    suspend fun cartelle(): List<CartellaEntity>

    @Query("SELECT * FROM tracce WHERE statoSync != 'DA_ELIMINARE'")
    suspend fun tracce(): List<TracciaEntity>

    @Query("SELECT * FROM commenti WHERE statoSync != 'DA_ELIMINARE' ORDER BY timestampSecondi")
    suspend fun commenti(): List<CommentoEntity>

    @Query("SELECT * FROM download")
    suspend fun download(): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE tracciaId = :tracciaId")
    suspend fun download(tracciaId: String): DownloadEntity?

    // ---------- scrittura ----------

    @Upsert
    suspend fun salvaCartella(cartella: CartellaEntity)

    @Upsert
    suspend fun salvaTracce(tracce: List<TracciaEntity>)

    @Upsert
    suspend fun salvaTraccia(traccia: TracciaEntity)

    @Upsert
    suspend fun salvaCommento(commento: CommentoEntity)

    @Upsert
    suspend fun salvaDownload(download: DownloadEntity)

    @Query("DELETE FROM download WHERE tracciaId = :tracciaId")
    suspend fun cancellaDownload(tracciaId: String)

    // ---------- eliminazione ----------
    //
    // Due strade diverse a seconda che la riga sia già arrivata su Firestore.
    // Quella mai caricata si può cancellare davvero; l'altra va marcata, o
    // ricomparirebbe alla prossima sincronizzazione.

    @Query("DELETE FROM commenti WHERE id = :id AND statoSync = 'LOCALE'")
    suspend fun cancellaCommentoMaiCaricato(id: String)

    @Query("UPDATE commenti SET statoSync = 'DA_ELIMINARE' WHERE id = :id AND statoSync != 'LOCALE'")
    suspend fun marcaCommentoDaEliminare(id: String)

    @Query("DELETE FROM cartelle WHERE id = :id AND statoSync = 'LOCALE'")
    suspend fun cancellaCartellaMaiCaricata(id: String)

    @Query("UPDATE cartelle SET statoSync = 'DA_ELIMINARE' WHERE id = :id AND statoSync != 'LOCALE'")
    suspend fun marcaCartellaDaEliminare(id: String)

    @Query("DELETE FROM tracce WHERE cartellaId = :cartellaId")
    suspend fun cancellaTracceDi(cartellaId: String)

    @Query("DELETE FROM commenti WHERE tracciaId IN (SELECT id FROM tracce WHERE cartellaId = :cartellaId)")
    suspend fun cancellaCommentiDi(cartellaId: String)

    // ---------- sincronizzazione ----------
    //
    // "Da caricare" è `LOCALE` o `ERRORE`: la prima è una riga scritta o
    // ritoccata qui, la seconda una che ci ha già provato e non ce l'ha fatta.
    // `DA_ELIMINARE` viaggia a parte perché non si carica, si cancella.

    @Query("SELECT * FROM cartelle WHERE statoSync IN ('LOCALE', 'ERRORE')")
    suspend fun cartelleDaCaricare(): List<CartellaEntity>

    @Query("SELECT * FROM tracce WHERE statoSync IN ('LOCALE', 'ERRORE')")
    suspend fun tracceDaCaricare(): List<TracciaEntity>

    @Query("SELECT * FROM commenti WHERE statoSync IN ('LOCALE', 'ERRORE')")
    suspend fun commentiDaCaricare(): List<CommentoEntity>

    @Query("SELECT * FROM cartelle WHERE statoSync = 'DA_ELIMINARE'")
    suspend fun cartelleDaCancellare(): List<CartellaEntity>

    @Query("SELECT * FROM commenti WHERE statoSync = 'DA_ELIMINARE'")
    suspend fun commentiDaCancellare(): List<CommentoEntity>

    @Query("UPDATE cartelle SET statoSync = :stato WHERE id = :id")
    suspend fun segnaCartella(id: String, stato: StatoSync)

    @Query("UPDATE tracce SET statoSync = :stato WHERE id = :id")
    suspend fun segnaTraccia(id: String, stato: StatoSync)

    @Query("UPDATE commenti SET statoSync = :stato WHERE id = :id")
    suspend fun segnaCommento(id: String, stato: StatoSync)

    /** Dopo la cancellazione su Firestore la riga non serve più a nessuno. */
    @Query("DELETE FROM cartelle WHERE id = :id AND statoSync = 'DA_ELIMINARE'")
    suspend fun cancellaCartellaSincronizzata(id: String)

    @Query("DELETE FROM commenti WHERE id = :id AND statoSync = 'DA_ELIMINARE'")
    suspend fun cancellaCommentoSincronizzato(id: String)

    /**
     * Toglie un commento che su Firestore non c'è più.
     *
     * Diverso da [cancellaCommentoSincronizzato], che chiude il giro di una
     * cancellazione partita da qui: questo esegue quella di qualcun altro, e
     * per questo pretende che la riga sia in pari col cloud — se fosse
     * `LOCALE` non sarebbe "sparita", non ci sarebbe mai arrivata.
     */
    @Query("DELETE FROM commenti WHERE id = :id AND statoSync = 'SINCRONIZZATO'")
    suspend fun cancellaCommentoSincronizzatoDavvero(id: String)

    /**
     * La riga intera, per sapere se il pull ha davvero qualcosa da scrivere.
     *
     * `null` quando non c'è: allora quello che arriva da Firestore è nuovo.
     * Serve la riga completa e non il solo `statoSync`, perché il confronto va
     * fatto sul contenuto: una versione remota identica a quella locale non è
     * un aggiornamento, e riscriverla la conterebbe come tale.
     */
    @Query("SELECT * FROM cartelle WHERE id = :id")
    suspend fun cartellaPerId(id: String): CartellaEntity?

    @Query("SELECT * FROM tracce WHERE id = :id")
    suspend fun tracciaPerId(id: String): TracciaEntity?

    @Query("SELECT * FROM commenti WHERE id = :id")
    suspend fun commentoPerId(id: String): CommentoEntity?

    @Query("SELECT COUNT(*) FROM commenti WHERE statoSync IN ('LOCALE', 'DA_ELIMINARE', 'ERRORE')")
    suspend fun commentiDaSincronizzare(): Int

    @Query("SELECT COUNT(*) FROM tracce WHERE statoSync IN ('LOCALE', 'DA_ELIMINARE', 'ERRORE')")
    suspend fun tracceDaSincronizzare(): Int

    @Query("SELECT COUNT(*) FROM cartelle WHERE statoSync IN ('LOCALE', 'DA_ELIMINARE', 'ERRORE')")
    suspend fun cartelleDaSincronizzare(): Int

    // ---------- strumenti di test ----------

    /**
     * Dopo uno svuotamento di Firestore fatto da fuori: quello che qui risulta
     * già caricato non lo è più. Le righe `DA_ELIMINARE` restano dove sono —
     * cancellare qualcosa che non c'è più è già successo.
     */
    @Query("UPDATE cartelle SET statoSync = 'LOCALE' WHERE statoSync = 'SINCRONIZZATO'")
    suspend fun cartelleDaRicaricare()

    @Query("UPDATE tracce SET statoSync = 'LOCALE' WHERE statoSync = 'SINCRONIZZATO'")
    suspend fun tracceDaRicaricare()

    @Query("UPDATE commenti SET statoSync = 'LOCALE' WHERE statoSync = 'SINCRONIZZATO' AND id NOT LIKE 'prova-%'")
    suspend fun commentiDaRicaricare()

    @Transaction
    suspend fun segnaTuttoDaCaricare() {
        cartelleDaRicaricare()
        tracceDaRicaricare()
        commentiDaRicaricare()
    }

    @Query("DELETE FROM commenti")
    suspend fun svuotaCommenti()

    @Query("DELETE FROM tracce")
    suspend fun svuotaTracce()

    @Query("DELETE FROM cartelle")
    suspend fun svuotaCartelle()

    @Query("DELETE FROM download")
    suspend fun svuotaDownload()
}

@Database(
    entities = [
        CartellaEntity::class,
        TracciaEntity::class,
        CommentoEntity::class,
        DownloadEntity::class
    ],
    // 3: `dimensioneByte` su TracciaEntity. Con la migrazione distruttiva qui
    // sotto il database si azzera, e le cartelle si riprendono da MEGA — ma i
    // commenti non ancora sincronizzati no. Finché Firestore non c'è, alzare
    // questo numero vuol dire perderli.
    version = 3,
    exportSchema = false
)
@TypeConverters(Convertitori::class)
abstract class ArchivioDb : RoomDatabase() {

    abstract fun dao(): ArchivioDao

    companion object {
        @Volatile
        private var istanza: ArchivioDb? = null

        fun apri(context: Context): ArchivioDb = istanza ?: synchronized(this) {
            istanza ?: Room.databaseBuilder(
                context.applicationContext,
                ArchivioDb::class.java,
                "registrazio.db"
            )
                // Lo schema è alla prima versione e i dati sono ricostruibili da
                // MEGA e Firestore. Quando ci saranno dati non ricostruibili,
                // qui serviranno migrazioni vere.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { istanza = it }
        }
    }
}

/** Le righe in attesa di essere caricate, per il badge del tasto Sincronizza. */
data class ConteggioPendenti(
    val cartelle: Int,
    val tracce: Int,
    val commenti: Int
) {
    val totale: Int get() = cartelle + tracce + commenti
    val ceNeSono: Boolean get() = totale > 0
}

