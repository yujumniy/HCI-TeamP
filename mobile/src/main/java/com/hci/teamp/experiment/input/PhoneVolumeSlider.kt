package com.hci.teamp.experiment.input

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentDevice
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.VirtualVolume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * C2 스마트폰 수평 슬라이더 (명세 4.3).
 *
 * 사용법: 시행이 ACTIVE가 되면 start(), 끝나면 stop(). 화면에는 Content()를 배치합니다.
 *
 * - 손잡이를 직접 끌어야만 값이 바뀝니다. 손잡이 밖 트랙을 누르면 값은 그대로이고 차단 횟수만 늘어납니다.
 * - 끄는 동안 5단위 값이 바뀔 때마다 즉시 InputEvent를 냅니다 (손을 떼기 전에도).
 * - 손가락이 여러 단계를 한 번에 건너뛰면 실제 관찰된 값 하나만 냅니다 (중간값을 만들지 않음, 명세 11.1).
 * - 잡은 위치와 손잡이 중심의 차이를 유지해서, 잡는 순간 값이 튀지 않습니다.
 */
class PhoneVolumeSlider(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ExperimentInput {

    override val condition = Condition.C2

    /** 화면에 그릴 값. 시행 중이 아니면 마지막 값(또는 null) */
    var value by mutableStateOf<VirtualVolume?>(null)
        private set

    var trackTapBlocked by mutableIntStateOf(0)
        private set

    var rawListener: ((RawInput) -> Unit)? = null

    private var attempt: Attempt? = null
    private var onChange: ((InputEvent) -> Unit)? = null

    val isActive: Boolean get() = attempt != null

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        this.attempt = attempt
        this.onChange = onChange
        value = attempt.task.start
    }

    override fun stop() {
        attempt = null
        onChange = null
    }

    /** 시행 전 미리보기 값 (카운트다운 전 등). 시행 중에는 무시 */
    fun preview(v: VirtualVolume?) {
        if (attempt == null) value = v
    }

    internal fun onThumbDown(x: Float) = raw(RawInput.Kind.DOWN, "x=%.1f".format(x))

    internal fun onThumbUp(x: Float) = raw(RawInput.Kind.UP, "x=%.1f".format(x))

    /** 시행 전(준비 전)에 슬라이더를 만진 경우: 값은 그대로, 원시 기록만 */
    internal fun onInactiveTouch(x: Float) = raw(RawInput.Kind.IGNORED, "not_active;x=%.1f".format(x))

    internal fun onTrackTap(x: Float) {
        if (attempt == null) return
        trackTapBlocked++
        raw(RawInput.Kind.TRACK_TAP_BLOCKED, "x=%.1f".format(x))
    }

    /** 끄는 중 계산된 값. 실제로 바뀌었을 때만 이벤트 */
    internal fun onDragValue(percent: Int) {
        val a = attempt ?: return
        val before = value ?: return
        val next = SliderMath.normalize(percent)
        if (next == before.percent) return
        val after = VirtualVolume(next)
        value = after
        onChange?.invoke(InputEvent(a.id, ExperimentDevice.PHONE, clock(), before, after))
    }

    private fun raw(kind: RawInput.Kind, detail: String) {
        rawListener?.invoke(RawInput(attempt?.id ?: "", clock(), kind, "C2_SLIDER", value?.percent, detail))
    }

    /** 슬라이더 화면. 색과 크기는 시안 기준 */
    @Composable
    fun Content(modifier: Modifier = Modifier, style: SliderStyle = SliderStyle.Phone) {
        SliderView(this, style, modifier)
    }
}

/** 슬라이더 좌표 ↔ 값 계산 (단위 테스트 대상) */
object SliderMath {
    fun normalize(percent: Int): Int = (percent.coerceIn(0, 100) / 5f).roundToInt() * 5

    /** 손잡이 중심 x */
    fun thumbX(percent: Int, left: Float, right: Float): Float = left + (right - left) * (percent / 100f)

    /** 트랙 위 x → 5단위 값 */
    fun valueAt(x: Float, left: Float, right: Float): Int {
        val frac = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return normalize((frac * 100).roundToInt())
    }

    /** 누른 위치가 손잡이 터치 영역 안인지 */
    fun hitsThumb(downX: Float, thumbX: Float, touchWidth: Float): Boolean = abs(downX - thumbX) <= touchWidth / 2
}

@Composable
private fun SliderView(slider: PhoneVolumeSlider, style: SliderStyle, modifier: Modifier) {
    val density = LocalDensity.current
    val thumbPx = with(density) { style.thumb.toPx() }
    val touchPx = with(density) { style.touch.toPx() }
    val current by rememberUpdatedState(slider.value?.percent)

    BoxWithConstraints(modifier.height(style.touch)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val left = thumbPx / 2
        val right = widthPx - thumbPx / 2

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!slider.isActive) {
                            slider.onInactiveTouch(down.position.x)
                            return@awaitEachGesture
                        }
                        val v = current ?: return@awaitEachGesture
                        val thumbX = SliderMath.thumbX(v, left, right)
                        if (!SliderMath.hitsThumb(down.position.x, thumbX, touchPx)) {
                            slider.onTrackTap(down.position.x)
                            return@awaitEachGesture
                        }
                        down.consume()
                        val grabOffset = down.position.x - thumbX
                        slider.onThumbDown(down.position.x)
                        var lastX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                // 제스처가 취소된 경우에도 '뗌'을 기록해 조작 1회를 마무리합니다.
                                slider.onThumbUp(lastX)
                                break
                            }
                            lastX = change.position.x
                            if (!change.pressed) {
                                slider.onThumbUp(change.position.x)
                                break
                            }
                            if (change.positionChange() != Offset.Zero) change.consume()
                            slider.onDragValue(SliderMath.valueAt(change.position.x - grabOffset, left, right))
                        }
                    }
                },
        ) {
            drawVolumeTrack(style, current, left, right)
            val v = current ?: return@Canvas
            val tx = SliderMath.thumbX(v, left, right)
            val cy = size.height / 2
            drawCircle(Color(0x33000000), radius = thumbPx / 2 + 2, center = Offset(tx, cy + 2))
            drawCircle(style.thumbColor, radius = thumbPx / 2, center = Offset(tx, cy))
        }
    }
}
