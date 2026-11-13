package dev.healthbridge

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SyncControlTest {
    internal class Fixture(enabled: Boolean, freshChunkingEnabled: Boolean = true) {
        val values = mutableMapOf<String, Any>("sync_enabled" to enabled, "minutes" to 75L)
        val calls = mutableListOf<String>()
        var writes = 0
        var commitSucceeds = true
        var applyWrites = true
        var cancelFails = false
        val cancelledStates = mutableListOf<Boolean>()
        private val staged = mutableMapOf<String, Any>()
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putString", "putLong" -> { staged[args!![0] as String] = args[1]; proxy }
                "commit" -> {
                    writes++
                    if (applyWrites) values.putAll(staged)
                    staged.clear()
                    commitSucceeds
                }
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        private val prefs = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean", "getLong", "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
        val settings = Settings(prefs)
        val control = SyncControl(settings, object : SyncScheduler {
            override fun schedule(minutes: Long) { calls += "schedule:$minutes" }
            override fun cancel() {
                cancelledStates += values["sync_enabled"] as Boolean
                calls += "cancel"
                check(!cancelFails) { "synthetic cancellation failure" }
            }
        }, freshChunkingEnabled)
    }

    @Test fun gatedBuildRefusesEnableWithoutWritingConsentOrScheduling() {
        val fixture = Fixture(false, freshChunkingEnabled = false)
        assertFalse(fixture.control.setEnabled(true))
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(emptyList<String>(), fixture.calls)
        assertEquals(0, fixture.writes)
    }

    @Test fun gatedBuildKeepsSavedConsentUntilExplicitOff() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        assertEquals(true, fixture.values["sync_enabled"])
        assertEquals(0, fixture.writes)
        assertEquals(emptyList<String>(), fixture.calls)
        assertTrue(fixture.control.setEnabled(false))
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
        assertEquals(1, fixture.writes)
        assertEquals(listOf(false), fixture.cancelledStates)
        assertFalse(fixture.control.setEnabled(true))
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
        assertEquals(1, fixture.writes)
    }

    @Test fun gatedRenderingPreservesBothConsentValuesWithoutWritesOrScheduling() {
        listOf(false, true).forEach { consent ->
            val fixture = Fixture(consent, freshChunkingEnabled = false)
            repeat(2) {
                val snapshot = SyncStatusDisplay.Snapshot(fixture.settings.syncEnabled, true, true)
                assertEquals("Build gated", SyncStatusDisplay.format(snapshot).state.label)
                assertEquals(SyncStatusDisplay.Controls(consent, false, true), SyncStatusDisplay.controls(snapshot))
            }
            assertEquals(consent, fixture.values["sync_enabled"])
            assertEquals(0, fixture.writes)
            assertEquals(emptyList<String>(), fixture.calls)
        }
    }

    @Test fun openingWithEitherStoredPreferenceDoesNoWork() {
        listOf(false, true).forEach { enabled ->
            val fixture = Fixture(enabled)
            assertEquals(emptyList<String>(), fixture.calls)
            assertEquals(enabled, fixture.values["sync_enabled"])
        }
    }

    @Test fun switchOnSchedulesStoredIntervalOnce() {
        val fixture = Fixture(false)
        assertTrue(fixture.control.setEnabled(true))
        assertEquals(true, fixture.values["sync_enabled"])
        assertEquals(listOf("schedule:75"), fixture.calls)
    }

    @Test fun switchOffPersistsFalseBeforeCanceling() {
        val fixture = Fixture(true)
        assertTrue(fixture.control.setEnabled(false))
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
        assertEquals(listOf(false), fixture.cancelledStates)
    }

    @Test fun saveWhileOnWithChangedIntervalSchedulesNewIntervalWithoutCanceling() {
        val fixture = Fixture(true)
        fixture.control.save("https://example.invalid/hook", "key", 120)
        assertEquals(120L, fixture.values["minutes"])
        assertEquals(true, fixture.values["sync_enabled"])
        assertEquals(listOf("schedule:120"), fixture.calls)
    }

    @Test fun saveWhileOnWithUnchangedIntervalDoesNoWork() {
        val fixture = Fixture(true)
        fixture.control.save("https://example.invalid/hook", "key", 75)
        assertEquals(75L, fixture.values["minutes"])
        assertEquals(true, fixture.values["sync_enabled"])
        assertEquals(emptyList<String>(), fixture.calls)
    }

    @Test fun saveWhileOffDoesNoWork() {
        val fixture = Fixture(false)
        fixture.control.save("https://example.invalid/hook", "key", 120)
        assertEquals(120L, fixture.values["minutes"])
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(emptyList<String>(), fixture.calls)
    }

    @Test fun gatedSaveClearsStaleConsentAndCancelsInsteadOfRescheduling() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        fixture.control.save("https://example.invalid/hook", "synthetic", 120)
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(120L, fixture.values["minutes"])
        assertEquals(listOf("cancel"), fixture.calls)
    }

    @Test fun gatedSaveWhileAlreadyOffStillCancelsWithoutScheduling() {
        val fixture = Fixture(false, freshChunkingEnabled = false)
        fixture.control.save("https://example.invalid/hook", "synthetic", 120)
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
    }

    @Test fun gatedSaveWithUnchangedIntervalAlsoClearsStaleConsentAndCancels() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        fixture.control.save("https://example.invalid/hook", "synthetic", 75)
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
    }


    @Test fun gatedEnforcementClearsStaleConsentBeforeCancellation() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        assertTrue(fixture.control.enforceDisabled())
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
        assertEquals(listOf(false), fixture.cancelledStates)
        assertEquals(1, fixture.writes)
    }

    @Test fun gatedEnforcementRejectsFailedCommitEvenWhenReadbackIsOff() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        fixture.commitSucceeds = false
        assertFalse(fixture.control.enforceDisabled())
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
    }

    @Test fun gatedEnforcementRejectsUnchangedReadbackAndStillCancels() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        fixture.applyWrites = false
        assertFalse(fixture.control.enforceDisabled())
        assertEquals(true, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
        assertEquals(listOf(true), fixture.cancelledStates)
    }

    @Test fun enforcementWithFlagEnabledDoesNothingForEitherStoredState() {
        listOf(false, true).forEach { enabled ->
            val fixture = Fixture(enabled, freshChunkingEnabled = true)
            assertTrue(fixture.control.enforceDisabled())
            assertEquals(enabled, fixture.values["sync_enabled"])
            assertEquals(0, fixture.writes)
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun gatedSaveDoesNotThrowWhenCancellationFails() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        fixture.cancelFails = true
        fixture.control.save("https://example.invalid/hook", "synthetic", 120)
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
        assertEquals(120L, fixture.values["minutes"])
    }

    @Test fun gatedEnforcementReportsCancellationFailureWithoutThrowing() {
        val fixture = Fixture(true, freshChunkingEnabled = false)
        fixture.cancelFails = true
        assertFalse(fixture.control.enforceDisabled())
        assertEquals(false, fixture.values["sync_enabled"])
        assertEquals(listOf("cancel"), fixture.calls)
    }

}
