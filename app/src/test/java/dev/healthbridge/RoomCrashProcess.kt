package dev.healthbridge

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

internal object RoomCrashProcess {
    @JvmStatic fun main(args: Array<String>) {
        val result = JUnitCore.runClasses(Child::class.java)
        result.failures.forEach { System.err.println(it.trace) }
        kotlin.system.exitProcess(if (result.wasSuccessful()) 0 else 2)
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [28], manifest = Config.NONE)
    @ConscryptMode(ConscryptMode.Mode.OFF)
    class Child {
        @Test fun crashInsideActualDaoTransaction(): Unit = runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val marker = File(checkNotNull(System.getProperty("hb.room.marker")))
            val cursorMode = System.getProperty("hb.room.mode") == "cursor"
            val factory = object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                    val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                    fun wrap(database: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by database {
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val statement = database.compileStatement(sql)
                            return object : SupportSQLiteStatement by statement {
                                var target: String? = null
                                override fun bindString(index: Int, value: String) {
                                    if (index == 1) target = value
                                    statement.bindString(index, value)
                                }
                                override fun executeInsert(): Long {
                                    if ((!cursorMode && sql.contains("SyncStatus")) ||
                                        (cursorMode && sql.contains("Cursor") && target == "SleepSessionRecord")) {
                                        check(database.inTransaction())
                                        val count = database.query("SELECT count(*) FROM Pending").use { it.moveToFirst(); it.getInt(0) }
                                        val until = database.query("SELECT until FROM Cursor WHERE type='StepsRecord'").use { it.moveToFirst(); it.getString(0) }
                                        check(count == 0 && until == "2026-11-02T00:00:00Z")
                                        FileOutputStream(marker).use { file ->
                                            file.write("inTransaction=true pending=0 cursor=$until beforeStatus=true".toByteArray())
                                            file.fd.sync()
                                        }
                                        Runtime.getRuntime().halt(73)
                                    }
                                    return statement.executeInsert()
                                }
                            }
                        }
                    }
                    return object : SupportSQLiteOpenHelper by helper {
                        override val writableDatabase get() = wrap(helper.writableDatabase)
                        override val readableDatabase get() = wrap(helper.readableDatabase)
                    }
                }
            }
            val db = Room.databaseBuilder(context, BridgeDb::class.java, checkNotNull(System.getProperty("hb.room.path")))
                .openHelperFactory(factory).allowMainThreadQueries().build()
            if (cursorMode) db.store().commitChunkCursors(listOf(
                Cursor("StepsRecord", "2026-11-02T00:00:00Z"), Cursor("SleepSessionRecord", "later")))
            else db.store().success(listOf(Cursor("StepsRecord", "2026-11-02T00:00:00Z")), "after", false, "accepted")
            error("Expected process halt inside success transaction")
        }
    }
}
