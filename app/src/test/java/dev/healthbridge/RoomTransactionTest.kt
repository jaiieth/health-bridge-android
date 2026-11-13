package dev.healthbridge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RoomTransactionTest {
    private lateinit var context: Context
    private lateinit var db: BridgeDb
    private lateinit var name: String
    private val old = listOf(Cursor("StepsRecord", "2026-11-01T00:00:00Z"))
    private val next = listOf(Cursor("StepsRecord", "2026-11-02T00:00:00Z"))
    private val pending get() = Pending(batchId = "synthetic", body = byteArrayOf(1, 2, 3), cursors = "{}")

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        name = "transaction-${UUID.randomUUID()}.db"
        reopen()
    }

    private fun reopen() {
        db = Room.databaseBuilder(context, BridgeDb::class.java, name).allowMainThreadQueries().build()
    }

    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun pendingPreventsCursorAdvanceAcrossRealDatabaseReopen() = runBlocking {
        val store = db.store()
        store.cursors(old)
        store.put(pending)
        assertFalse(store.commitChunkCursors(next))
        db.close(); reopen()
        assertEquals(old, db.store().cursors())
        assertArrayEquals(pending.body, db.store().pending()!!.body)
        assertEquals("synthetic", db.store().pending()!!.batchId)
    }

    @Test fun noPendingCommitsAllTargetsAcrossRealDatabaseReopen() = runBlocking {
        db.store().cursors(old)
        val targets = next + Cursor("SleepSessionRecord", "2026-11-02T00:00:00Z")
        assertTrue(db.store().commitChunkCursors(targets))
        db.close(); reopen()
        assertEquals(targets.sortedBy { it.type }, db.store().cursors().sortedBy { it.type })
        assertNull(db.store().pending())
    }

    @Test fun successPersistsCursorPendingDeleteAndStatusTogether() = runBlocking {
        db.store().cursors(old)
        db.store().put(pending)
        db.store().status(SyncStatus(lastSuccess = "before", result = "before"))
        db.store().success(next, "after", false, "accepted")
        db.close(); reopen()
        assertEquals(next, db.store().cursors())
        assertNull(db.store().pending())
        assertEquals(SyncStatus(lastSuccess = "after", result = "accepted"), db.store().status())
    }

    @Test fun statusInsertFailureRollsBackCursorAndPendingDelete() = runBlocking {
        db.store().cursors(old)
        db.store().put(pending)
        val before = SyncStatus(lastSuccess = "before", result = "before")
        db.store().status(before)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_status BEFORE INSERT ON SyncStatus BEGIN SELECT RAISE(ABORT, 'synthetic status failure'); END")
        var failed = false
        try { db.store().success(next, "after", false, "accepted") }
        catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue("SQLite trigger must abort the final transaction write", failed)
        db.close(); reopen()
        assertEquals(old, db.store().cursors())
        assertArrayEquals(pending.body, db.store().pending()!!.body)
        assertEquals(before, db.store().status())
    }

    @Test fun cursorInsertFailureRollsBackEveryTarget() = runBlocking {
        db.store().cursors(old)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON Cursor WHEN NEW.type = 'SleepSessionRecord' BEGIN SELECT RAISE(ABORT, 'synthetic cursor failure'); END")
        var failed = false
        try { db.store().commitChunkCursors(next + Cursor("SleepSessionRecord", "later")) }
        catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue(failed)
        db.close(); reopen()
        assertEquals(old, db.store().cursors())
    }

    @Test fun testSuccessPreservesLastSuccessInSameTransaction() = runBlocking {
        db.store().put(pending)
        db.store().status(SyncStatus(lastSuccess = "before"))
        db.store().success(next, "after", true, "test accepted")
        db.close(); reopen()
        assertNull(db.store().pending())
        assertEquals(next, db.store().cursors())
        assertEquals(SyncStatus(lastSuccess = "before", result = "test accepted"), db.store().status())
    }
}
