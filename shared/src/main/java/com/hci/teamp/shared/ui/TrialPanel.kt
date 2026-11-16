package com.hci.teamp.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hci.teamp.shared.experiment.VolumeRules
import kotlin.math.abs
import kotlin.math.roundToInt

/** 시안의 색상 */
object TrialColors {
    val Background = Color(0xFF1A1D25)
    val Label = Color(0xFFB8C0CC)
    val Value = Color.White
    val Fill = Color(0xFF55B8BE)
    val BarTrack = Color(0xFF334155)
    val SliderTrack = Color(0xFF5B6880)
    val Thumb = Color.White
    val BezelRing = Color(0xFF4A9EA1)
    val Button = Color(0xFFE8ECF4)
    val ButtonText = Color(0xFF1A1D25)
    val Hint = Color(0xFFB8C0CC)
}

/** 화면 중앙 조작 영역의 종류 */
enum class CenterMode {
    /** C1·C3: 읽기 전용 막대 */
    BAR,
    /** C2·C4: 손잡이를 끌어야 하는 슬라이더 */
    SLIDER,
}

/** 기기별 크기 */
data class TrialSizes(
    val label: TextUnit,
    val number: TextUnit,
    val barHeight: Dp,
    val sliderTrack: Dp,
    val thumb: Dp,
    val thumbTouch: Dp,
    val button: TextUnit,
    val buttonHeight: Dp,
) {
    companion object {
        val Phone = TrialSizes(20.sp, 60.sp, 26.dp, 16.dp, 34.dp, 56.dp, 26.sp, 72.dp)
        val Watch = TrialSizes(13.sp, 34.sp, 18.dp, 10.dp, 26.dp, 44.dp, 17.sp, 44.dp)
    }
}

/** 슬라이더 원시 입력 콜백 */
interface SliderListener {
    fun onDown(x: Float, value: Int) {}
    fun onValue(value: Int, x: Float) {}
    fun onUp(x: Float, value: Int) {}
    /** 손잡이가 아닌 트랙을 눌렀을 때 (값은 바뀌지 않음) */
    fun onTrackTapBlocked(x: Float) {}
}

/**
 * 명세 5.1 공통 시행 화면: 상단 목표/현재, 중앙 막대 또는 슬라이더, 하단 완료 버튼.
 * - 목표 눈금, 정답 색상 변화, 진동 없음
 * - target/current가 null이면 숫자를 숨깁니다 (카운트다운 중 등)
 */
@Composable
fun TrialPanel(
    target: Int?,
    current: Int?,
    mode: CenterMode,
    sizes: TrialSizes,
    modifier: Modifier = Modifier,
    hint: String? = null,
    round: Boolean = false,
    bezelRing: Boolean = false,
    completeEnabled: Boolean = true,
    sliderListener: SliderListener? = null,
    onComplete: () -> Unit = {},
) {
    val shape = if (round) CircleShape else RoundedCornerShape(0.dp)
    Box(
        modifier
            .clip(shape)
            .background(TrialColors.Background)
            .then(if (bezelRing) Modifier.border(9.dp, TrialColors.BezelRing, CircleShape) else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (round) 30.dp else 28.dp, vertical = if (round) 26.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(if (round) 0.9f else 1.2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Number("목표", target, sizes)
                Number("현재", current, sizes)
            }
            Spacer(Modifier.weight(if (round) 0.5f else 1.2f))
            when (mode) {
                CenterMode.BAR -> VolumeBar(current ?: 0, sizes, Modifier.fillMaxWidth())
                CenterMode.SLIDER -> DragSlider(
                    value = current ?: 0,
                    enabled = current != null,
                    sizes = sizes,
                    listener = sliderListener,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hint != null) {
                Spacer(Modifier.height(if (round) 6.dp else 28.dp))
                BasicText(hint, style = TextStyle(color = TrialColors.Hint, fontSize = sizes.label * 0.85f, textAlign = TextAlign.Center))
            }
            Spacer(Modifier.weight(if (round) 0.5f else 2f))
            CompleteButton(sizes, completeEnabled, onComplete, Modifier.fillMaxWidth(if (round) 0.82f else 1f))
            Spacer(Modifier.weight(if (round) 0.35f else 0.4f))
        }
    }
}

@Composable
private fun Number(label: String, value: Int?, sizes: TrialSizes) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(label, style = TextStyle(color = TrialColors.Label, fontSize = sizes.label))
        BasicText(
            value?.toString() ?: "–",
            style = TextStyle(color = TrialColors.Value, fontSize = sizes.number, fontWeight = FontWeight.Bold),
        )
    }
}

