package com.hci.teamp.experiment

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hci.teamp.mobile.debug.CommTestActivity
import com.hci.teamp.shared.experiment.RecordedPhase
import com.hci.teamp.shared.experiment.RecordedTrial
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.TrialPhase
import kotlinx.coroutines.delay

class StudyActivity : ComponentActivity() {
    private lateinit var model: StudyViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[StudyViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { StudyContent() }
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

    override fun onStop() {
        model.interrupt()
        super.onStop()
    }

    @Composable
    private fun StudyContent() {
        when (model.screen) {
            StudyScreen.ADMIN -> {
                Text("실험 관리", style = MaterialTheme.typography.headlineSmall)
                Text("기술 검증용 통합본 · 실기기 저장·동기화 검증 후 본 실험을 진행해 주세요.")
                Button(onClick = model::register, enabled = !model.busy) { Text("참가자 새로 등록") }
                model.participants.forEach { participant ->
                    Text("${participant.participantId} · ${participant.groupId} · ${participant.conditionOrder}")
                    Button(onClick = { model.resume(participant) }, enabled = !model.busy) {
                        Text("${participant.participantId} 계속하기")
                    }
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
            StudyScreen.INSTRUCTIONS -> {
                val cfg = checkNotNull(model.config)
                val method = when (cfg.condition) {
                    ConditionId.C1 -> "폰 옆면의 물리 음량 버튼"
                    ConditionId.C2 -> "폰 화면의 슬라이더 손잡이"
                    ConditionId.C3 -> "워치의 터치 베젤"
                    ConditionId.C4 -> "워치 화면의 슬라이더 손잡이"
                }
                Text("${model.participantId} · ${cfg.condition} 안내")
                Text("$method 로 목표 음량을 맞춘 뒤 완료를 눌러 주세요.")
                Button(onClick = model::continuePreparation) { Text("연습 시작") }
            }
            StudyScreen.MAIN_GATE -> {
                Text("연습을 마쳤습니다.")
                Button(onClick = model::continuePreparation) { Text("본 실험 시작") }
            }
            StudyScreen.REST -> {
                var remaining by remember(model.restUntilMs) { mutableLongStateOf((model.restUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)) }
                LaunchedEffect(model.restUntilMs) {
                    do {
                        remaining = (model.restUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        delay(100)
                    } while (remaining > 0L)
                }
                Text("휴식 · ${(remaining + 999) / 1000}초")
                Button(onClick = model::continuePreparation, enabled = remaining == 0L) { Text("다음 조건") }
            }
            StudyScreen.READY -> {
                val cfg = checkNotNull(model.config)
                Text("${cfg.condition} · ${if (cfg.phase == TrialPhase.PRACTICE) "연습" else "본 시행"} ${cfg.orderIndex}")
                Button(onClick = model::prepareTrial, enabled = !model.busy) {
                    Text(if (cfg.device == com.hci.teamp.shared.model.DeviceType.WATCH) "워치에 시행 전달" else "준비")
                }
            }
            StudyScreen.PHONE -> model.trial?.let { PhoneTrial(it) }
            StudyScreen.WATCH -> {
                Text("워치에서 시행 중입니다.")
                Button(onClick = model::checkWatchResult, enabled = !model.busy) { Text("결과 저장 확인") }
                OutlinedButton(onClick = model::abort, enabled = !model.busy) { Text("관리자 긴급 중단") }
            }
            StudyScreen.FINISHED -> {
                Text("전 조건을 마쳤습니다.")
                OutlinedButton(onClick = { model.export() }, enabled = !model.busy) { Text("다시 내보내기") }
            }
        }
        val active = model.trial?.state?.collectAsState()?.value?.phase == RecordedPhase.ACTIVE
        if (!active && model.notice.isNotBlank()) Text(model.notice)
        if (!model.busy && model.screen != StudyScreen.ADMIN &&
            model.screen != StudyScreen.PHONE && model.screen != StudyScreen.WATCH) {
            TextButton(onClick = model::showAdmin) { Text("관리자 화면") }
        }
    }

    @Composable
    private fun PhoneTrial(trial: RecordedTrial) {
        val state by trial.state.collectAsState()
        LaunchedEffect(trial) {
            while (true) {
                trial.tick()
                delay(25)
            }
        }
        when (state.phase) {
            RecordedPhase.READY -> Button(onClick = trial::ready) { Text("준비") }
            RecordedPhase.COUNTDOWN -> Text("시작까지 ${state.countdownSeconds}초")
            RecordedPhase.ACTIVE -> {
                Text("목표 ${trial.config.targetVolume}% · 현재 ${state.value}%")
                if (trial.config.condition == ConditionId.C2) model.slider.Content(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { state.value / 100f }, modifier = Modifier.fillMaxWidth())
                Button(onClick = trial::complete) { Text("완료") }
                TextButton(onClick = model::abort) { Text("관리자 긴급 중단") }
            }
            RecordedPhase.SAVING -> Text("기록 저장 중입니다.")
            RecordedPhase.RESULT -> {
                Text(when (state.outcome) {
                    com.hci.teamp.shared.model.EndStatus.COMPLETED -> "시행 종료"
                    com.hci.teamp.shared.model.EndStatus.TIMEOUT -> "시간 초과"
                    else -> "중단된 시행"
                })
                if (trial.config.phase == TrialPhase.PRACTICE) {
                    Text("목표 ${trial.config.targetVolume}% · 최종 ${state.value}% · " +
                        if (trial.config.targetVolume == state.value) "일치" else "불일치")
                }
                Button(onClick = model::next, enabled = !model.busy) { Text("계속") }
            }
            RecordedPhase.ERROR -> {
                Text(state.error ?: "저장 오류")
                Text("앱을 다시 열어 관리자 화면에서 기록을 확인해 주세요.")
            }
        }
    }
}
