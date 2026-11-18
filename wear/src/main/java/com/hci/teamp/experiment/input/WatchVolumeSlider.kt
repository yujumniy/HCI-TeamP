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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentDevice
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.VirtualVolume

/**
 * C4 워치 수평 슬라이더 (명세 4.5). 동작 규칙은 팀원 1의 C2 PhoneVolumeSlider와 같습니다.
 *
 * 사용법: 시행이 ACTIVE가 되면 start(), 끝나면 stop(). 화면 가운데에 Content()를 배치합니다.
 *
 * - 손잡이를 직접 끌어야만 값이 바뀝니다. 손잡이 밖 트랙을 누르면 값은 그대로이고 차단 횟수만 늘어납니다.
 * - 끄는 동안 5단위 값이 바뀔 때마다 즉시 InputEvent를 냅니다 (손을 떼기 전에도).
 * - 여러 단계를 한 번에 건너뛰면 실제 관찰된 값 하나만 냅니다 (중간값을 만들지 않음, 명세 11.1).
 * - 잡은 위치와 손잡이 중심의 차이를 유지해서, 잡는 순간 값이 튀지 않습니다.
 * - 원형 화면에서는 가운데 높이가 가장 넓으므로 화면 세로 가운데에 두고, 가로는 화면의 약 80% 이내로 둡니다.
 * - 이 화면을 쓰는 Activity는 '쓸어서 닫기'를 꺼야 합니다 (styles.xml의 Theme.HciWear.NoSwipe).
 *   켜져 있으면 손잡이를 오른쪽으로 끌 때 앱이 닫힐 수 있습니다.
 */
class WatchVolumeSlider(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ExperimentInput {

    override val condition = Condition.C4

    var value by mutableStateOf<VirtualVolume?>(null)
        private set

    var trackTapBlocked by mutableIntStateOf(0)
        private set

    var rawListener: ((WatchRawInput) -> Unit)? = null

    private var attempt: Attempt? = null
    private var onChange: ((InputEvent) -> Unit)? = null

    val isActive: Boolean get() = attempt != null

    override fun start(attempt: Attempt, onChange: (InputEvent) -> Unit) {
        require(attempt.condition == condition)
        this.attempt = attempt
        this.onChange = onChange
        value = attempt.task.start
        trackTapBlocked = 0
    }

    override fun stop() {
        attempt = null
        onChange = null
    }

    /** 시행 전 미리보기 값. 시행 중에는 무시 */
    fun preview(v: VirtualVolume?) {
        if (attempt == null) value = v
    }

    internal fun onThumbDown(x: Float, y: Float) = raw(WatchRawInput.Kind.DOWN, "x=%.1f;y=%.1f".format(x, y))

    internal fun onThumbUp(x: Float, cause: String) = raw(WatchRawInput.Kind.UP, "x=%.1f;cause=$cause".format(x))

    internal fun onInactiveTouch(x: Float, y: Float) =
        raw(WatchRawInput.Kind.IGNORED, "not_active;x=%.1f;y=%.1f".format(x, y))

    internal fun onTrackTap(x: Float, y: Float) {
        if (attempt == null) return
        trackTapBlocked++
        raw(WatchRawInput.Kind.TRACK_TAP_BLOCKED, "x=%.1f;y=%.1f".format(x, y))
    }

    /** 끄는 중 계산된 값. 실제로 바뀌었을 때만 이벤트 */
    internal fun onDragValue(percent: Int) {
        val a = attempt ?: return
        val before = value ?: return
        val next = WatchSliderMath.normalize(percent)
        if (next == before.percent) return
        val after = VirtualVolume(next)
        value = after
        onChange?.invoke(InputEvent(a.id, ExperimentDevice.WEAR, clock(), before, after))
    }

    private fun raw(kind: WatchRawInput.Kind, detail: String) {
        rawListener?.invoke(WatchRawInput(attempt?.id ?: "", clock(), kind, "C4_SLIDER", value?.percent, detail))
    }

    @Composable
    fun Content(modifier: Modifier = Modifier, style: WatchSliderStyle = WatchSliderStyle.Default) {
        WatchSliderView(this, style, modifier)
    }
}

data class WatchSliderStyle(
    val track: Dp,
    val thumb: Dp,
    /** 손잡이 터치 영역 (가로·세로). 손가락 크기를 고려해 손잡이보다 크게 */
    val touch: Dp,
    val fill: Color = Color(0xFF55B8BE),
    val rest: Color = Color(0xFF5B6880),
    val thumbColor: Color = Color.White,
) {
    companion object {
        /** 파일럿에서 조정 (명세 19장: 터치 영역 및 화면 크기) */
        val Default = WatchSliderStyle(track = 10.dp, thumb = 26.dp, touch = 48.dp)
    }
}

@Composable
private fun WatchSliderView(slider: WatchVolumeSlider, style: WatchSliderStyle, modifier: Modifier) {
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
                        val cy = size.height / 2f
                        if (!slider.isActive) {
                            slider.onInactiveTouch(down.position.x, down.position.y)
                            return@awaitEachGesture
                        }
                        val v = current ?: return@awaitEachGesture
                        val thumbX = WatchSliderMath.thumbX(v, left, right)
                        if (!WatchSliderMath.hitsThumb(down.position.x, down.position.y, thumbX, cy, touchPx)) {
                            slider.onTrackTap(down.position.x, down.position.y)
                            return@awaitEachGesture
                        }
                        down.consume()
                        val grabOffset = down.position.x - thumbX
                        slider.onThumbDown(down.position.x, down.position.y)
                        var lastX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                slider.onThumbUp(lastX, "cancel")
                                break
                            }
                            lastX = change.position.x
                            if (!change.pressed) {
                                slider.onThumbUp(change.position.x, "up")
                                break
                            }
                            if (change.positionChange() != Offset.Zero) change.consume()
                            slider.onDragValue(WatchSliderMath.valueAt(change.position.x - grabOffset, left, right))
                        }
                    }
                },
        ) {
            val trackH = style.track.toPx()
            val cy = size.height / 2
            val r = CornerRadius(trackH / 2)
            drawRoundRect(style.rest, topLeft = Offset(0f, cy - trackH / 2), size = Size(size.width, trackH), cornerRadius = r)
            val v = current ?: return@Canvas
            val tx = WatchSliderMath.thumbX(v, left, right)
            drawRoundRect(style.fill, topLeft = Offset(0f, cy - trackH / 2), size = Size(tx, trackH), cornerRadius = r)
            drawCircle(style.thumbColor, radius = thumbPx / 2, center = Offset(tx, cy))
        }
    }
}
