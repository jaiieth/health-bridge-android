package dev.healthbridge

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun clearDiagnostics() {
        SyncDiagnostics.reset()
        DirectoryDurability.reset()
    }

    @Test fun causeCodesSeparateParseStateQuotaStorageAndUnknown() {
        assertEquals(SyncDiagnostics.IO, SyncDiagnostics.code(IOException("ENOSPC")))
        assertEquals(SyncDiagnostics.PARSE, SyncDiagnostics.code(JSONException("bad manifest")))
        assertEquals(SyncDiagnostics.STATE, SyncDiagnostics.code(IllegalStateException("check failed")))
        assertEquals(SyncDiagnostics.STATE, SyncDiagnostics.code(NullPointerException()))
        assertEquals(SyncDiagnostics.QUOTA, SyncDiagnostics.code(ChunkBudgetExceeded(ChunkReason.OUTBOX_QUOTA)))
        assertEquals(SyncDiagnostics.UNKNOWN, SyncDiagnostics.code(RuntimeException("boom")))
        assertEquals(SyncDiagnostics.UNKNOWN, SyncDiagnostics.code(null))
    }

    @Test fun detailDropsPathsUrlsAndBoundsLength() {
        assertEquals("", SyncDiagnostics.sanitize("/data/user/0/dev.healthbridge/files/fresh-sync-chunks"))
        assertEquals("", SyncDiagnostics.sanitize("https://receiver.invalid/hook?token=abc"))
        assertEquals("", SyncDiagnostics.sanitize("mailto:someone@example.invalid"))
        assertEquals(64, SyncDiagnostics.sanitize("A".repeat(200)).length)
        assertEquals("linebreak", SyncDiagnostics.sanitize("line\nbreak"))
        assertEquals("EINVAL (Invalid argument)", SyncDiagnostics.sanitize("  EINVAL   (Invalid argument) "))
        assertFalse(SyncDiagnostics.describe(IOException("/data/user/0/private"), "load").contains("/"))
        assertFalse(SyncDiagnostics.describe(null, "load").contains("null"))
    }

    @Test fun recordedFailureCarriesCodeStepAndSanitizedType() {
        SyncDiagnostics.begin()
        assertNull(SyncDiagnostics.last())
        SyncDiagnostics.record(IOException("UNPUBLISHED_ACTIVE_POINTER"), "resume")
        assertEquals("IO · resume · IOException UNPUBLISHED_ACTIVE_POINTER", SyncDiagnostics.last())
        SyncDiagnostics.begin()
        assertNull(SyncDiagnostics.last())
    }

    @Test fun notesAccumulateWithoutLosingTheRecordedFailure() {
        SyncDiagnostics.begin()
        SyncDiagnostics.record(IllegalStateException("check failed"), "stage")
        SyncDiagnostics.note("durability reduced: directory fsync EINVAL rejected by this device")
        val text = checkNotNull(SyncDiagnostics.last())
        assertTrue(text, text.startsWith("STATE · stage · IllegalStateException check failed"))
        assertTrue(text, text.contains("durability reduced"))
    }

    @Test fun directoryFsyncOnlyDowngradesUnsupportedCapabilityErrors() {
        assertTrue(DirectoryDurability.isUnsupportedErrno(22))
        assertTrue(DirectoryDurability.isUnsupportedErrno(95))
        assertTrue(DirectoryDurability.isUnsupportedErrno(38))
        assertTrue(DirectoryDurability.isUnsupportedErrno(1))
        assertFalse(DirectoryDurability.isUnsupportedErrno(28))
        assertFalse(DirectoryDurability.isUnsupportedErrno(5))
        assertFalse(DirectoryDurability.isUnsupportedErrno(30))
        assertEquals("EINVAL", DirectoryDurability.label(22))
        assertEquals("ENOSPC", DirectoryDurability.label(28))
        assertEquals("errno-77", DirectoryDurability.label(77))
        assertFalse(DirectoryDurability.degraded)
        DirectoryDurability.degrade(22)
        assertTrue(DirectoryDurability.degraded)
        assertTrue(checkNotNull(DirectoryDurability.signal).contains("EINVAL"))
        DirectoryDurability.reset()
        assertFalse(DirectoryDurability.degraded)
        assertNull(DirectoryDurability.signal)
    }

    @Test fun storageFailureStillReportsPausedIoAndRecordsTheRealStep() {
        val root = File(temporary.newFolder(), "outbox")
        val harness = ChunkHarness(root)
        harness.fault = { boundary, name ->
            if (name == "child-0.json" && boundary == DurableBoundary.TEMP_WRITTEN) throw IOException("synthetic ENOSPC")
        }
        val status = harness.run()
        assertEquals(ChunkPhase.PAUSED, status.phase)
        assertEquals(ChunkReason.IO_FAILURE, status.reason)
        val recorded = checkNotNull(SyncDiagnostics.last())
        assertTrue(recorded, recorded.startsWith("IO · stage · IOException synthetic ENOSPC"))
        assertTrue(recorded, recorded.none { it == '/' })
    }

    @Test fun successfulRunClearsRecordedFailure() {
        val root = File(temporary.newFolder(), "outbox")
        val harness = ChunkHarness(root)
        harness.fault = { boundary, name ->
            if (name == "child-0.json" && boundary == DurableBoundary.TEMP_WRITTEN) throw IOException("synthetic ENOSPC")
        }
        assertEquals(ChunkReason.IO_FAILURE, harness.run().reason)
        assertNotNull(SyncDiagnostics.last())
        harness.fault = { _, _ -> }
        val done = harness.run()
        assertEquals(ChunkPhase.DONE, done.phase)
        assertNull(SyncDiagnostics.last())
    }

    @Test fun diagnosticsNeverInfluenceDeliveryOrCursors() {
        val root = File(temporary.newFolder(), "outbox")
        val harness = ChunkHarness(root)
        harness.fault = { boundary, name ->
            if (name == "manifest.json" && boundary == DurableBoundary.FILE_SYNCED) throw IOException("synthetic ENOSPC")
        }
        val paused = harness.run()
        assertEquals(ChunkReason.IO_FAILURE, paused.reason)
        assertEquals(0, harness.cursors.commits)
        val sentBefore = harness.sent.size
        harness.fault = { _, _ -> }
        assertEquals(ChunkPhase.DONE, harness.run().phase)
        assertEquals(1, harness.cursors.commits)
        assertTrue(harness.sent.size >= sentBefore)
        assertNull(SyncDiagnostics.last())
    }

    @Test fun recordedFailureIsObservableThroughTheStatusSnapshotContract() {
        val result = "Health sync PAUSED: 0/0; IO_FAILURE"
        SyncDiagnostics.begin()
        SyncDiagnostics.record(IOException("EINVAL"), "load")
        val snapshot = SyncStatusDisplay.Snapshot(true, true, true, result = result)
        assertEquals(SyncStatusDisplay.State.IO_FAILURE, SyncStatusDisplay.format(snapshot).state)
        assertEquals("Chunk counts are unavailable after a storage failure. Health sync is incomplete. " +
            "Local storage failed. Recorded counts may be unavailable. No automatic resend will run.",
            SyncStatusDisplay.progress(result))
        assertEquals("IO · load · IOException EINVAL", SyncDiagnostics.last())
    }

    @Test fun harnessRunIsNotAffectedByDiagnosticsReset() = runBlocking {
        val root = File(temporary.newFolder(), "outbox")
        val harness = ChunkHarness(root)
        SyncDiagnostics.reset()
        assertEquals(ChunkPhase.DONE, harness.run().phase)
        assertNull(SyncDiagnostics.last())
        assertEquals(1, harness.cursors.commits)
    }
}
