package com.abijith.expensetracker

import android.content.ContextWrapper
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Shared production processor, isolated history, real local Python, and stubbed scheduling. */
@RunWith(AndroidJUnit4::class)
class SmsProcessorInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "sms-processor-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = directory
        // Scope permission to this isolated wrapper, never grant/revoke the real app's permission.
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.RECEIVE_SMS) PackageManager.PERMISSION_GRANTED
            else super.checkPermission(permission, pid, uid)
    }
    private val store = SmsStore(context)
    private val real = CapturedSms("demo-received", "DEMO", "Demo purchase", 0, 1,
        pythonHandoff = PythonHandoff(HandoffStatus.PENDING))

    @After
    fun removeOnlySyntheticFiles() {
        listOf("sms_capture.json", "sms_capture.json.bak", "sms_capture.json.new").forEach {
            File(directory, it).delete()
        }
        directory.delete()
    }

    @Test
    fun bothSourcesUsePackagedPythonAndSharedSchedulingWithoutInventingReceptionTimings() {
        assertTrue(store.setEnabled(true))
        val generation = store.activeGeneration()!!
        val queued = mutableListOf<String>()
        val processor = SmsProcessor(store, { true }, { sms, timing ->
            assertEquals(HandoffStatus.PENDING, store.state.value.messages.first { it.id == sms.id }.pythonHandoff!!.status)
            PythonBridge.acknowledge(target, sms, timing)
        }, { id, token -> assertEquals(generation, token); queued.add(id) })
        val timing = SmsReceptionTiming(SystemClock.elapsedRealtimeNanos(), 1.0, 2.0, 3.0, 0.0, 1)
        assertTrue(processor.process(real, generation, timing))
        val simulated = SimulatedSms.create("DEMO", "Demo groceries \uD83D\uDED2")
        assertTrue(processor.process(simulated, generation))
        val restored = SmsStore(context).state.value.messages
        assertEquals(listOf(simulated.id, real.id), restored.map { it.id })
        assertTrue(restored[0].simulated)
        assertFalse(restored[1].simulated)
        assertEquals(listOf(real.id, simulated.id), queued)
        restored.forEach { assertEquals(HandoffStatus.ACKNOWLEDGED, it.pythonHandoff!!.status) }
        val simulatedTimings = restored[0].pythonHandoff!!.performance!!
        assertNotNull(simulatedTimings.jsonEncodeMs)
        assertNull(simulatedTimings.receiverToHandoffMs)
        assertNull(simulatedTimings.smsTimestampToReceiverMs)
        assertEquals(1.0, restored[1].pythonHandoff!!.performance!!.receiverSetupMs!!, 0.001)
        assertNotNull(restored[1].pythonHandoff!!.performance!!.initialSaveMs)
        assertFalse(SmsPayload.encode(simulated).contains("simulated")) // Local provenance, not an LLM hint.
    }

    @Test
    fun permissionDuplicatesAndStaleTokensPreventHandoffAndScheduling() {
        assertTrue(store.setEnabled(true))
        val generation = store.activeGeneration()!!
        val forbidden = SmsProcessor(store, { false }, { _, _ -> error("Must not acknowledge") },
            { _, _ -> error("Must not schedule") })
        assertFalse(forbidden.process(real, generation))
        assertTrue(store.state.value.messages.isEmpty())
        assertTrue(store.append(real, generation))
        val allowed = SmsProcessor(store, { true }, { _, _ -> error("Must not acknowledge") },
            { _, _ -> error("Must not schedule") })
        assertFalse(allowed.process(real, generation))
        assertTrue(store.clear())
        assertFalse(allowed.process(real, generation))
        assertTrue(store.setEnabled(false))
        assertFalse(allowed.process(real, store.state.value.generation))
    }

    @Test
    fun pauseDuringHandoffDiscardsResultAndNeverSchedulesCloud() {
        assertTrue(store.setEnabled(true))
        val processor = SmsProcessor(store, { true }, { _, _ ->
            store.setEnabled(false)
            PythonHandoff(HandoffStatus.ACKNOWLEDGED)
        }, { _, _ -> error("Must not schedule after pause") })
        assertTrue(processor.process(real, store.activeGeneration()!!))
        assertEquals(HandoffStatus.INTERRUPTED, store.state.value.messages.single().pythonHandoff!!.status)
    }

    @Test
    fun clearingDuringHandoffCannotResurrectACardOrScheduleCloud() {
        assertTrue(store.setEnabled(true))
        val processor = SmsProcessor(store, { true }, { _, _ ->
            store.clear()
            PythonHandoff(HandoffStatus.ACKNOWLEDGED)
        }, { _, _ -> error("Must not schedule after clear") })
        assertTrue(processor.process(real, store.activeGeneration()!!))
        assertTrue(store.state.value.messages.isEmpty())
    }

    @Test
    fun aHandoffExceptionKeepsTheCopyWithoutLeakingPrivateErrorText() {
        assertTrue(store.setEnabled(true))
        var scheduled = false
        val processor = SmsProcessor(store, { true }, { _, _ ->
            throw IllegalStateException("Private SMS text must not be exposed")
        }, { _, _ -> scheduled = true })
        assertTrue(processor.process(real, store.activeGeneration()!!))
        val handoff = store.state.value.messages.single().pythonHandoff!!
        assertEquals(HandoffStatus.FAILED, handoff.status)
        assertNull(handoff.acknowledgement)
        // As with the original receiver, the saved body can still be classified separately.
        assertTrue(scheduled)
    }

    @Test
    fun actualLocalOnlySubmissionCreatesAnAcknowledgedCardWithoutCloudWork() {
        assertTrue(store.setEnabled(true))
        assertTrue(submitSimulatedSms(context, store, "DEMO", SimulatedSms.EXPENDITURE_EXAMPLE, false))
        val message = store.state.value.messages.single()
        assertTrue(message.simulated)
        assertEquals(HandoffStatus.ACKNOWLEDGED, message.pythonHandoff!!.status)
        assertNull(message.classification) // No cloud configuration or WorkManager interaction.
        assertFalse(submitSimulatedSms(context, store, "DEMO", " ", false))
        assertTrue(store.setEnabled(false))
        assertFalse(submitSimulatedSms(context, store, "DEMO", "Another demo", false))
        assertEquals(1, store.state.value.messages.size)
    }

    @Test
    fun simulatorRejectsInvalidInputsAndIdenticalTestsHaveDistinctIds() {
        assertFalse(SimulatedSms.isValid(" ", "Demo"))
        assertFalse(SimulatedSms.isValid("D".repeat(101), "Demo"))
        assertFalse(SimulatedSms.isValid("DEMO", "\n "))
        assertFalse(SimulatedSms.isValid("DEMO", "x".repeat(12001)))
        val first = SimulatedSms.create(" DEMO ", "x".repeat(12000))
        val second = SimulatedSms.create(" DEMO ", "x".repeat(12000))
        assertNotEquals(first.id, second.id)
        assertEquals("DEMO", first.sender)
        assertTrue(first.simulated)
        assertEquals(first.sentAtMillis, first.receivedAtMillis)
    }
}
