package com.hci.teamp.experiment.input.check

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hci.teamp.experiment.input.PhoneVolumeKeys
import com.hci.teamp.experiment.input.PhoneVolumeSlider
import com.hci.teamp.shared.experiment.Attempt
import com.hci.teamp.shared.experiment.COUNTDOWN_MS
import com.hci.teamp.shared.experiment.Condition
import com.hci.teamp.shared.experiment.ExperimentDevice
import com.hci.teamp.shared.experiment.ExperimentInput
import com.hci.teamp.shared.experiment.InputEvent
import com.hci.teamp.shared.experiment.Outcome
import com.hci.teamp.shared.experiment.ParticipantId
import com.hci.teamp.shared.experiment.Task
import com.hci.teamp.shared.experiment.TrialController
import com.hci.teamp.shared.experiment.TrialPhase
import com.hci.teamp.shared.experiment.TrialRecord
import com.hci.teamp.shared.experiment.VirtualVolume

/**
 * Phase 1 · C1 물리 버튼 / C2 슬라이더 입력 검증 화면 (팀원 1).
 * 시행 진행은 공통 TrialController(준비 → 3초 카운트다운 → 조작 → 종료)를 그대로 사용합니다.
 * 참가자용 최종 시행 화면은 팀원 4 담당이며, 이 화면은 검증용입니다.
 */
class InputCheckActivity : ComponentActivity() {

    private val log = InputCheckLog()
    private val keys = PhoneVolumeKeys()
    private val slider = PhoneVolumeSlider()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    private var condition by mutableStateOf(Condition.C1)
    private var taskIndex by mutableIntStateOf(0)
    private var phase by mutableStateOf(TrialPhase.READY)
    private var countdown by mutableIntStateOf(0)
    private var current by mutableStateOf<VirtualVolume?>(null)
    private var lastInput by mutableStateOf("-")
    private var lastRecord by mutableStateOf<TrialRecord?>(null)
    private var attemptNo by mutableIntStateOf(0)
    private var systemVolume by mutableStateOf("" to true)
    private var exportMessage by mutableStateOf("")
    private val passes = mutableStateMapOf<String, Int>()
    private val fails = mutableStateMapOf<String, Int>()

    private var controller: TrialController? = null
    private var attempt: Attempt? = null
    private var baseline = 0 to 0
    private var countdownStartedAt = 0L

