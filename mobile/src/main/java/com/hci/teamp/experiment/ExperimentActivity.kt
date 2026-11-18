package com.hci.teamp.experiment

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.Outcome
import com.hci.teamp.shared.experiment.ParticipantId
import kotlinx.coroutines.delay

class ExperimentActivity : ComponentActivity() {
    private var session by mutableStateOf<RehearsalSession?>(null)
    private var participantText by mutableStateOf("")
    private var invalidId by mutableStateOf(false)
    private var refresh by mutableIntStateOf(0)
    private var audio: RingtoneAudio? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = RingtoneAudio.prepare(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val current = session
                        if (current == null) ParticipantForm() else TrialScreen(current, refresh)
                    }
                }
            }
        }
    }

    override fun onStop() {
        session?.interrupt()
        refresh++
        super.onStop()
    }

    override fun onDestroy() {
        audio?.release()
        super.onDestroy()
    }

    @Composable
    private fun ParticipantForm() {
        Text("실험 흐름 리허설", style = MaterialTheme.typography.headlineSmall)
        Text("v1 과제로 폰 화면을 점검합니다. C1·C2 모두 화면 버튼을 사용하며, 결과는 실험 데이터로 저장하지 않습니다.")
        if (audio == null) {
            Text("벨소리를 준비하지 못했습니다. 화면을 다시 열어 주세요.")
            return
        }
        OutlinedTextField(
            value = participantText,
            onValueChange = {
                participantText = it
                invalidId = false
            },
            label = { Text("참가자 ID (P001)") },
            isError = invalidId,
            supportingText = { if (invalidId) Text("P001 형식의 참가자 ID를 입력하세요.") },
            singleLine = true,
        )
        Button(onClick = ::startRehearsal) { Text("조건 배정") }
    }

    private fun startRehearsal() {
        val player = audio ?: return
        val id = runCatching { ParticipantId(participantText.trim()) }.getOrNull()
        if (id == null || id.value.drop(1).toIntOrNull() == null) {
            invalidId = true
            return
        }
        session = RehearsalSession(id, player, SystemClock::elapsedRealtime)
    }

    @Composable
    private fun TrialScreen(current: RehearsalSession, revision: Int) {
        val phase = remember(current, revision) { current.phase }
        LaunchedEffect(current, phase) {
            val ticking = phase == RehearsalPhase.COUNTDOWN || phase == RehearsalPhase.ACTIVE ||
                phase == RehearsalPhase.REST
            while (ticking && current.phase == phase) {
                delay(50)
                current.tick()
                refresh++
            }
        }
        when (phase) {
            RehearsalPhase.INSTRUCTIONS -> {
                val method = if (current.condition == Condition.C1) "물리 음량 버튼" else "슬라이더 손잡이"
                Text("전체 조건 순서  ${current.conditions.joinToString(" → ")}")
                Text("${current.condition} · 실제 입력 방식은 ${method}입니다.")
                Text("리허설에서는 화면의 −5와 +5 버튼으로 조절합니다.")
                NextButton(current, "연습 시작")
            }
            RehearsalPhase.READY -> {
                val label = if (current.isPractice) "연습 ${current.taskNumber}/2" else "본 시행 ${current.taskNumber}/8"
                Text("${current.condition} · $label")
                Button(onClick = {
                    current.begin()
                    refresh++
                }) { Text("준비") }
            }
            RehearsalPhase.COUNTDOWN -> Text("시작까지 ${seconds(current.remainingMs())}초")
            RehearsalPhase.ACTIVE -> {
                Text("목표 ${current.target.percent}% · 현재 ${current.value.percent}%")
                val volumeProgress = current.value.percent / 100f
                LinearProgressIndicator(
                    progress = { volumeProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { adjust(current, -5) }) { Text("−5") }
                    OutlinedButton(onClick = { adjust(current, 5) }) { Text("+5") }
                }
                Button(onClick = {
                    current.submit()
                    refresh++
                }) { Text("완료") }
            }
            RehearsalPhase.RESULT -> {
                val result = checkNotNull(current.lastRecord).result
                Text(when (result.outcome) {
                    Outcome.COMPLETED -> "시행 종료"
                    Outcome.TIMEOUT -> "시간 초과"
                    Outcome.INVALID -> "중단된 시행"
                })
                if (current.isPractice) {
                    val target = result.attempt.task.target
                    val match = if (target == result.finalValue) "일치" else "불일치"
                    Text("목표 ${target.percent}% · 최종 ${result.finalValue.percent}% · $match")
                }
                NextButton(current, if (result.outcome == Outcome.INVALID) "같은 과제 재시도" else "계속")
            }
            RehearsalPhase.MAIN_GATE -> {
                Text("연습을 마쳤습니다.")
                NextButton(current, "본 실험 시작")
            }
            RehearsalPhase.REST -> {
                val remaining = current.remainingMs()
                Text("휴식 · ${seconds(remaining)}초")
                NextButton(current, "다음 조건", enabled = remaining == 0L)
            }
            RehearsalPhase.DONE -> {
                Text("폰 리허설을 마쳤습니다.")
                Button(onClick = { session = null }) { Text("처음으로") }
            }
        }
    }

    private fun adjust(current: RehearsalSession, delta: Int) {
        current.adjust(delta)
        refresh++
    }

    @Composable
    private fun NextButton(current: RehearsalSession, label: String, enabled: Boolean = true) {
        Button(
            onClick = {
                current.next()
                refresh++
            },
            enabled = enabled,
        ) { Text(label) }
    }

    private fun seconds(ms: Long) = (ms + 999) / 1000
}