/** C1·C3 읽기 전용 막대 (터치에 반응하지 않음) */
@Composable
fun VolumeBar(value: Int, sizes: TrialSizes, modifier: Modifier = Modifier) {
    Canvas(modifier.height(sizes.barHeight)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(TrialColors.BarTrack, cornerRadius = r)
        val w = size.width * (value.coerceIn(0, 100) / 100f)
        if (w > 0f) drawRoundRect(TrialColors.Fill, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/**
 * C2·C4 슬라이더. 명세 4.3/4.5:
 * - 손잡이를 직접 끌어야만 값이 바뀜 (트랙 탭은 무시하고 onTrackTapBlocked로 알림)
 * - 끄는 동안 5단위 값이 바뀔 때마다 onValue 호출 (손을 떼기 전에도 즉시)
 * - 손잡이를 잡은 위치를 유지해서, 잡는 순간 값이 튀지 않음
 */
@Composable
fun DragSlider(
    value: Int,
    sizes: TrialSizes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    listener: SliderListener? = null,
) {
    val currentValue by rememberUpdatedState(value)
    val currentListener by rememberUpdatedState(listener)
    val currentEnabled by rememberUpdatedState(enabled)
    val density = LocalDensity.current
    val thumbPx = with(density) { sizes.thumb.toPx() }
    val touchPx = with(density) { sizes.thumbTouch.toPx() }

    BoxWithConstraints(modifier.height(sizes.thumbTouch)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val left = thumbPx / 2
        val right = widthPx - thumbPx / 2
        fun xOf(v: Int) = left + (right - left) * (v / 100f)
        fun valueAt(x: Float): Int {
            val frac = ((x - left) / (right - left)).coerceIn(0f, 1f)
            return VolumeRules.normalize((frac * 100).roundToInt())
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentEnabled) return@awaitEachGesture
                        val thumbX = xOf(currentValue)
                        if (abs(down.position.x - thumbX) > touchPx / 2) {
                            currentListener?.onTrackTapBlocked(down.position.x)
                            return@awaitEachGesture
                        }
                        down.consume()
                        val grabOffset = down.position.x - thumbX
                        var last = currentValue
                        currentListener?.onDown(down.position.x, last)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                currentListener?.onUp(change.position.x, last)
                                break
                            }
                            if (change.positionChange() != Offset.Zero) change.consume()
                            val v = valueAt(change.position.x - grabOffset)
                            if (v != last) {
                                last = v
                                currentListener?.onValue(v, change.position.x)
                            }
                        }
                    }
                },
        ) {
            val trackH = sizes.sliderTrack.toPx()
            val cy = size.height / 2
            val r = CornerRadius(trackH / 2)
            drawRoundRect(TrialColors.SliderTrack, topLeft = Offset(0f, cy - trackH / 2), size = Size(size.width, trackH), cornerRadius = r)
            val tx = xOf(value)
            drawRoundRect(TrialColors.Fill, topLeft = Offset(0f, cy - trackH / 2), size = Size(tx, trackH), cornerRadius = r)
            drawCircle(Color(0x33000000), radius = thumbPx / 2 + 2, center = Offset(tx, cy + 2))
            drawCircle(TrialColors.Thumb, radius = thumbPx / 2, center = Offset(tx, cy))
        }
    }
}

@Composable
private fun CompleteButton(sizes: TrialSizes, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier
            .height(sizes.buttonHeight)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) TrialColors.Button else TrialColors.Button.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { currentOnClick() },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("완료", style = TextStyle(color = TrialColors.ButtonText, fontSize = sizes.button, fontWeight = FontWeight.Bold))
    }
}
