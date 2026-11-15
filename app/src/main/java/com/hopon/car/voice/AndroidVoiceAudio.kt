package com.hopon.car.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/** Shared assistant attributes for focus, cues and Pleos synthesis; never changes system volume. */
internal fun voiceAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

fun vehicleVoiceOutput(context: Context): VoiceOutput =
    FocusedVoiceOutput(PleosVoiceOutput(context), AndroidVoiceAudio(context))

class AndroidVoiceAudio(context: Context) : VoiceAudio {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var generation = 0
    private var track: AudioTrack? = null
    private var cueFinished: Runnable? = null
    private var cueToken = 0

    override fun acquire(listening: Boolean, interrupted: () -> Unit): Boolean {
        release()
        // Do not compete with an ongoing call, communication session or ringtone.
        if (manager.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) return false
        val token = generation
        val focus = AudioFocusRequest.Builder(if (listening) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(voiceAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (token == generation && request != null && change in setOf(AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                    // The owner stops capture/synthesis first, then releases this lease.
                    interrupted()
                }
            }, main).build()
        request = focus
        val granted = runCatching { manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        if (!granted) release()
        return granted
    }

    override fun cue(start: Boolean, completed: () -> Unit) {
        stopCue()
        if (request == null) { completed(); return }
        val token = cueToken
        val samples = cueSamples(start)
        fun finish() {
            if (token != cueToken) return
            stopCue(); completed()
        }
        try {
            val player = AudioTrack.Builder().setAudioAttributes(voiceAudioAttributes())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            track = player
            check(player.write(samples, 0, samples.size) == samples.size)
            check(player.state == AudioTrack.STATE_INITIALIZED)
            player.setVolume(0.3f)
            // Completion follows playback, with a short tail before opening the microphone.
            val completion = Runnable { finish() }; cueFinished = completion
            player.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    if (token != cueToken) return
                    main.removeCallbacks(completion); main.postDelayed(completion, 60)
                }
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            }, main)
            player.notificationMarkerPosition = samples.size
            player.play()
            main.postDelayed(completion, 400) // bounded fallback if the device omits its marker callback
        } catch (_: Exception) {
            // A missing cue must not leave the microphone permanently waiting; the screen still shows readiness.
            finish()
        }
    }
    private fun stopCue() {
        cueToken++; cueFinished?.let { main.removeCallbacks(it) }; cueFinished = null
        val old = track; track = null
        runCatching { old?.stop() }; runCatching { old?.release() }
    }
    override fun release() {
        generation++; stopCue()
        val old = request; request = null
        if (old != null) runCatching { manager.abandonAudioFocusRequest(old) }
    }
    private fun cueSamples(start: Boolean): ShortArray {
        val rate = 48_000; val count = rate * 140 / 1000
        return ShortArray(count) { i ->
            val half = count / 2; val local = i % half
            val frequency = if ((i < half) == start) 660.0 else 880.0
            val envelope = minOf(1.0, local / 480.0, (half - 1 - local) / 480.0).coerceAtLeast(0.0)
            (sin(2 * PI * frequency * local / rate) * envelope * 12_000).toInt().toShort()
        }
    }
}
