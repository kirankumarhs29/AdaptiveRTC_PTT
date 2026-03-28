package com.netsense.mesh

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.netsense.mesh.AppLogger.Module

class VoiceAudioManager(context: Context) {
    companion object {
        private const val TAG = "VoiceAudioManager"
    }

    private val systemAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()

    val frameSamples: Int = VoiceTransportConfig.frameSamples
    val frameBytes: Int = VoiceTransportConfig.frameBytes

    @Volatile
    var playbackPrepared: Boolean = false
        private set

    // AudioRecord.getMinBufferSize() queries audio HAL parameters that are constant for the
    // lifetime of the process. Cache the result to avoid a redundant JNI call on every prepare.
    @Volatile
    private var cachedCaptureSupported: Boolean? = null

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var playbackStarted = false
    private var bufferedFrames = 0

    fun validateCaptureSupport(): Boolean {
        cachedCaptureSupported?.let { return it }
        val minBytes = AudioRecord.getMinBufferSize(
            VoiceTransportConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        Log.d(TAG, "validateCaptureSupport minBytes=$minBytes sampleRate=${VoiceTransportConfig.SAMPLE_RATE_HZ}")
        val supported = minBytes > 0
        cachedCaptureSupported = supported
        AppLogger.info(Module.AUDIO, "validateCaptureSupport supported=$supported minBytes=$minBytes sampleRate=${VoiceTransportConfig.SAMPLE_RATE_HZ}")
        return supported
    }

    @Synchronized
    fun preparePlayback(): Boolean {
        if (playbackPrepared && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return true
        }

        val minBytes = AudioTrack.getMinBufferSize(
            VoiceTransportConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            Log.e(TAG, "AudioTrack min buffer unavailable")
            AppLogger.error(Module.AUDIO, "AudioTrack min buffer unavailable")
            return false
        }
        Log.d(TAG, "preparePlayback minBytes=$minBytes frameBytes=$frameBytes")

        releasePlayback()
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(VoiceTransportConfig.SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBytes, frameBytes * 12),
            AudioTrack.MODE_STREAM,
            0
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            Log.e(TAG, "AudioTrack init failed")
            AppLogger.error(Module.AUDIO, "AudioTrack init failed")
            return false
        }

        audioTrack = track
        playbackPrepared = true
        playbackStarted = false
        bufferedFrames = 0
        Log.d(TAG, "playback prepared")
        AppLogger.info(Module.AUDIO, "playback prepared sampleRate=${VoiceTransportConfig.SAMPLE_RATE_HZ}")
        return true
    }

    @Synchronized
    fun startRecorder(): AudioRecord? {
        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            return try {
                audioRecord?.startRecording()
                acquireAudioFocus()
                audioRecord
            } catch (t: Throwable) {
                Log.e(TAG, "failed to restart recorder", t)
                AppLogger.error(Module.AUDIO, "recorder restart failed: ${t.message}")
                null
            }
        }

        val minBytes = AudioRecord.getMinBufferSize(
            VoiceTransportConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            Log.e(TAG, "AudioRecord min buffer unavailable")
            AppLogger.error(Module.AUDIO, "AudioRecord min buffer unavailable")
            return null
        }
        Log.d(TAG, "startRecorder minBytes=$minBytes frameBytes=$frameBytes")

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            VoiceTransportConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, frameBytes * 8)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Log.e(TAG, "AudioRecord init failed")
            AppLogger.error(Module.AUDIO, "AudioRecord init failed")
            return null
        }

        record.startRecording()
        audioRecord = record
        acquireAudioFocus()
        Log.d(TAG, "recorder started")
        AppLogger.info(Module.AUDIO, "recorder started sampleRate=${VoiceTransportConfig.SAMPLE_RATE_HZ} frameBytes=$frameBytes")
        return record
    }

    @Synchronized
    fun stopRecorder() {
        val record = audioRecord ?: return
        try {
            record.stop()
        } catch (_: Throwable) {
        }
        record.release()
        audioRecord = null
        releaseAudioFocusIfIdle()
        Log.d(TAG, "recorder stopped")
        AppLogger.info(Module.AUDIO, "recorder stopped")
    }

    @Synchronized
    fun writePlayback(payload: ByteArray): Boolean {
        val track = audioTrack ?: return false
        track.write(payload, 0, payload.size, AudioTrack.WRITE_BLOCKING)
        if (!playbackStarted) {
            bufferedFrames += 1
            if (bufferedFrames >= 3) {
                acquireAudioFocus(preferSpeaker = true)
                track.play()
                playbackStarted = true
                Log.d(TAG, "playback started")
                AppLogger.info(Module.AUDIO, "playback started (3-frame buffer ready)")
                return true
            }
        }
        return false
    }

    @Synchronized
    fun onPlaybackIdle() {
        val track = audioTrack ?: return
        if (!playbackStarted) {
            bufferedFrames = 0
            return
        }
        try {
            track.pause()
            track.flush()
        } catch (_: Throwable) {
        }
        playbackStarted = false
        bufferedFrames = 0
        releaseAudioFocusIfIdle()
        Log.d(TAG, "playback idle")
        AppLogger.debug(Module.AUDIO, "playback idle")
    }

    @Synchronized
    fun releasePlayback() {
        val track = audioTrack ?: return
        try {
            if (playbackStarted) {
                track.stop()
            }
        } catch (_: Throwable) {
        }
        try {
            track.flush()
        } catch (_: Throwable) {
        }
        track.release()
        audioTrack = null
        playbackPrepared = false
        playbackStarted = false
        bufferedFrames = 0
        releaseAudioFocusIfIdle()
        Log.d(TAG, "playback released")
        AppLogger.info(Module.AUDIO, "playback released")
    }

    @Synchronized
    fun shutdown() {
        stopRecorder()
        releasePlayback()
    }

    private fun acquireAudioFocus() {
        acquireAudioFocus(preferSpeaker = false)
    }

    private fun acquireAudioFocus(preferSpeaker: Boolean) {
        systemAudioManager.requestAudioFocus(audioFocusRequest)
        if (preferSpeaker) {
            systemAudioManager.mode = AudioManager.MODE_NORMAL
            try {
                systemAudioManager.stopBluetoothSco()
            } catch (_: Throwable) {
            }
            systemAudioManager.isBluetoothScoOn = false
        } else {
            systemAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        systemAudioManager.isSpeakerphoneOn = preferSpeaker
    }

    private fun releaseAudioFocusIfIdle() {
        if (audioRecord != null || playbackStarted) return
        try {
            systemAudioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (_: Throwable) {
        }
        systemAudioManager.mode = AudioManager.MODE_NORMAL
        systemAudioManager.isSpeakerphoneOn = false
    }
}