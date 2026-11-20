package com.hci.teamp.experiment.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * C1 막대와 C2 슬라이더의 공통 모양.
 * 입력 방식 외의 시각 차이가 실험에 영향을 주지 않도록 길이·두께·색·위치를 같게 그립니다.
 * C2만 조작용 흰 손잡이가 추가됩니다.
 */
data class SliderStyle(
    /** 막대(트랙) 두께 */
    val track: Dp,
    /** C2 손잡이 지름 */
    val thumb: Dp,
    /** 영역 높이 = C2 손잡이 터치 영역. C1도 같은 높이를 차지해 화면 위치가 같음 */
    val touch: Dp,
    val fill: Color = Color(0xFF55B8BE),
    val rest: Color = Color(0xFF4A5568),
    val thumbColor: Color = Color.White,
) {
    companion object {
        val Phone = SliderStyle(track = 20.dp, thumb = 40.dp, touch = 64.dp)
    }
}

/**
 * 트랙과 채움을 그립니다. C1·C2 모두 이 함수 하나만 사용합니다.
 * 채움 끝 = C2 손잡이 중심 위치 (left..right 구간에서 value%).
 */
internal fun DrawScope.drawVolumeTrack(style: SliderStyle, value: Int?, left: Float, right: Float) {
    val trackH = style.track.toPx()
    val cy = size.height / 2
    val r = CornerRadius(trackH / 2)
    drawRoundRect(style.rest, topLeft = Offset(0f, cy - trackH / 2), size = Size(size.width, trackH), cornerRadius = r)
    val v = value ?: return
    val end = SliderMath.thumbX(v, left, right)
    drawRoundRect(style.fill, topLeft = Offset(0f, cy - trackH / 2), size = Size(end, trackH), cornerRadius = r)
}

/** C1 읽기 전용 음량 막대. 터치에 반응하지 않습니다. value가 null이면 채움 없이 트랙만. */
@Composable
fun VolumeBar(value: Int?, modifier: Modifier = Modifier, style: SliderStyle = SliderStyle.Phone) {
    val density = LocalDensity.current
    val thumbPx = with(density) { style.thumb.toPx() }
    BoxWithConstraints(modifier.height(style.touch)) {
        val widthPx = with(density) { maxWidth.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            drawVolumeTrack(style, value, thumbPx / 2, widthPx - thumbPx / 2)
        }
    }
}
