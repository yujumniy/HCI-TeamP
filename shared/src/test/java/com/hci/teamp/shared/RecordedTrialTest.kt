package com.hci.teamp.shared

import com.hci.teamp.shared.experiment.*
import com.hci.teamp.shared.model.AttemptRecord
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.SaveOutcome
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialPhase as DataPhase
import com.hci.teamp.shared.recorder.AttemptRecorder
import com.hci.teamp.shared.recorder.AttemptStore
import com.hci.teamp.shared.recorder.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class RecordedTrialTest {
    private class Clock : MonotonicClock {
        var timeNs = 0L
        override fun nowNs() = timeNs
        override fun wallMs() = 1_000L + timeNs / 1_000_000
        fun advance(ms: Long) { timeNs += ms * 1_000_000 }
    }
    private class Store : AttemptStore {
        val begins = mutableListOf<AttemptRecord>()
        val finals = mutableListOf<AttemptRecord>()
        val appended = mutableListOf<com.hci.teamp.shared.model.TrialEvent>()
        override suspend fun appendEvent(event: com.hci.teamp.shared.model.TrialEvent) { appended.add(event) }
        val completion = CompletableDeferred<SaveOutcome>()
        override suspend fun begin(partial: AttemptRecord, deviceType: DeviceType) { begins.add(partial) }
        override suspend fun finalize(record: AttemptRecord, deviceType: DeviceType): SaveOutcome {
            finals.add(record)
            return completion.await()
        }
    }
    private class Input : ExperimentInput {
        override val condition = Condition.C1
        var attempt: Attempt? = null
        var callback: ((InputEvent) -> Unit)? = null
        var stopCount = 0
        override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
            this.attempt = attempt
            callback = onChange
        }
        override fun stop() { stopCount++ }
    }
    private class Audio : TrialAudio {
        val values = mutableListOf<Int>()
        var stops = 0
        override fun start(value: Int) { values.add(value) }
        override fun setVolume(value: Int) { values.add(value) }
        override fun stop() { stops++ }
    }
    private val config = TrialConfig(
        "P001-C1-PR01-A01", "P001", ConditionId.C1, "PR01", 1, DataPhase.PRACTICE, 25, 65, 1,
    )

    @Test fun inputAudioAndPersistenceHaveOneTrialLifecycle() {
        val clock = Clock()
        val store = Store()
        val input = Input()
        val audio = Audio()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val recorder = AttemptRecorder(config, store, deviceModel = "JVM", clock = clock, scope = scope)
        val trial = RecordedTrial(config, recorder, input, audio, scope, clock::nowNs)
        trial.ready()
        clock.advance(2999)
        trial.tick()
        assertTrue(store.begins.isEmpty())
        assertTrue(audio.values.isEmpty())
        clock.advance(1)
        trial.tick()
        assertEquals(1, store.begins.size)
        assertEquals(listOf(25), audio.values)
        trial.raw("DOWN", mapOf("source" to "VOL_UP"))
        clock.advance(10)
        input.callback?.invoke(InputEvent(
            config.attemptId, ExperimentDevice.PHONE, 3010, VirtualVolume(25), VirtualVolume(40),
        ))
        trial.raw("UP", emptyMap())
        trial.complete()
        assertEquals(RecordedPhase.SAVING, trial.state.value.phase)
        assertEquals(1, input.stopCount)
        assertEquals(1, audio.stops)
        val record = store.finals.single()
        assertEquals(EndStatus.COMPLETED, record.result.endStatus)
        assertEquals(40, record.result.finalVolume)
        assertEquals(listOf("START", "INPUT_DOWN", "VALUE_CHANGE", "INPUT_UP", "COMPLETE"), record.events.map { it.type.name })
        assertEquals(15, record.result.metrics?.pathDistance)
        store.completion.complete(SaveOutcome.SAVED)
        assertEquals(RecordedPhase.RESULT, trial.state.value.phase)
    }

    @Test fun timeoutRejectsInputAtTheDeadline() {
        val clock = Clock()
        val store = Store().apply { completion.complete(SaveOutcome.SAVED) }
        val input = Input()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val trial = RecordedTrial(
            config, AttemptRecorder(config, store, deviceModel = "JVM", clock = clock, scope = scope),
            input, Audio(), scope, clock::nowNs,
        )
        trial.ready()
        clock.advance(3000)
        trial.tick()
        clock.advance(30000)
        input.callback?.invoke(InputEvent(
            config.attemptId, ExperimentDevice.PHONE, 33000, VirtualVolume(25), VirtualVolume(30),
        ))
        assertEquals(EndStatus.TIMEOUT, store.finals.single().result.endStatus)
        assertEquals(25, store.finals.single().result.finalVolume)
        assertNull(store.finals.single().result.completionTimeMs)
    }

    @Test fun restartBeforeStartDoesNotInventAnAttempt() {
        val clock = Clock()
        val store = Store()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val trial = RecordedTrial(
            config, AttemptRecorder(config, store, deviceModel = "JVM", clock = clock, scope = scope),
            Input(), Audio(), scope, clock::nowNs,
        )
        trial.ready()
        trial.interrupt()
        assertEquals(RecordedPhase.READY, trial.state.value.phase)
        assertTrue(store.begins.isEmpty())
        assertTrue(store.finals.isEmpty())
    }

    @Test fun rejectedSaveCannotAdvanceAsSuccessfulResult() {
        val clock = Clock()
        val store = Store().apply { completion.complete(SaveOutcome.REJECTED_HASH_MISMATCH) }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val trial = RecordedTrial(
            config, AttemptRecorder(config, store, deviceModel = "JVM", clock = clock, scope = scope),
            Input(), Audio(), scope, clock::nowNs,
        )
        trial.ready()
        clock.advance(3000)
        trial.tick()
        trial.complete()
        assertEquals(RecordedPhase.ERROR, trial.state.value.phase)
        assertTrue(checkNotNull(trial.state.value.error).contains("REJECTED_HASH_MISMATCH"))
    }
    @Test fun storedPlanHasEightPracticeAndThirtyTwoMainTasks() {
        val conditions = V1Protocol.conditionsFor(1).map { ConditionId.valueOf(it.name) }
        val plan = StudyPlan.configs("P001", conditions, StudyPlan.taskOrders("P001"))
        assertEquals(40, plan.size)
        assertEquals(8, plan.count { it.phase == DataPhase.PRACTICE })
        assertEquals(32, plan.count { it.phase == DataPhase.MAIN })
        assertEquals(listOf("PR01", "PR02", "T01", "T02", "T08", "T03", "T07", "T04", "T06", "T05"), plan.take(10).map { it.taskId })
        assertEquals(listOf(ConditionId.C1, ConditionId.C2, ConditionId.C4, ConditionId.C3), plan.map { it.condition }.distinct())
        assertEquals("P001-C1-PR01-A02", StudyPlan.retry(plan.first(), 2).attemptId)
        assertEquals(plan, StudyPlan.configs("P001", conditions, StudyPlan.taskOrders("P001")))
    }
}
