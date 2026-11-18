package com.hci.teamp.experiment.input.check

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.hci.teamp.experiment.input.BezelConfig
import com.hci.teamp.experiment.input.WatchRawInput
import com.hci.teamp.experiment.input.WatchTouchBezel
import com.hci.teamp.experiment.input.WatchVolumeSlider
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
import com.hci.teamp.shared.experiment.VirtualVolume
import kotlinx.coroutines.delay

/**
 * Phase 1 T2: C3 터치 베젤 · C4 슬라이더 검증 화면 (명세 3장, 17장).
 *
 * 준비 → 3초 카운트다운 → 조작 → 완료(또는 30초 TIMEOUT) → 결과 확인 → 성공/실패 판정을 반복합니다.
 * 실험 본 흐름(참가자 관리, 저장, 동기화)은 다루지 않습니다.
 *
 * 베젤 원본 확인을 위해 화면에 오는 회전 MotionEvent(소스, 장치 이름, AXIS_SCROLL)와
 * C3 중 화면 터치(베젤을 문지를 때 화면 터치도 같이 들어오는지)를 그대로 기록합니다.
 */
class WatchInputCheckActivity : ComponentActivity() {

    private enum class Phase { READY, COUNTDOWN, ACTIVE, RESULT }
    private enum class Screen { TRIAL, MENU }

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val log = WatchInputCheckLog()
    private val bezel = WatchTouchBezel(clock)
    private val slider = WatchVolumeSlider(clock)

    private var screen by mutableStateOf(Screen.TRIAL)
    private var condition by mutableStateOf(Condition.C3)
    private var itemIndex by mutableIntStateOf(0)
    private var taskIndex by mutableIntStateOf(2) // T01부터
    private var phase by mutableStateOf(Phase.READY)
    private var countdown by mutableIntStateOf(3)
    private var bezelConfig by mutableStateOf(BezelConfig())

    private var attempt: Attempt? by mutableStateOf(null)
    private var controller: TrialController? = null
    private var result by mutableStateOf<String?>(null)

    private var valueChanges = 0
    private var rotaryEvents = 0
    private var gestures = 0

    /** 검증 항목별 (성공, 실패) */
    private val tally = mutableStateMapOf<String, Pair<Int, Int>>()
    private val attemptNo = mutableMapOf<String, Int>()

    private val input: ExperimentInput get() = if (condition == Condition.C3) bezel else slider
    private val itemList: List<String> get() = if (condition == Condition.C3) C3_ITEMS else C4_ITEMS
    private val checkItem: String get() = itemList[itemIndex % itemList.size]
    private val task: Task get() = CHECK_TASKS[taskIndex % CHECK_TASKS.size]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val onRaw: (WatchRawInput) -> Unit = { r ->
            log.raw(r)
            if (r.attemptId.isNotEmpty() && r.attemptId == attempt?.id) {
                if (r.kind == WatchRawInput.Kind.ROTARY) rotaryEvents++
                if (r.kind == WatchRawInput.Kind.GESTURE_START) gestures++
            }
        }
        bezel.rawListener = onRaw
        slider.rawListener = onRaw
        bezel.stepper.config = bezelConfig

