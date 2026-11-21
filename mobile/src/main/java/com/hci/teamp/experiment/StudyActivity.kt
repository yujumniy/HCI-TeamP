package com.hci.teamp.experiment

import android.content.Intent
import android.view.WindowManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.hci.teamp.experiment.input.VolumeBar
import com.hci.teamp.experiment.ui.BigAdminButton
import com.hci.teamp.experiment.ui.BigButton
import com.hci.teamp.experiment.ui.CountdownCard
import com.hci.teamp.experiment.ui.TrialFrame
import com.hci.teamp.experiment.ui.TrialUi
import com.hci.teamp.experiment.ui.ValueCards
import com.hci.teamp.mobile.debug.CommTestActivity
import com.hci.teamp.shared.experiment.RecordedPhase
import com.hci.teamp.shared.experiment.RecordedTrial
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.DeviceType
import com.hci.teamp.shared.model.EndStatus
import com.hci.teamp.shared.model.TrialPhase
import kotlinx.coroutines.delay

class StudyActivity : ComponentActivity() {
    private var foreground by mutableStateOf(false)
    private lateinit var model: StudyViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model = ViewModelProvider(this)[StudyViewModel::class.java]
        setContent {
            MaterialTheme {
                if (isDarkScreen()) {
                    DarkScreens()
                } else {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) { AdminScreens() }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        model.keys.onKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        model.keys.onKeyEvent(event) || super.onKeyUp(keyCode, event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::model.isInitialized && !hasFocus) model.keys.cancelHold("focus_lost")
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        model.stopTestPlay()
        model.interrupt()
        super.onStop()
    }

    /** 참가자가 보는 화면(안내·연습 완료·휴식·폰 준비·폰 시행)은 같은 어두운 시행 화면 틀을 씁니다. */
    private fun isDarkScreen(): Boolean = when (model.screen) {
        StudyScreen.INSTRUCTIONS, StudyScreen.MAIN_GATE, StudyScreen.REST, StudyScreen.PHONE -> true
        StudyScreen.READY -> model.config?.device == DeviceType.PHONE
        else -> false
    }

    // ---------------- 참가자 화면 (어두운 틀) ----------------

    @Composable
    private fun DarkScreens() {
        when (model.screen) {
            StudyScreen.INSTRUCTIONS -> {
                val cfg = checkNotNull(model.config)
                MessageScreen(
                    header = "${model.participantId} · ${cfg.condition}",
                    title = "${methodOf(cfg.condition)}로\n목표 음량을 맞춘 뒤\n완료를 눌러 주세요.",
                    button = "연습 시작",
                    onClick = model::continuePreparation,
                )
            }
            StudyScreen.MAIN_GATE -> MessageScreen(
                title = "연습을 마쳤습니다.",
                button = "본 실험 시작",
                onClick = model::continuePreparation,
            )
            StudyScreen.REST -> {
                var remaining by remember(model.restUntilMs) {
                    mutableLongStateOf((model.restUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                }
                LaunchedEffect(model.restUntilMs) {
                    do {
                        remaining = (model.restUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        delay(100)
                    } while (remaining > 0L)
                }
                MessageScreen(
                    title = "휴식\n${(remaining + 999) / 1000}초",
                    button = "다음 조건",
                    onClick = model::continuePreparation,
                    enabled = remaining == 0L,
                )
            }
            StudyScreen.READY -> {
                // 폰 조건 준비: 시행 화면과 같은 배치에서 숫자는 숨김, 버튼만 '준비'
                val cfg = checkNotNull(model.config)
                TrialFrame(
                    header = "${cfg.condition} · ${if (cfg.phase == TrialPhase.PRACTICE) "연습" else "본 시행"}",
                    values = { ValueCards(null, null) },
                    center = { CenterControl(cfg.condition, null, interactive = false); NoticeLine() },
                    button = { BigButton("준비", model::prepareTrial, enabled = !model.busy) },
                    footer = { AdminLink() },
                )
            }
            StudyScreen.PHONE -> model.trial?.let { PhoneTrial(it) }
            else -> Unit
        }
    }

    @Composable
    private fun MessageScreen(title: String, button: String, onClick: () -> Unit, enabled: Boolean = true, header: String = "") {
        TrialFrame(
            header = header,
            values = {
                Text(
                    title,
                    color = TrialUi.Value,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 42.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            center = { NoticeLine() },
            button = { BigButton(button, onClick, enabled = enabled && !model.busy) },
            footer = { AdminLink() },
        )
    }

    @Composable
    private fun PhoneTrial(trial: RecordedTrial) {
        val state by trial.state.collectAsState()
        LaunchedEffect(trial, state.phase, foreground) {
            while (foreground && (trial.state.value.phase == RecordedPhase.COUNTDOWN ||
                    trial.state.value.phase == RecordedPhase.ACTIVE)) {
                trial.tick()
                delay(25)
            }
        }
        val cfg = trial.config
        val practice = cfg.phase == TrialPhase.PRACTICE
        when (state.phase) {
            RecordedPhase.READY -> TrialFrame(
                values = { ValueCards(null, null) },
                center = { CenterControl(cfg.condition, null, interactive = false) },
                button = { BigButton("준비", trial::ready) },
            )
            RecordedPhase.COUNTDOWN -> TrialFrame(
                // 카운트다운 중에는 시작값·목표값과 손잡이를 숨김 (명세 5.2)
                values = { CountdownCard(state.countdownSeconds) },
                center = { CenterControl(cfg.condition, null, interactive = false) },
                button = { BigButton("완료", {}, enabled = false) },
            )
            RecordedPhase.ACTIVE -> TrialFrame(
                values = { ValueCards(cfg.targetVolume, state.value) },
                center = { CenterControl(cfg.condition, state.value, interactive = true) },
                button = { BigButton("완료", trial::complete) },
                footer = {
                    TextButton(onClick = model::abort) { Text("관리자 긴급 중단", color = TrialUi.Label, fontSize = 13.sp) }
                },
            )
            RecordedPhase.SAVING -> TrialFrame(
                values = { ValueCards(null, null) },
                center = { InfoText("기록 저장 중입니다.") },
                button = { BigButton("다음 과제", {}, enabled = false) },
            )
            RecordedPhase.RESULT -> TrialFrame(
                values = {
                    // 연습에서만 결과 표시 (명세 6.1). 본 시행은 정답 피드백 없음
                    if (practice) ValueCards(cfg.targetVolume, state.value) else ValueCards(null, null)
                },
                center = {
                    InfoText(
                        when (state.outcome) {
                            EndStatus.COMPLETED -> "시행 종료"
                            EndStatus.TIMEOUT -> "시간 초과"
                            else -> "중단된 시행"
                        },
                    )
                    if (practice) {
                        InfoText(if (cfg.targetVolume == state.value) "목표와 일치" else "목표와 불일치")
                    }
                },
                button = { BigButton("다음 과제", model::next, enabled = !model.busy) },
            )
            RecordedPhase.ERROR -> TrialFrame(
                values = { ValueCards(null, null) },
                center = {
                    InfoText(state.error ?: "저장 오류")
                    InfoText("앱을 다시 열어 관리자 화면에서 기록을 확인해 주세요.")
                },
                button = { BigButton("다음 과제", {}, enabled = false) },
            )
        }
    }

    /**
     * 화면 중앙 조작 영역. C1·C2가 같은 길이·두께·색의 막대를 쓰고, C2만 손잡이가 있습니다.
     * value == null 이면 채움·손잡이 없이 트랙만 (준비·카운트다운).
     */
    @Composable
    private fun ColumnScope.CenterControl(condition: ConditionId, value: Int?, interactive: Boolean) {
        if (condition == ConditionId.C2 && interactive) {
            model.slider.Content(Modifier.fillMaxWidth())
        } else {
            VolumeBar(value, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (condition == ConditionId.C1) "측면 버튼으로 조절" else "손잡이를 끌어서 조절",
            color = TrialUi.Label,
            fontSize = 17.sp,
        )
    }

    @Composable
    private fun InfoText(text: String) =
        Text(text, color = TrialUi.Value, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

    @Composable
    private fun NoticeLine() {
        if (model.notice.isNotBlank()) {
            Text(model.notice, color = TrialUi.TargetAccent, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun AdminLink() {
        if (!model.busy) TextButton(onClick = model::showAdmin) { Text("관리자 화면", color = TrialUi.Label, fontSize = 13.sp) }
    }

    private fun methodOf(c: ConditionId) = when (c) {
        ConditionId.C1 -> "폰 옆면의 물리 음량 버튼"
        ConditionId.C2 -> "폰 화면의 슬라이더 손잡이"
        ConditionId.C3 -> "워치의 터치 베젤"
        ConditionId.C4 -> "워치 화면의 슬라이더 손잡이"
    }

    // ---------------- 관리자·워치 대기 화면 (밝은 화면) ----------------

    @Composable
    private fun AdminScreens() {
        when (model.screen) {
            StudyScreen.ADMIN -> {
                Text("실험 관리", style = MaterialTheme.typography.headlineSmall)
                Text("기술 검증용 통합본 · 실기기 저장·동기화 검증 후 본 실험을 진행해 주세요.")
                AudioCheckCard()
                BigAdminButton("참가자 새로 등록", model::register, enabled = !model.busy)
                model.participants.forEach { participant ->
                    Text("${participant.participantId} · ${participant.groupId} · ${participant.conditionOrder}")
                    BigAdminButton("${participant.participantId} 계속하기", { model.resume(participant) }, enabled = !model.busy)
                    OutlinedButton(onClick = { model.export(participant) }, enabled = !model.busy) {
                        Text("${participant.participantId} CSV ZIP 내보내기")
                    }
                }
                OutlinedButton(onClick = { model.export(null) }, enabled = !model.busy) {
                    Text("전체 CSV ZIP 내보내기")
                }
                OutlinedButton(onClick = {
                    startActivity(Intent(this@StudyActivity, CommTestActivity::class.java))
                }, enabled = !model.busy) { Text("T3 통신 검증") }
            }
            StudyScreen.READY -> {
                // 워치 조건 준비 (폰 조건은 어두운 시행 화면에서 처리)
                val cfg = checkNotNull(model.config)
                Text("${cfg.condition} · ${if (cfg.phase == TrialPhase.PRACTICE) "연습" else "본 시행"} ${cfg.orderIndex}")
                BigAdminButton("워치에 시행 전달", model::prepareTrial, enabled = !model.busy)
            }
            StudyScreen.WATCH -> {
                Text("워치에서 시행 중입니다.")
                BigAdminButton("결과 저장 확인", model::checkWatchResult, enabled = !model.busy)
                OutlinedButton(onClick = model::abort, enabled = !model.busy) { Text("관리자 긴급 중단") }
            }
            StudyScreen.FINISHED -> {
                Text("전 조건을 마쳤습니다.")
                OutlinedButton(onClick = { model.export() }, enabled = !model.busy) { Text("다시 내보내기") }
            }
            else -> Unit
        }
        if (model.notice.isNotBlank()) Text(model.notice)
        if (!model.busy && model.screen != StudyScreen.ADMIN && model.screen != StudyScreen.WATCH) {
            TextButton(onClick = model::showAdmin) { Text("관리자 화면") }
        }
    }

    /** 실험 전 음량 확인: 시스템 미디어 음량, 시험 재생, 가상 음량 → 재생 크기 곡선 */
    @Composable
    private fun AudioCheckCard() {
        LaunchedEffect(foreground) {
            while (foreground) {
                model.refreshMediaVolume()
                delay(500)
            }
        }
        val (media, max) = model.mediaVolume
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("음량 확인", style = MaterialTheme.typography.titleMedium)
                Text(
                    "시스템 미디어 음량 $media / $max" +
                        if (media == 0) " · 0이면 벨소리가 들리지 않습니다. 실험 전에 올려 주세요." else "",
                    color = if (media == 0) Color(0xFFB3261E) else Color.Unspecified,
                    fontWeight = if (media == 0) FontWeight.Bold else FontWeight.Normal,
                )
                OutlinedButton(onClick = model::showSystemVolumePanel, modifier = Modifier.fillMaxWidth()) {
                    Text("시스템 음량 패널 열기 (앱은 값을 바꾸지 않음)")
                }
                Text("시험 재생 (가상 음량, 2.5초)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(10, 25, 50, 75, 100).forEach { v ->
                        val playing = model.testPlaying == v
                        Button(
                            onClick = { model.testPlay(v) },
                            enabled = !model.busy,
                            modifier = Modifier.weight(1f).height(52.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = if (playing) ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            else ButtonDefaults.buttonColors(),
                        ) { Text("$v", fontSize = 16.sp) }
                    }
                }
                if (model.testPlaying != null) {
                    TextButton(onClick = model::stopTestPlay) { Text("재생 정지 (${model.testPlaying} 재생 중)") }
                }
                Text("음량 → 재생 크기 곡선 (파일럿에서 비교 후 확정)", style = MaterialTheme.typography.labelLarge)
                GainCurve.entries.forEach { curve ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = model.gainCurve == curve, onClick = { model.selectGainCurve(curve) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model.gainCurve == curve, onClick = { model.selectGainCurve(curve) })
                        Column {
                            Text(curve.label + if (curve == GainCurve.LINEAR) " (기본)" else "")
                            Text(curve.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "선택한 곡선은 관리자 시험 재생에만 적용됩니다. 실제 시행은 폰·워치 모두 기존 선형 게인을 사용합니다. 변경 시 양쪽 적용과 명세 갱신이 필요합니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
