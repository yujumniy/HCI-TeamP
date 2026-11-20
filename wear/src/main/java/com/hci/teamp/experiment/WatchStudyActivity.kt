package com.hci.teamp.experiment

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.hci.teamp.experiment.input.WatchTouchBezel
import com.hci.teamp.experiment.input.WatchVolumeSlider
import com.hci.teamp.experiment.input.WatchRawInput
import com.hci.teamp.shared.R
import com.hci.teamp.shared.experiment.*
import com.hci.teamp.shared.model.ConditionId
import com.hci.teamp.shared.model.TrialConfig
import com.hci.teamp.shared.model.TrialPhase
import com.hci.teamp.wear.sync.WearSyncManager
import kotlinx.coroutines.*

class WatchStudyViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = WearSyncManager.get(application)
    val bezel = WatchTouchBezel()
    val slider = WatchVolumeSlider()
    private val player = MediaPlayer.create(
        application, R.raw.experiment_ringtone,
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        AudioManager.AUDIO_SESSION_ID_GENERATE,
    )?.apply { isLooping = true }
    var config by mutableStateOf<TrialConfig?>(null)
        private set
    var trial by mutableStateOf<RecordedTrial?>(null)
        private set
    var notice by mutableStateOf("폰에서 시행을 전달해 주세요.")
        private set
    val pendingUploads = manager.pendingUploadCount

    init {
        notice = "폰에서 시행을 전달해 주세요."
        val raw: (WatchRawInput) -> Unit = { event ->
            trial?.raw(event.kind.name, mapOf(
                "source" to event.source, "detail" to event.detail,
                "source_elapsed_realtime_ms" to event.elapsedRealtimeMs.toString(),
            ))
            if (event.attemptId.isNotBlank()) scope.launch(Dispatchers.IO) {
                manager.repo.log("RAW_INPUT", event.attemptId, event.toString())
            }
        }
        bezel.rawListener = raw
        slider.rawListener = raw
        manager.onAppStart()
        scope.launch {
            manager.pendingConfig.collect { incoming ->
                if (incoming != null && incoming.attemptId != config?.attemptId) {
                    config = incoming
                    trial = null
                    notice = ""
                }
            }
        }
        scope.launch {
            manager.abortRequests.collect { request ->
                if (request.attemptId == config?.attemptId) {
                    trial?.interrupt(request.reason)
                }
            }
        }
    }

    fun ready() {
        val cfg = config ?: return
        if (trial != null) {
            trial?.ready()
            return
        }
        if (player == null) {
            notice = "벨소리 로딩 실패 · 앱을 다시 열어 주세요."
            return
        }
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            notice = "시스템 미디어 음량을 먼저 설정해 주세요."
            return
        }
        val sound = object : TrialAudio {
            override fun start(value: Int) { setVolume(value); player.start() }
            override fun setVolume(value: Int) {
                val gain = value / 100f
                player.setVolume(gain, gain)
            }
            override fun stop() { player.pause(); player.seekTo(0) }
        }
        val input = when (cfg.condition) {
            ConditionId.C3 -> bezel
            ConditionId.C4 -> slider
            else -> {
                notice = "워치 조건이 아닙니다."
                return
            }
        }
        trial = RecordedTrial(cfg, manager.newRecorder(cfg), input, sound, scope, SystemClock::elapsedRealtimeNanos)
        trial?.ready()
    }

    fun interrupt() { trial?.interrupt() }

    override fun onCleared() {
        interrupt()
        bezel.stop()
        slider.stop()
        player?.release()
        scope.cancel()
    }
}

class WatchStudyActivity : ComponentActivity() {
    private lateinit var model: WatchStudyViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[WatchStudyViewModel::class.java]
        setContent {
            MaterialTheme {
                val trial = model.trial
                if (model.config?.condition == ConditionId.C3) {
                    model.bezel.Content(Modifier.fillMaxSize()) { TrialContent(trial) }
                } else {
                    TrialContent(trial)
                }
            }
        }
    }

    override fun onStop() {
        model.interrupt()
        super.onStop()
    }

    @Composable
    private fun TrialContent(trial: RecordedTrial?) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            val cfg = model.config
            if (cfg == null) {
                Text(model.notice)
                return@Column
            }
            if (trial == null) {
                Text("${cfg.condition} · ${if (cfg.phase == TrialPhase.PRACTICE) "연습" else "본 시행"} ${cfg.orderIndex}")
                Chip(onClick = model::ready, label = { Text("준비") }, modifier = Modifier.fillMaxWidth())
                if (model.notice.isNotBlank()) Text(model.notice)
                return@Column
            }
            val state by trial.state.collectAsState()
            LaunchedEffect(trial) {
                while (true) {
                    trial.tick()
                    delay(25)
                }
            }
            when (state.phase) {
                RecordedPhase.READY -> Chip(onClick = model::ready, label = { Text("준비") })
                RecordedPhase.COUNTDOWN -> Text("시작까지 ${state.countdownSeconds}초")
                RecordedPhase.ACTIVE -> {
                    Text("목표 ${cfg.targetVolume}%\n현재 ${state.value}%")
                    if (cfg.condition == ConditionId.C4) model.slider.Content(Modifier.fillMaxWidth())
                    else androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        drawRect(androidx.compose.ui.graphics.Color.DarkGray)
                        drawRect(
                            androidx.compose.ui.graphics.Color(0xFF55B8BE),
                            size = androidx.compose.ui.geometry.Size(size.width * state.value / 100f, size.height),
                        )
                    }
                    Chip(onClick = trial::complete, label = { Text("완료") }, modifier = Modifier.fillMaxWidth())
                }
                RecordedPhase.SAVING -> Text("기록 저장 중")
                RecordedPhase.RESULT -> {
                    Text(when (state.outcome) {
                        com.hci.teamp.shared.model.EndStatus.COMPLETED -> "시행 종료"
                        com.hci.teamp.shared.model.EndStatus.TIMEOUT -> "시간 초과"
                        else -> "중단된 시행"
                    })
                    if (cfg.phase == TrialPhase.PRACTICE) {
                        Text("목표 ${cfg.targetVolume}%\n최종 ${state.value}%\n" +
                            if (state.value == cfg.targetVolume) "일치" else "불일치")
                    }
                    val pending by model.pendingUploads.collectAsState(initial = -1)
                    Text(if (pending == 0) "폰에서 다음 과제를 진행해 주세요." else "폰과 동기화 중")
                }
                RecordedPhase.ERROR -> Text(state.error ?: "저장 오류")
            }
        }
    }
}
