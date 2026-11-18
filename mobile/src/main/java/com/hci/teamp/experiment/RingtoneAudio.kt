package com.hci.teamp.experiment

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.hci.teamp.shared.R
import com.hci.teamp.shared.experiment.VirtualVolume

internal class RingtoneAudio private constructor(private val player: MediaPlayer) : ExperimentAudio {
    override fun start(value: VirtualVolume) {
        setVolume(value)
        player.start()
    }

    override fun setVolume(value: VirtualVolume) {
        val gain = value.percent / 100f
        player.setVolume(gain, gain)
    }

    override fun stop() {
        player.pause()
        player.seekTo(0)
    }

    fun release() = player.release()

    companion object {
        // MediaPlayer.create prepares the bundled asset before a trial can be started.
        fun prepare(context: Context): RingtoneAudio? {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            return MediaPlayer.create(
                context, R.raw.experiment_ringtone, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE,
            )?.let { player ->
                player.isLooping = true
                RingtoneAudio(player)
            }
        }
    }
}
