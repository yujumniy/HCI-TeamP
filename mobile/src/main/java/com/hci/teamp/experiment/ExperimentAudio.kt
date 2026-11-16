package com.hci.teamp.experiment

import com.hci.teamp.shared.experiment.VirtualVolume

interface ExperimentAudio {
    fun start(value: VirtualVolume)
    fun setVolume(value: VirtualVolume)
    fun stop()
}

class ExperimentAudioStub : ExperimentAudio {
    // TODO(team 4): verify the v1 ringtone asset, playback rules and per-player gain on hardware.
    override fun start(value: VirtualVolume): Unit = error("Experiment audio is not configured")
    override fun setVolume(value: VirtualVolume): Unit = error("Experiment audio is not configured")
    override fun stop() = Unit
}