    private val task get() = CHECK_TASKS[taskIndex]
    private val input: ExperimentInput get() = if (condition == Condition.C1) keys else slider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AudioManager::class.java)
        baseline = readSystemVolume()
        checkSystemVolume("baseline")
        keys.rawListener = { log.raw(it); lastInput = "${it.source} ${it.kind}" }
        slider.rawListener = { log.raw(it); lastInput = "${it.source} ${it.kind}" }
        slider.preview(task.start)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    // C1이 아닐 때도 볼륨 키를 소비해 시스템 음량을 보호합니다 (keys가 활성 상태가 아니면 이벤트 없음).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keys.onKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keys.onKeyEvent(event)) {
            checkSystemVolume("after_key_up")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        keys.cancelHold("pause")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) keys.cancelHold("focus_lost")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        input.stop()
        super.onDestroy()
    }

    private fun readSystemVolume() =
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamVolume(AudioManager.STREAM_RING)

    private fun checkSystemVolume(reason: String) {
        val now = readSystemVolume()
        val unchanged = now == baseline
        systemVolume = "미디어 ${now.first} · 벨 ${now.second}" to unchanged
        log.note(attempt?.id ?: "", "SYSTEM_VOLUME_CHECK", SystemClock.elapsedRealtime(), null,
            "$reason;music=${now.first};ring=${now.second};unchanged=$unchanged")
    }

    // ---------- 시행 진행 ----------

    private fun startTrial() {
        if (phase == TrialPhase.COUNTDOWN || phase == TrialPhase.ACTIVE) return
        attemptNo++
        val a = Attempt(
            id = "CHECK-${condition.name}-${task.id}-A%02d".format(attemptNo),
            participant = ParticipantId("P999"),
            condition = condition,
            task = task,
        )
        val c = TrialController(a, ExperimentDevice.PHONE, TIMEOUT_MS) { SystemClock.elapsedRealtime() }
        attempt = a
        controller = c
        lastRecord = null
        current = null // 카운트다운 중에는 시작값·목표값 숨김 (명세 5.2)
        slider.preview(null)
        c.startCountdown()
        phase = c.phase
        log.note(a.id, "COUNTDOWN", SystemClock.elapsedRealtime(), null, "task=${task.id}")
        countdownStartedAt = SystemClock.elapsedRealtime()
        countdown = 3
        handler.postDelayed(::tick, 1000)
    }

    private fun tick() {
        val c = controller ?: return
        if (c.phase != TrialPhase.COUNTDOWN) return
        if (c.activate()) {
            val a = attempt!!
            phase = c.phase
            current = a.task.start
            log.note(a.id, "START", SystemClock.elapsedRealtime(), a.task.start.percent,
                "start=${a.task.start.percent};target=${a.task.target.percent}")
            input.start(a, ::onInput)
            handler.postDelayed({ finish(Outcome.TIMEOUT) }, TIMEOUT_MS)
            return
        }
        val remaining = COUNTDOWN_MS - (SystemClock.elapsedRealtime() - countdownStartedAt)
        countdown = ((remaining + 999) / 1000).toInt().coerceIn(1, 3)
        handler.postDelayed(::tick, remaining.coerceIn(10, 1000))
    }

    private fun onInput(e: InputEvent) {
        val accepted = controller?.record(e) == true
        log.value(e, if (condition == Condition.C1) "C1_BUTTON" else "C2_SLIDER", accepted)
        if (accepted) current = e.current
        lastInput = "${e.previous.percent} → ${e.current.percent}" + if (accepted) "" else " (거부됨)"
    }

    private fun finish(outcome: Outcome) {
        val c = controller ?: return
        if (c.phase == TrialPhase.COUNTDOWN) {
            handler.removeCallbacksAndMessages(null)
            log.note(attempt?.id ?: "", "CANCELLED_IN_COUNTDOWN", SystemClock.elapsedRealtime(), null, "")
            phase = TrialPhase.READY
            controller = null
            current = task.start
            slider.preview(task.start)
            return
        }
        val record = c.finish(outcome) ?: return
        handler.removeCallbacksAndMessages(null)
        input.stop()
        phase = c.phase
        lastRecord = record
        log.note(record.result.attempt.id, record.result.outcome.name, SystemClock.elapsedRealtime(),
            record.result.finalValue.percent, "duration_ms=${record.result.durationMs};events=${record.events.size}")
    }

    private fun selectCondition(c: Condition) {
        if (phase == TrialPhase.COUNTDOWN || phase == TrialPhase.ACTIVE) return
        condition = c
        phase = TrialPhase.READY
        lastRecord = null
        current = task.start
        slider.preview(task.start)
    }

    private fun nextTask() {
        if (phase == TrialPhase.COUNTDOWN || phase == TrialPhase.ACTIVE) return
        taskIndex = (taskIndex + 1) % CHECK_TASKS.size
        phase = TrialPhase.READY
        lastRecord = null
        current = task.start
        slider.preview(task.start)
    }

    private fun record(test: String, ok: Boolean) {
        if (ok) passes[test] = (passes[test] ?: 0) + 1 else fails[test] = (fails[test] ?: 0) + 1
        log.note(attempt?.id ?: "", "CHECK_RESULT", SystemClock.elapsedRealtime(), null,
            "test=$test;result=${if (ok) "PASS" else "FAIL"};pass=${passes[test] ?: 0};fail=${fails[test] ?: 0}")
    }

    // ---------- 화면 ----------

    @Composable
    private fun Screen() {
        val busy = phase == TrialPhase.COUNTDOWN || phase == TrialPhase.ACTIVE
        Column(Modifier.fillMaxSize().background(BG).safeDrawingPadding()) {
            TabRow(selectedTabIndex = if (condition == Condition.C1) 0 else 1, containerColor = PANEL) {
                Tab(condition == Condition.C1, onClick = { selectCondition(Condition.C1) }, text = { Text("C1 물리 버튼") })
                Tab(condition == Condition.C2, onClick = { selectCondition(Condition.C2) }, text = { Text("C2 슬라이더") })
            }
            TrialArea(Modifier.weight(1f).fillMaxWidth())
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.45f).background(PANEL)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = ::nextTask, enabled = !busy) {
                        Text("과제 ${task.id}  ${task.start.percent}→${task.target.percent}")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!busy) {
                        Button(onClick = ::startTrial) { Text("준비") }
                    } else {
                        Button(
                            onClick = { finish(Outcome.INVALID) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        ) { Text("중단(무효)") }
                    }
                }
                Info("상태 ${phase.name} · 마지막 입력 $lastInput")
                lastRecord?.let { r ->
                    Info(
                        "결과 ${r.result.outcome} · %.2f초 · 최종 ${r.result.finalValue.percent} (목표 ${r.result.attempt.task.target.percent}) · 값 변경 ${r.events.size}회"
                            .format(r.result.durationMs / 1000f),
                    )
                }
                val (sys, unchanged) = systemVolume
                Info("시스템 음량 $sys " + if (unchanged) "(변화 없음 ✓)" else "(바뀜 ✗)", if (unchanged) OK else BAD)
                if (condition == Condition.C1) {
                    Stepper("길게 누르기 시작 지연", keys.initialDelayMs, 50) { keys.initialDelayMs = it }
                    Stepper("길게 누르기 반복 간격", keys.intervalMs, 25) { keys.intervalMs = it }
                    CheckRow("C1-짧게누르기")
                    CheckRow("C1-길게누르기")
                    CheckRow("C1-시스템음량유지")
                } else {
                    Info("트랙 탭 차단 ${slider.trackTapBlocked}회 (손잡이 밖을 눌러도 값이 그대로여야 함)")
                    CheckRow("C2-드래그정확성")
                    CheckRow("C2-트랙탭차단")
                }
                TextButton(onClick = {
                    exportMessage = runCatching { "저장됨: " + log.export(this@InputCheckActivity) }
                        .getOrElse { "저장 실패: ${it.message}" }
                }) { Text("CSV 내보내기 (${log.size}행)") }
                if (exportMessage.isNotEmpty()) Info(exportMessage)
            }
        }
    }

    /** 시안 구조: 상단 목표/현재 · 중앙 막대(C1) 또는 슬라이더(C2) · 하단 완료 */
    @Composable
    private fun TrialArea(modifier: Modifier) {
        val active = phase == TrialPhase.ACTIVE
        val shown = if (phase == TrialPhase.COUNTDOWN) null else current
        val target = if (phase == TrialPhase.COUNTDOWN) null else task.target.percent
        Column(modifier.padding(horizontal = 28.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            if (phase == TrialPhase.COUNTDOWN) {
                Text("$countdown", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Number("목표", target)
                    Number("현재", shown?.percent)
                }
            }
            Spacer(Modifier.weight(1f))
            if (condition == Condition.C1) {
                ReadOnlyBar(shown?.percent ?: 0)
                Spacer(Modifier.height(20.dp))
                Text("측면 버튼으로 조절", color = LABEL, fontSize = 16.sp)
            } else {
                slider.Content(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.weight(1.5f))
            Button(
                onClick = { finish(Outcome.COMPLETED) },
                enabled = active,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8ECF4), contentColor = BG),
            ) { Text("완료", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.weight(0.3f))
        }
    }

    @Composable
    private fun Number(label: String, value: Int?) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = LABEL, fontSize = 18.sp)
            Text(value?.toString() ?: "–", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun ReadOnlyBar(value: Int) {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color(0xFF334155), cornerRadius = r)
            val w = size.width * value / 100f
            if (w > 0) drawRoundRect(Color(0xFF55B8BE), size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
        }
    }

    @Composable
    private fun CheckRow(test: String) {
        val p = passes[test] ?: 0
        val f = fails[test] ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(test, color = Color.White, fontSize = 14.sp)
                Text("성공 $p · 실패 $f (${p + f}/20)", color = LABEL, fontSize = 12.sp)
            }
            Button(onClick = { record(test, true) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("성공") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { record(test, false) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2A2A))) { Text("실패") }
        }
    }

    @Composable
    private fun Stepper(label: String, value: Long, step: Long, onChange: (Long) -> Unit) {
        var v by remember { mutableStateOf(value) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label ${v}ms", color = LABEL, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { v = (v - step).coerceAtLeast(step); onChange(v) }) { Text("−") }
            TextButton(onClick = { v += step; onChange(v) }) { Text("+") }
        }
    }

    @Composable
    private fun Info(text: String, color: Color = LABEL) = Text(text, color = color, fontSize = 13.sp)

    companion object {
        /** 공통 TrialController에 넘기는 안전 제한 시간 (명세 8.2) */
        const val TIMEOUT_MS = 30_000L

        private val BG = Color(0xFF1A1D25)
        private val PANEL = Color(0xFF11131A)
        private val LABEL = Color(0xFFB8C0CC)
        private val OK = Color(0xFF9BE7A5)
        private val BAD = Color(0xFFFF8A80)

        /**
         * 검증용 과제 (명세 6장 숫자). 실제 과제 배정은 팀원 4의 ExperimentSchedule이 담당합니다.
         */
        val CHECK_TASKS: List<Task> = listOf(
            "PR01" to (25 to 65), "PR02" to (75 to 35),
            "T01" to (10 to 80), "T02" to (20 to 75), "T03" to (40 to 55), "T04" to (50 to 70),
            "T05" to (90 to 20), "T06" to (80 to 25), "T07" to (60 to 45), "T08" to (70 to 50),
        ).map { (id, v) -> Task(id, VirtualVolume(v.first), VirtualVolume(v.second)) }
    }
}
