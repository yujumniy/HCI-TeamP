package com.hci.teamp

import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hci.teamp.shared.log.CsvExport
import com.hci.teamp.shared.log.EventLog
import com.hci.teamp.shared.log.EventType
import com.hci.teamp.shared.log.TestCounters
import com.hci.teamp.shared.trial.TrialController
import com.hci.teamp.shared.ui.CenterMode
import com.hci.teamp.shared.ui.SliderListener
import com.hci.teamp.shared.ui.TrialColors
import com.hci.teamp.shared.ui.TrialPanel
import com.hci.teamp.shared.ui.TrialSizes

/**
 * Phase 1 스마트폰 입력 검증 앱 (팀원 1 담당: C1 물리 버튼 · C2 슬라이더).
 * 상단: 시행 화면(명세 5.1 형태) / 하단: 검증용 정보와 기록 버튼.
 */
class MainActivity : ComponentActivity() {

    private val log = EventLog("phone")
    private val counters = TestCounters(log)
    private val c1 = TrialController(log, "C1")
    private val c2 = TrialController(log, "C2")
    private val buttons = VolumeButtonHandler(c1)

    private var tab by mutableIntStateOf(0) // 0 = C1, 1 = C2
    private var trackTapBlocked by mutableIntStateOf(0)
    private var systemVolume by mutableStateOf(SystemVolume(0, 0))
    private var exportMessage by mutableStateOf("")
    private lateinit var baselineVolume: SystemVolume
    private lateinit var audio: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AudioManager::class.java)
        baselineVolume = readSystemVolume()
        systemVolume = baselineVolume
        log.log(EventType.SYSTEM_VOLUME_CHECK, raw = "baseline;${baselineVolume.raw()}")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        buttons.enabled = tab == 0
        if (buttons.onKeyEvent(event)) {
            if (event.action == KeyEvent.ACTION_UP) checkSystemVolume("after_key_up")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        buttons.cancel("pause")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) buttons.cancel("focus_lost")
    }

    private fun readSystemVolume() = SystemVolume(
        music = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        ring = audio.getStreamVolume(AudioManager.STREAM_RING),
    )

    /** 명세 4.2: 시스템 음량이 바뀌지 않았는지 매번 확인해서 기록 */
    private fun checkSystemVolume(reason: String) {
        systemVolume = readSystemVolume()
        val unchanged = systemVolume == baselineVolume
        log.log(EventType.SYSTEM_VOLUME_CHECK, raw = "$reason;${systemVolume.raw()};unchanged=$unchanged")
    }

    private fun export() {
        exportMessage = try {
            "저장됨: " + CsvExport.saveToDownloads(this, "phase1_phone_${CsvExport.stamp()}.csv", log.toCsv())
        } catch (e: Exception) {
            "저장 실패: ${e.message}"
        }
    }

    private val sliderListener = object : SliderListener {
        override fun onDown(x: Float, value: Int) = c2.inputDown("C2_SLIDER", "x=%.1f".format(x))
        override fun onValue(value: Int, x: Float) = c2.setValue(value, "C2_SLIDER", "x=%.1f".format(x))
        override fun onUp(x: Float, value: Int) = c2.inputUp("C2_SLIDER", "x=%.1f".format(x))
        override fun onTrackTapBlocked(x: Float) {
            trackTapBlocked++
            log.log(EventType.TRACK_TAP_BLOCKED, c2.attemptId.takeIf { c2.running } ?: "", c2.volume, "C2_SLIDER", "x=%.1f".format(x))
        }
    }

    @Composable
    private fun Screen() {
        val controller = if (tab == 0) c1 else c2
        Column(
            Modifier
                .fillMaxSize()
                .background(TrialColors.Background)
                .safeDrawingPadding(),
        ) {
            TabRow(selectedTabIndex = tab, containerColor = Color(0xFF11131A)) {
                Tab(selected = tab == 0, onClick = { if (!c2.running) tab = 0 }, text = { Text("C1 물리 버튼") })
                Tab(selected = tab == 1, onClick = { if (!c1.running) tab = 1 }, text = { Text("C2 슬라이더") })
            }

            // ---- 시행 화면 (명세 5.1) ----
            TrialPanel(
                target = controller.task.target,
                current = controller.volume,
                mode = if (tab == 0) CenterMode.BAR else CenterMode.SLIDER,
                sizes = TrialSizes.Phone,
                hint = if (tab == 0) "측면 버튼으로 조절" else null,
                completeEnabled = controller.running,
                sliderListener = sliderListener,
                onComplete = { controller.complete() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // ---- 검증용 패널 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF11131A))
                    .fillMaxHeight(0.42f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { controller.nextTask() }, enabled = !controller.running) {
                        Text("과제 ${controller.task.id}  ${controller.task.start}→${controller.task.target}")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!controller.running) {
                        Button(onClick = { controller.start() }) { Text("시작") }
                    } else {
                        Button(
                            onClick = { controller.invalidate() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        ) { Text("중단(무효)") }
                    }
                }
                Info("가상 음량 ${controller.volume} · 마지막 입력 ${controller.lastInput}")
                if (controller.running) Info("시행 중: ${controller.attemptId} (30초 후 TIMEOUT)")
                controller.resultSummary().takeIf { it.isNotEmpty() }?.let { Info("결과: $it") }

                if (tab == 0) {
                    val unchanged = systemVolume == baselineVolume
                    Info(
                        "시스템 음량 미디어 ${systemVolume.music} · 벨 ${systemVolume.ring} " +
                            if (unchanged) "(변화 없음 ✓)" else "(변경됨 ✗ 시작: ${baselineVolume.music}/${baselineVolume.ring})",
                        color = if (unchanged) Color(0xFF9BE7A5) else Color(0xFFFF8A80),
                    )
                    Stepper("길게 누르기 시작 지연", buttons.initialDelayMs, 50) { buttons.initialDelayMs = it }
                    Stepper("길게 누르기 반복 간격", buttons.intervalMs, 25) { buttons.intervalMs = it }
                    TestRow("T1-짧게누르기")
                    TestRow("T1-길게누르기")
                    TestRow("T1-시스템음량유지")
                } else {
                    Info("트랙 탭 차단 횟수 $trackTapBlocked (손잡이 밖을 눌러도 값이 안 바뀌어야 함)")
                    TestRow("C2-드래그정확성")
                    TestRow("C2-트랙탭차단")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::export) { Text("CSV 내보내기 (${log.size}개 이벤트)") }
                }
                if (exportMessage.isNotEmpty()) Info(exportMessage)
            }
        }
    }

    @Composable
    private fun TestRow(test: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(test, fontSize = 14.sp, color = Color.White)
                Text(counters.summary(test), fontSize = 12.sp, color = TrialColors.Label)
            }
            Button(
                onClick = { counters.record(test, true) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            ) { Text("성공") }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { counters.record(test, false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E2A2A)),
            ) { Text("실패") }
        }
    }

    @Composable
    private fun Stepper(label: String, value: Long, step: Long, onChange: (Long) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label ${value}ms", fontSize = 13.sp, color = TrialColors.Label, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange((value - step).coerceAtLeast(step)) }) { Text("−") }
            TextButton(onClick = { onChange(value + step) }) { Text("+") }
        }
    }

    @Composable
    private fun Info(text: String, color: Color = TrialColors.Label) {
        Text(text, fontSize = 13.sp, color = color)
    }
}

data class SystemVolume(val music: Int, val ring: Int) {
    fun raw() = "music=$music;ring=$ring"
}
