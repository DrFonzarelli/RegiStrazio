package com.example.registrazio.data.local.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert

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

    @Query("SELECT COUNT(*) FROM commenti WHERE statoSync IN ('LOCALE', 'DA_ELIMINARE', 'ERRORE')")
    suspend fun commentiDaSincronizzare(): Int

    @Query("SELECT COUNT(*) FROM tracce WHERE statoSync IN ('LOCALE', 'DA_ELIMINARE', 'ERRORE')")
    suspend fun tracceDaSincronizzare(): Int

    @Query("SELECT COUNT(*) FROM cartelle WHERE statoSync IN ('LOCALE', 'DA_ELIMINARE', 'ERRORE')")
    suspend fun cartelleDaSincronizzare(): Int

    // ---------- strumenti di test ----------

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

