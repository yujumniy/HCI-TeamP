package com.hci.teamp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hci.teamp.shared.trial.TrialController

/**
 * C1 물리 음량 버튼 처리 (명세 4.2).
 * - 앱이 키 이벤트를 소비하므로 시스템 음량은 바뀌지 않습니다.
 * - 짧게 누르기: 누르는 순간 ±5 한 번
 * - 길게 누르기: initialDelayMs 후 intervalMs마다 ±5 (앱 자체 타이머)
 *   시스템 키 반복 이벤트(repeatCount>0)는 값 변경에 쓰지 않고 횟수만 기록합니다.
 *   반복 속도는 실제 기기 검증 후 확정 (명세 19장) → 화면에서 바꿀 수 있게 둡니다.
 */
class VolumeButtonHandler(private val controller: TrialController) {

    var enabled by mutableStateOf(true)
    var initialDelayMs by mutableLongStateOf(400L)
    var intervalMs by mutableLongStateOf(150L)

    private val handler = Handler(Looper.getMainLooper())
    private var heldKey: Int? = null
    private var heldDirection = 0
    private var downAt = 0L
    private var autoSteps = 0
    private var systemRepeats = 0

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (heldKey == null) return
            autoSteps++
            controller.step(heldDirection, SOURCE, "auto_repeat=$autoSteps")
            handler.postDelayed(this, intervalMs)
        }
    }

    /** 볼륨 키면 처리하고 true (항상 소비해서 시스템 음량 보호). 다른 키는 false */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> +1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        val keyName = if (direction > 0) "VOL_UP" else "VOL_DOWN"
        if (!enabled) return true // C2 등: 소비만 하고 무시

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    if (heldKey == event.keyCode) systemRepeats++
                    return true
                }
                if (heldKey != null) {
                    // 두 버튼을 동시에 누른 경우: 기록만 하고 무시
                    controller.inputDown(SOURCE, "key=$keyName;ignored=another_key_held")
                    return true
                }
                heldKey = event.keyCode
                heldDirection = direction
                downAt = SystemClock.elapsedRealtime()
                autoSteps = 0
                systemRepeats = 0
                controller.inputDown(SOURCE, "key=$keyName;event_time=${event.eventTime};down_time=${event.downTime}")
                controller.step(direction, SOURCE, "key=$keyName;press")
                handler.postDelayed(repeatRunnable, initialDelayMs)
            }
            KeyEvent.ACTION_UP -> {
                if (heldKey != event.keyCode) return true
                release("key=$keyName;event_time=${event.eventTime}")
            }
        }
        return true
    }

    /** 화면이 가려지는 등 키 업을 못 받을 수 있을 때 반복 중지 */
    fun cancel(reason: String) {
        if (heldKey != null) release("cancel=$reason")
    }

    private fun release(raw: String) {
        handler.removeCallbacks(repeatRunnable)
        val held = SystemClock.elapsedRealtime() - downAt
        val kind = if (autoSteps > 0) "long" else "short"
        controller.inputUp(
            SOURCE,
            "$raw;held_ms=$held;press=$kind;auto_steps=$autoSteps;system_repeats=$systemRepeats;" +
                "initial_delay_ms=$initialDelayMs;interval_ms=$intervalMs",
        )
        heldKey = null
    }

    companion object {
        const val SOURCE = "C1_BUTTON"
    }
}
