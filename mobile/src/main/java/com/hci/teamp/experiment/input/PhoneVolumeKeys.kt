package com.hci.teamp.experiment.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentDevice
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.VirtualVolume

/**
 * C1 스마트폰 물리 음량 버튼 (명세 4.2).
 *
 * 사용법: 시행이 ACTIVE가 되면 start(), 끝나면 stop().
 * 호스트 Activity는 onKeyDown()/onKeyUp()에서 onKeyEvent()를 호출하고, true면 이벤트를 소비합니다.
 * (ComponentActivity.dispatchKeyEvent는 AndroidX 내부 API라 Lint 에러가 납니다)
 *
 * - 볼륨 키는 시행 중이 아니어도 항상 소비합니다 → 시스템 음량이 바뀌지 않음.
 * - 짧게 누르기: 누르는 순간 ±5.
 * - 길게 누르기: initialDelayMs 뒤부터 intervalMs마다 ±5 (앱 자체 타이머).
 *   시스템 키 반복(repeatCount > 0)은 값 변경에 쓰지 않고 횟수만 원시 기록에 남깁니다.
 *   반복 속도는 실기기 검증 후 확정(명세 19장)하므로 바꿀 수 있게 둡니다.
 * - 값이 실제로 바뀔 때만 InputEvent를 냅니다. 0/100 경계에서 누른 것은 원시 기록(BOUNDARY)만 남깁니다.
 */
class PhoneVolumeKeys(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val scheduler: RepeatScheduler = HandlerRepeatScheduler(),
) : ExperimentInput {

    override val condition = Condition.C1

    var initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS
    var intervalMs: Long = DEFAULT_INTERVAL_MS

    /** Phase 1 검증용 원시 입력 기록 (누름/뗌/경계/무시) */
    var rawListener: ((RawInput) -> Unit)? = null

    private var attempt: Attempt? = null
    private var onChange: ((InputEvent) -> Unit)? = null
    private var value: VirtualVolume? = null

    private var heldKey: Int? = null
    private var heldDirection = 0
    private var downAt = 0L
    private var autoSteps = 0
    private var systemRepeats = 0

    val currentValue: VirtualVolume? get() = value
    val isActive: Boolean get() = attempt != null

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        cancelRepeat()
        heldKey = null
        this.attempt = attempt
        this.onChange = onChange
        this.value = attempt.task.start
    }

    override fun stop() {
        if (heldKey != null) release(cause = "stop")
        attempt = null
        onChange = null
    }

    /** Activity.onKeyDown/onKeyUp에서 호출. 볼륨 키면 true(소비), 아니면 false */
    fun onKeyEvent(event: KeyEvent): Boolean = handleKey(event.keyCode, event.action, event.repeatCount)

    /** 화면 포커스를 잃는 등 키 업을 못 받을 수 있을 때 반복을 멈춥니다. */
    fun cancelHold(cause: String) {
        if (heldKey != null) release(cause)
    }

    internal fun handleKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> +1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        val key = if (direction > 0) "VOL_UP" else "VOL_DOWN"
        if (attempt == null) {
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) raw(RawInput.Kind.IGNORED, key, "not_active")
            return true
        }
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0) {
                    if (heldKey == keyCode) systemRepeats++
                    return true
                }
                if (heldKey != null) {
                    raw(RawInput.Kind.IGNORED, key, "another_key_held")
                    return true
                }
                heldKey = keyCode
                heldDirection = direction
                downAt = clock()
                autoSteps = 0
                systemRepeats = 0
                raw(RawInput.Kind.DOWN, key, "")
                stepOnce(direction, key, "press")
                scheduleRepeat(initialDelayMs)
            }
            KeyEvent.ACTION_UP -> if (heldKey == keyCode) release(cause = "key_up")
        }
        return true
    }

    private fun scheduleRepeat(delayMs: Long) {
        scheduler.schedule(delayMs) {
            if (heldKey == null || attempt == null) return@schedule
            autoSteps++
            stepOnce(heldDirection, keyName(heldDirection), "auto_repeat=$autoSteps")
            scheduleRepeat(intervalMs)
        }
    }

    private fun cancelRepeat() = scheduler.cancel()

    private fun release(cause: String) {
        cancelRepeat()
        val held = clock() - downAt
        val press = if (autoSteps > 0) "long" else "short"
        raw(
            RawInput.Kind.UP,
            keyName(heldDirection),
            "cause=$cause;held_ms=$held;press=$press;auto_steps=$autoSteps;system_repeats=$systemRepeats;" +
                "initial_delay_ms=$initialDelayMs;interval_ms=$intervalMs",
        )
        heldKey = null
    }

    private fun stepOnce(direction: Int, key: String, cause: String) {
        val a = attempt ?: return
        val before = value ?: return
        val next = (before.percent + direction * STEP).coerceIn(0, 100)
        if (next == before.percent) {
            raw(RawInput.Kind.BOUNDARY, key, "$cause;value=${before.percent}")
            return
        }
        val after = VirtualVolume(next)
        value = after
        onChange?.invoke(InputEvent(a.id, ExperimentDevice.PHONE, clock(), before, after))
    }

    private fun raw(kind: RawInput.Kind, key: String, detail: String) {
        rawListener?.invoke(RawInput(attempt?.id ?: "", clock(), kind, key, value?.percent, detail))
    }

    private fun keyName(direction: Int) = if (direction > 0) "VOL_UP" else "VOL_DOWN"

    companion object {
        const val STEP = 5
        const val DEFAULT_INITIAL_DELAY_MS = 400L
        const val DEFAULT_INTERVAL_MS = 150L
    }
}

/** 원시 입력 기록 (공통 InputEvent는 값 변경만 담으므로, 검증용으로 따로 둡니다) */
data class RawInput(
    val attemptId: String,
    val elapsedRealtimeMs: Long,
    val kind: Kind,
    val source: String,
    val value: Int?,
    val detail: String,
) {
    enum class Kind { DOWN, UP, BOUNDARY, IGNORED, TRACK_TAP_BLOCKED }
}

/** 길게 누르기 반복용 타이머 (테스트에서 바꿔 끼울 수 있게 분리) */
interface RepeatScheduler {
    fun schedule(delayMs: Long, block: () -> Unit)
    fun cancel()
}

class HandlerRepeatScheduler : RepeatScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun schedule(delayMs: Long, block: () -> Unit) {
        val r = Runnable { pending = null; block() }
        pending = r
        handler.postDelayed(r, delayMs)
    }

    override fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}
