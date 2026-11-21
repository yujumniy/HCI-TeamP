package com.hci.teamp.experiment.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 폰 시행 화면 공통 색·크기 (시안 기준). 조건(C1/C2)과 단계(준비/시행/결과)에 관계없이 같은 값을 씁니다. */
object TrialUi {
    val Background = Color(0xFF1A1D25)
    val Card = Color(0xFF252A34)
    val Label = Color(0xFFB8C0CC)
    val Value = Color.White
    val TargetAccent = Color(0xFFFFC857)
    val CurrentAccent = Color(0xFF55B8BE)
    val ButtonBg = Color(0xFFE8ECF4)
    val ButtonText = Color(0xFF1A1D25)
    val ButtonHeight = 72.dp
    val ButtonText_ = 24.sp
    val NumberSize = 72.sp
    val LabelSize = 22.sp
}

/**
 * 시행 화면 틀: 위아래 여백을 같은 비율로 두어 숫자·막대·버튼을 화면 가운데에 모읍니다.
 * - values: 목표/현재 카드
 * - center: C1 막대 또는 C2 슬라이더 (+ 안내 문구)
 * - button: 준비 / 완료 / 다음 과제 (같은 크기·같은 위치)
 * - footer: 화면 아래쪽 관리자 기능 (완료 버튼과 충분히 떨어뜨림)
 */
@Composable
fun TrialFrame(
    values: @Composable () -> Unit,
    center: @Composable ColumnScope.() -> Unit,
    button: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
    header: String = "",
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(TrialUi.Background)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(header, color = TrialUi.Label, fontSize = 15.sp, modifier = Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))
        values()
        Spacer(Modifier.height(48.dp))
        Column(Modifier.fillMaxWidth().height(120.dp), horizontalAlignment = Alignment.CenterHorizontally) { center() }
        Spacer(Modifier.height(40.dp))
        button()
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { footer() }
    }
}

/**
 * 목표·현재 음량. 두 카드로 나누고, 이름표와 테두리 색으로 구분합니다.
 * 테두리 색은 항상 고정입니다 (목표 도달 시 색 변화 없음, 명세 5.1).
 * null이면 "–" (준비·카운트다운 중 숨김, 명세 5.2)
 */
@Composable
fun ValueCards(target: Int?, current: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ValueCard("목표", target, TrialUi.TargetAccent, Modifier.weight(1f))
        ValueCard("현재", current, TrialUi.CurrentAccent, Modifier.weight(1f))
    }
}

@Composable
private fun ValueCard(label: String, value: Int?, accent: Color, modifier: Modifier) {
    Column(
        modifier
            .border(BorderStroke(2.dp, accent), RoundedCornerShape(20.dp))
            .background(TrialUi.Card, RoundedCornerShape(20.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = accent, fontSize = TrialUi.LabelSize, fontWeight = FontWeight.Bold)
        Text(
            value?.toString() ?: "–",
            color = TrialUi.Value,
            fontSize = TrialUi.NumberSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** 카운트다운 숫자. 값 카드와 같은 높이를 차지해 화면이 흔들리지 않게 합니다. */
@Composable
fun CountdownCard(seconds: Int) {
    Column(
        Modifier.fillMaxWidth().background(TrialUi.Card, RoundedCornerShape(20.dp)).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("시작까지", color = TrialUi.Label, fontSize = TrialUi.LabelSize)
        Text("$seconds", color = TrialUi.Value, fontSize = TrialUi.NumberSize, fontWeight = FontWeight.Bold)
    }
}

/** 준비·완료·다음 과제 등 주요 버튼. 모든 화면·조건에서 같은 크기 */
@Composable
fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(TrialUi.ButtonHeight),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = TrialUi.ButtonBg,
            contentColor = TrialUi.ButtonText,
            disabledContainerColor = TrialUi.ButtonBg.copy(alpha = 0.35f),
            disabledContentColor = TrialUi.ButtonText.copy(alpha = 0.6f),
        ),
    ) { Text(text, fontSize = TrialUi.ButtonText_, fontWeight = FontWeight.Bold) }
}

/** 관리자 화면 등 밝은 배경용 큰 버튼 (같은 높이) */
@Composable
fun BigAdminButton(text: String, onClick: () -> Unit, enabled: Boolean = true, outlined: Boolean = false) {
    val m = Modifier.fillMaxWidth().height(56.dp)
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = m) { Text(text, fontSize = 18.sp) }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = m) { Text(text, fontSize = 18.sp) }
    }
}

@Composable
fun SmallGap() = Spacer(Modifier.width(8.dp))
