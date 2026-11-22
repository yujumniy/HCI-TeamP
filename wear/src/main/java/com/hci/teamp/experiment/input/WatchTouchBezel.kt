package com.hci.teamp.experiment.input

import android.os.SystemClock
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentDevice
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.VirtualVolume

/**
 * C3 갤럭시 워치4 터치 베젤 (명세 4.4).
 *
 * 사용법: 시행 화면에 Content()로 감싸 배치 → 시행이 ACTIVE가 되면 start(), 끝나면 stop().
 *
 * - 베젤 회전은 화면 터치가 아니라 회전(rotary) 이벤트로 들어옵니다. Content()가 포커스를 잡아 그 이벤트를 받습니다.
 *   Content() 안에 스크롤 목록(ScalingLazyColumn 등)을 두면 회전이 목록 스크롤로 빠지므로 두지 마세요.
 * - 회전 이벤트는 시행 중이 아니어도 항상 소비합니다 (값은 그대로, 원시 기록만).
 * - 값이 실제로 바뀔 때만 InputEvent를 냅니다. 한 이벤트로 2단계 이상 바뀌면 관찰된 값 하나만 냅니다 (명세 11.1).
 * - 터치 베젤은 손가락 접촉(누름/뗌) 신호를 앱에 주지 않습니다. 재접촉은 회전 이벤트 간격으로만 추정하며
 *   GESTURE_START 원시 기록에 "derived"로 표시합니다. 실제 INPUT_DOWN으로 다루지 않습니다.
 * - 값이 한 단계 바뀔 때마다 stepper.config.haptic 설정대로 진동합니다 (경계에서 값이 안 바뀌면 진동 없음,
 *   목표 도달 시 특별한 진동 없음). 진동할 때마다 HAPTIC 원시 기록을 남깁니다.
 * - 홈·뒤로 버튼이나 물리 회전 베젤로 대체하지 않습니다.
 */
class WatchTouchBezel(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    val stepper: BezelStepper = BezelStepper(),
    /** 단계 진동. null이면 Content()가 화면에 처음 나올 때 만듭니다. */
    var haptics: WatchHaptics? = null,
) : ExperimentInput {

    override val condition = Condition.C3

    /** 화면에 그릴 값 */
    var value by mutableStateOf<VirtualVolume?>(null)
        private set

    /** 마지막 회전 이벤트 요약 (검증 화면 표시용) */
    var lastRotary by mutableStateOf<String?>(null)
        private set

    var rawListener: ((WatchRawInput) -> Unit)? = null

    private var attempt: Attempt? = null
    private var onChange: ((InputEvent) -> Unit)? = null

    val isActive: Boolean get() = attempt != null

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        stepper.reset()
        this.attempt = attempt
        this.onChange = onChange
        value = attempt.task.start
    }

    override fun stop() {
        attempt = null
        onChange = null
        stepper.reset()
    }

    /** 시행 전 미리보기 값. 시행 중에는 무시 */
    fun preview(v: VirtualVolume?) {
        if (attempt == null) value = v
    }

    /**
     * 회전 이벤트 1개 처리. Content()가 호출하며, 다른 화면에서 직접 연결할 때도 이것만 부르면 됩니다.
     * @param rawPixels 원본 회전량 (RotaryScrollEvent.verticalScrollPixels)
     * @param eventUptimeMs 원본 이벤트 시각 (RotaryScrollEvent.uptimeMillis, 다른 시계라 기록용으로만 사용)
     * @return 항상 true (회전 이벤트 소비)
     */
    fun onRotary(rawPixels: Float, eventUptimeMs: Long): Boolean {
        val now = clock()
        val a = attempt
        if (a == null) {
            lastRotary = "px=$rawPixels (시행 전)"
            raw(WatchRawInput.Kind.IGNORED, "not_active;px=$rawPixels;uptime=$eventUptimeMs", now)
            return true
        }
        val r = stepper.feed(rawPixels, now)
        if (r.newGesture) {
            raw(WatchRawInput.Kind.GESTURE_START, "derived;gap_ms=${r.gapMs ?: "first"};${stepper.config.describe()}", now)
        }
        raw(
            WatchRawInput.Kind.ROTARY,
            "px=$rawPixels;uptime=$eventUptimeMs;dir=${r.direction};steps=${r.steps};acc=${r.accumulated};gap_ms=${r.gapMs ?: ""}",
            now,
        )
        lastRotary = "px=$rawPixels → ${if (r.steps > 0) "+" else ""}${r.steps}단계"

        val before = value ?: return true
        if (r.steps == 0) return true
        val next = BezelStepper.apply(before.percent, r.steps)
        if (next == before.percent) {
            raw(WatchRawInput.Kind.BOUNDARY, "steps=${r.steps};value=${before.percent}", now)
            return true
        }
        val after = VirtualVolume(next)
        value = after
        onChange?.invoke(InputEvent(a.id, ExperimentDevice.WEAR, now, before, after))
        val effect = stepper.config.haptic
        if (effect != BezelConfig.Haptic.OFF) {
            val played = haptics?.play(effect) ?: false
            raw(WatchRawInput.Kind.HAPTIC, "effect=$effect;played=$played;value=${after.percent}", now)
        }
        return true
    }

    private fun raw(kind: WatchRawInput.Kind, detail: String, at: Long) {
        rawListener?.invoke(WatchRawInput(attempt?.id ?: "", at, kind, "C3_BEZEL", value?.percent, detail))
    }

    /**
     * 베젤 입력을 받는 영역. 시행 화면 전체를 이걸로 감싸세요.
     * 화면이 바뀌어 포커스를 잃으면 회전 이벤트가 안 들어오므로, 다시 보일 때마다 포커스를 요청합니다.
     */
    @Composable
    fun Content(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        val focus = remember { FocusRequester() }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            if (haptics == null) haptics = WatchHaptics(context.applicationContext)
            focus.requestFocus()
        }
        Box(
            modifier
                .onRotaryScrollEvent { onRotary(it.verticalScrollPixels, it.uptimeMillis) }
                .focusRequester(focus)
                .focusable(),
        ) { content() }
    }
}