        setContent { MaterialTheme { Root() } }
    }

    // ───────── 원본 입력 기록 (검증용) ─────────

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            val rotary = ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            log.note(
                attempt?.id ?: "", "MOTION_RAW", clock(), null, "system",
                "rotary_source=$rotary;source=0x${Integer.toHexString(ev.source)};" +
                    "device=${ev.device?.name ?: "?"};axis_scroll=${ev.getAxisValue(MotionEvent.AXIS_SCROLL)};" +
                    "event_time=${ev.eventTime}",
            )
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (condition == Condition.C3 && phase == Phase.ACTIVE &&
            (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP)
        ) {
            val kind = if (ev.actionMasked == MotionEvent.ACTION_DOWN) "SCREEN_TOUCH_DOWN" else "SCREEN_TOUCH_UP"
            log.note(attempt?.id ?: "", kind, clock(), null, "system", "x=%.0f;y=%.0f".format(ev.x, ev.y))
        }
        return super.dispatchTouchEvent(ev)
    }

    // ───────── 시행 진행 ─────────

    private fun prepare() {
        val t = task
        val key = "${condition.name}-${t.id}"
        val n = (attemptNo[key] ?: 0) + 1
        attemptNo[key] = n
        val a = Attempt("CHECK-$key-A%02d".format(n), CHECK_PARTICIPANT, condition, t)
        attempt = a
        controller = TrialController(a, ExperimentDevice.WEAR, TIMEOUT_MS, clock).also { it.startCountdown() }
        valueChanges = 0
        rotaryEvents = 0
        gestures = 0
        result = null
        bezel.preview(null)
        slider.preview(null)
        log.note(a.id, "READY", clock(), null, condition.name, "item=$checkItem;${bezelConfig.describe()}")
        countdown = 3
        phase = Phase.COUNTDOWN
    }

    private fun activate(): Boolean {
        val a = attempt ?: return false
        val c = controller ?: return false
        if (!c.activate()) return false
        input.start(a) { e: InputEvent ->
            val ok = c.record(e)
            log.value(e, if (condition == Condition.C3) "C3_BEZEL" else "C4_SLIDER", ok)
            if (ok) valueChanges++
        }
        log.note(a.id, "START", clock(), a.task.start.percent, condition.name, "target=${a.task.target.percent}")
        phase = Phase.ACTIVE
        return true
    }

    private fun finish(outcome: Outcome) {
        val a = attempt ?: return
        val c = controller ?: return
        val record = c.finish(outcome) ?: return
        input.stop()
        val r = record.result
        log.note(a.id, r.outcome.name, clock(), r.finalValue.percent, condition.name, "duration_ms=${r.durationMs}")
        log.trial(
            WatchInputCheckLog.TrialRow(
                attemptId = a.id, condition = condition.name, checkItem = checkItem, taskId = a.task.id,
                start = a.task.start.percent, target = a.task.target.percent, outcome = r.outcome.name,
                durationMs = r.durationMs, finalValue = r.finalValue.percent, valueChanges = record.events.size,
                rotaryEvents = rotaryEvents, gestures = gestures, trackTapBlocked = slider.trackTapBlocked,
                judged = "", bezelConfig = if (condition == Condition.C3) bezelConfig.describe() else "",
            ),
        )
        val extra = if (condition == Condition.C3) "회전 ${rotaryEvents}개 · 동작 ${gestures}회" else "트랙 탭 차단 ${slider.trackTapBlocked}회"
        result = "${r.outcome.name} · %.2f초\n목표 ${a.task.target.percent} → 최종 ${r.finalValue.percent}\n값 변경 ${record.events.size}회 · $extra"
            .format(r.durationMs / 1000.0)
        phase = Phase.RESULT
    }

    private fun judge(success: Boolean) {
        val a = attempt ?: return
        val (s, f) = tally[checkItem] ?: (0 to 0)
        tally[checkItem] = if (success) (s + 1) to f else s to (f + 1)
        log.judgeLast(a.id, if (success) "PASS" else "FAIL")
        log.note(a.id, "JUDGE", clock(), null, condition.name, "item=$checkItem;${if (success) "PASS" else "FAIL"}")
        attempt = null
        controller = null
        phase = Phase.READY
    }

    private fun export() {
        val msg = try {
            "저장됨: " + log.export(this).joinToString(", ")
        } catch (e: Exception) {
            "저장 실패: ${e.message}"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ───────── 화면 ─────────

    @Composable
    private fun Root() {
        BackHandler(enabled = screen == Screen.MENU || phase == Phase.ACTIVE || phase == Phase.COUNTDOWN) {
            when {
                screen == Screen.MENU -> screen = Screen.TRIAL
                else -> log.note(attempt?.id ?: "", "BACK_KEY", clock(), null, condition.name, "ignored_during_trial")
            }
        }
        when (screen) {
            Screen.MENU -> Menu()
            Screen.TRIAL -> if (condition == Condition.C3) {
                bezel.Content(Modifier.fillMaxSize()) { TrialScreen() }
            } else {
                Box(Modifier.fillMaxSize()) { TrialScreen() }
            }
        }
    }

    @Composable
    private fun TrialScreen() {
        if (phase == Phase.COUNTDOWN) {
            LaunchedEffect(attempt?.id) {
                for (n in 3 downTo 1) {
                    countdown = n
                    delay(COUNTDOWN_MS / 3)
                }
                while (!activate()) delay(10)
            }
        }
        if (phase == Phase.ACTIVE) {
            LaunchedEffect(attempt?.id) {
                delay(TIMEOUT_MS + 50)
                finish(Outcome.TIMEOUT)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                Phase.READY -> {
                    val (s, f) = tally[checkItem] ?: (0 to 0)
                    Text(checkItem, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("과제 ${task.id} · 성공 $s / 실패 $f", fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Chip(
                        onClick = ::prepare,
                        label = { Text("준비", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Chip(
                        onClick = { screen = Screen.MENU },
                        label = { Text("메뉴", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                }

                // 명세 5.2: 카운트다운 중에는 시작값·목표값을 보여주지 않습니다.
                Phase.COUNTDOWN -> Text("$countdown", fontSize = 56.sp, fontWeight = FontWeight.Bold)

                Phase.ACTIVE -> {
                    val target = attempt?.task?.target?.percent ?: 0
                    val now = (if (condition == Condition.C3) bezel.value else slider.value)?.percent
                    Text("목표 $target", fontSize = 16.sp, color = MaterialTheme.colors.primary)
                    Text("${now ?: "-"}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    if (condition == Condition.C4) {
                        slider.Content(Modifier.fillMaxWidth())
                    } else {
                        Text(bezel.lastRotary ?: "베젤을 돌리세요", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Chip(
                        onClick = { finish(Outcome.COMPLETED) },
                        label = { Text("완료", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                }

                Phase.RESULT -> {
                    Text(result ?: "", fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("$checkItem 판정", fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { judge(true) }, colors = ButtonDefaults.primaryButtonColors()) { Text("성공") }
                        Button(onClick = { judge(false) }, colors = ButtonDefaults.secondaryButtonColors()) { Text("실패") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Menu() {
        ScalingLazyColumn(Modifier.fillMaxSize()) {
            item { ListHeader { Text("C3·C4 입력 검증") } }
            item {
                MenuChip("조건", if (condition == Condition.C3) "C3 터치 베젤" else "C4 슬라이더") {
                    condition = if (condition == Condition.C3) Condition.C4 else Condition.C3
                    itemIndex = 0
                }
            }
            item { MenuChip("검증 항목", checkItem) { itemIndex = (itemIndex + 1) % itemList.size } }
            item {
                MenuChip("과제", "${task.id}  ${task.start.percent}→${task.target.percent}") {
                    taskIndex = (taskIndex + 1) % CHECK_TASKS.size
                }
            }
            if (condition == Condition.C3) {
                item { ListHeader { Text("베젤 설정 (파일럿 확정 전)") } }
                item {
                    MenuChip("변환 방식", if (bezelConfig.mode == BezelConfig.Mode.PER_EVENT) "이벤트 1개 = 5" else "회전량 누적") {
                        setBezel(
                            bezelConfig.copy(
                                mode = if (bezelConfig.mode == BezelConfig.Mode.PER_EVENT) BezelConfig.Mode.ACCUMULATE else BezelConfig.Mode.PER_EVENT,
                            ),
                        )
                    }
                }
                if (bezelConfig.mode == BezelConfig.Mode.ACCUMULATE) {
                    item {
                        MenuChip("1단계 회전량", "${bezelConfig.pixelsPerStep.toInt()} px") {
                            setBezel(bezelConfig.copy(pixelsPerStep = next(PX_OPTIONS, bezelConfig.pixelsPerStep)))
                        }
                    }
                }
                item {
                    MenuChip("재접촉 기준", "${bezelConfig.gestureGapMs} ms") {
                        setBezel(bezelConfig.copy(gestureGapMs = next(GAP_OPTIONS, bezelConfig.gestureGapMs)))
                    }
                }
                item {
                    MenuChip("방향", if (bezelConfig.clockwiseIsPositive) "원시값 + = 시계" else "원시값 − = 시계 (반전)") {
                        setBezel(bezelConfig.copy(clockwiseIsPositive = !bezelConfig.clockwiseIsPositive))
                    }
                }
            }
            item { ListHeader { Text("집계 (각 20회)") } }
            (C3_ITEMS + C4_ITEMS).forEach { name ->
                item {
                    val (s, f) = tally[name] ?: (0 to 0)
                    Text("$name  성공 $s / 실패 $f", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            item { MenuChip("CSV 내보내기", "시행 ${log.trialCount}개 · 기록 ${log.size}줄") { export() } }
            item { MenuChip("돌아가기", "") { screen = Screen.TRIAL } }
        }
    }

    @Composable
    private fun MenuChip(label: String, value: String, onClick: () -> Unit) {
        Chip(
            onClick = onClick,
            label = { Text(label) },
            secondaryLabel = { if (value.isNotEmpty()) Text(value) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun setBezel(c: BezelConfig) {
        bezelConfig = c
        bezel.stepper.config = c
        log.note("", "BEZEL_CONFIG", clock(), null, "C3_BEZEL", c.describe())
    }

    private fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

    companion object {
        /** 명세 8.2: 30초 안전 제한 */
        const val TIMEOUT_MS = 30_000L

        private val CHECK_PARTICIPANT = ParticipantId("P999")

        private val C3_ITEMS = listOf("C3-시계방향", "C3-반시계방향", "C3-재접촉", "C3-감도")
        private val C4_ITEMS = listOf("C4-드래그정확성", "C4-트랙탭차단")

        private val PX_OPTIONS = listOf(16f, 24f, 32f, 48f, 64f, 96f)
        private val GAP_OPTIONS = listOf(150L, 300L, 500L, 800L)

        /** 명세 6장 과제 (검증용 사본. 실험 본 과제 배정은 팀원 4 담당) */
        private fun t(id: String, s: Int, e: Int) = Task(id, VirtualVolume(s), VirtualVolume(e))
        private val CHECK_TASKS = listOf(
            t("PR01", 25, 65), t("PR02", 75, 35),
            t("T01", 10, 80), t("T02", 20, 75), t("T03", 40, 55), t("T04", 50, 70),
            t("T05", 90, 20), t("T06", 80, 25), t("T07", 60, 45), t("T08", 70, 50),
        )
    }
}
