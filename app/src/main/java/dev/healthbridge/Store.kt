package dev.healthbridge

import android.content.Context
import androidx.room.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.sync.Mutex

@Entity
data class Pending(
    @PrimaryKey val id: Int = 1,
    val batchId: String,
    val body: ByteArray,
    val cursors: String,
    val attempts: Int = 0,
    val nextAttempt: Long = 0,
    val isTest: Boolean = false,
)

@Entity data class Cursor(@PrimaryKey val type: String, val until: String)

@Entity
data class SyncStatus(
    @PrimaryKey val id: Int = 1,
    val lastSuccess: String = "Never",
    val result: String = "Ready",
)

@Dao
interface Store {
    @Query("SELECT * FROM Pending LIMIT 1") suspend fun pending(): Pending?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(p: Pending)

    @Query("DELETE FROM Pending") suspend fun deletePending()

    @Query("SELECT * FROM Cursor") suspend fun cursors(): List<Cursor>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cursors(c: List<Cursor>)

    @Query("SELECT * FROM SyncStatus LIMIT 1") suspend fun status(): SyncStatus?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun status(s: SyncStatus)

    @Transaction
    suspend fun success(c: List<Cursor>, time: String, isTest: Boolean, result: String) {
        val lastSync = if (isTest) status()?.lastSuccess ?: "Never" else time
        cursors(c)
        deletePending()
        status(SyncStatus(lastSuccess = lastSync, result = result))
    }
}

@Database(
    entities = [Pending::class, Cursor::class, SyncStatus::class],
    version = 1,
    exportSchema = false,
)
abstract class BridgeDb : RoomDatabase() {
    abstract fun store(): Store
}

class Settings(context: Context) {
    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            "settings",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    val endpoint
        get() = prefs.getString("endpoint", "")!!

    val token
        get() = prefs.getString("token", "")!!

    val minutes
        get() = prefs.getLong("minutes", 360)

    fun save(endpoint: String, token: String, minutes: Long) {
        require(
            prefs
                .edit()
                .putString("endpoint", endpoint)
                .putString("token", token)
                .putLong("minutes", minutes)
                .commit()
        )
    }
}

object AppState {
    @Volatile var foreground = false
    val mutex = Mutex()
    @Volatile private var db: BridgeDb? = null

    fun store(context: Context): Store =
        (db
                ?: synchronized(this) {
                    db
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                BridgeDb::class.java,
                                "bridge.db",
                            )
                            .build()
                            .also { db = it }
                })
            .store()
}
