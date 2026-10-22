package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Native Full-Duplex PCM Audio Engine for Gemini Multimodal Live API:
 * - Captures microphone at 16,000 Hz 16-bit Mono PCM with fallback from VOICE_COMMUNICATION to MIC
 * - Configures speakerphone routing so voice responses play clearly through main speaker or headset
 * - Streams continuous 16kHz PCM in open-mic mode so Gemini Server VAD accurately detects end-of-turn
 * - Plays incoming model audio at 24,000 Hz 16-bit Mono PCM via streaming AudioTrack
 * - Supports instant Barge-In (flushing AudioTrack buffer immediately on user voice above vadThreshold or server interruption)
 */
class PcmAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPcmChunkCaptured: (ByteArray) -> Unit,
    private val onLocalBargeInDetected: () -> Unit
) {
    companion object {
        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000
        private const val CHUNK_DURATION_MS = 60
        private const val SAMPLES_PER_INPUT_CHUNK = (INPUT_SAMPLE_RATE * CHUNK_DURATION_MS) / 1000 // 960 samples = 1920 bytes
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private val isRunning = AtomicBoolean(false)
    private val isModelCurrentlySpeaking = AtomicBoolean(false)

    @Volatile
    var isMuted: Boolean = false

    @Volatile
    var isPttMode: Boolean = false

    @Volatile
    var isPttPressed: Boolean = false

    @Volatile
    var vadThreshold: Float = 0.025f

    private val _inputRms = MutableStateFlow(0f)
    val inputRms: StateFlow<Float> = _inputRms.asStateFlow()

    private val _outputRms = MutableStateFlow(0f)
    val outputRms: StateFlow<Float> = _outputRms.asStateFlow()

    private val _spectrumBands = MutableStateFlow(FloatArray(16) { 0.08f })
    val spectrumBands: StateFlow<FloatArray> = _spectrumBands.asStateFlow()

    private var consecutiveVoiceFrames = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.getAndSet(true)) return

        configureAudioRouting(enable = true)
        setupAudioTrack()
        setupAudioRecord()
    }

    @Suppress("DEPRECATION")
    private fun configureAudioRouting(enable: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val hasHeadset = devices.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    }
                    if (!hasHeadset) {
                        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) {
                            audioManager.setCommunicationDevice(speaker)
                        }
                    }
                } else {
                    if (!audioManager.isBluetoothScoOn && !audioManager.isWiredHeadsetOn) {
                        audioManager.isSpeakerphoneOn = true
                    }
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    audioManager.isSpeakerphoneOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setupAudioTrack() {
        val minOutBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(OUTPUT_SAMPLE_RATE * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minOutBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        audioTrack = track
        playbackChannel = Channel(Channel.UNLIMITED)

        playbackJob = scope.launch(Dispatchers.IO) {
            for (pcmBytes in playbackChannel) {
                if (!isActive || !isRunning.get()) break
                val currentTrack = audioTrack ?: continue
                isModelCurrentlySpeaking.set(true)
                val rms = computeRmsAndBands(pcmBytes, isOutput = true)
                _outputRms.value = rms
                try {
                    currentTrack.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
                } catch (_: Exception) {
                }
                if (playbackChannel.isEmpty) {
                    isModelCurrentlySpeaking.set(false)
                    _outputRms.value = 0f
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioRecord() {
        val minInBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLES_PER_INPUT_CHUNK * 4)

        val recorder = createInitializedAudioRecord(minInBuffer) ?: return

        val sessionId = recorder.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }

        try {
            recorder.startRecording()
        } catch (_: Exception) {
            recorder.release()
            return
        }
        audioRecord = recorder

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(SAMPLES_PER_INPUT_CHUNK * 2)
            while (isActive && isRunning.get()) {
                val readBytes = recorder.read(buffer, 0, buffer.size)
                if (readBytes <= 0) continue

                val shouldTransmit = when {
                    isMuted -> false
                    isPttMode -> isPttPressed
                    else -> true
                }

                if (!shouldTransmit) {
                    _inputRms.value = 0f
                    consecutiveVoiceFrames = 0
                    continue
                }

                val chunk = if (readBytes == buffer.size) buffer.copyOf() else buffer.copyOf(readBytes)
                val rms = computeRmsAndBands(chunk, isOutput = false)
                _inputRms.value = rms

                // Local VAD & Barge-In detection when user speaks above vadThreshold while model is speaking
                if (rms >= vadThreshold) {
                    consecutiveVoiceFrames++
                    if (consecutiveVoiceFrames >= 3 && isModelCurrentlySpeaking.get()) {
                        flushPlayback()
                        onLocalBargeInDetected()
                    }
                } else {
                    consecutiveVoiceFrames = 0
                }

                // Stream 16kHz PCM continuously while unmuted (or while PTT is held)
                // so Gemini's Server VAD accurately detects both speech start and trailing silence
                onPcmChunkCaptured(chunk)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            try {
                val rec = AudioRecord(
                    source,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec
                }
                rec.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Sends a brief trailing silence tail when PTT button is released so Server VAD immediately commits the turn.
     */
    fun sendTrailingSilenceTail() {
        if (!isRunning.get()) return
        scope.launch(Dispatchers.IO) {
            val silenceChunk = ByteArray(SAMPLES_PER_INPUT_CHUNK * 2)
            repeat(6) {
                onPcmChunkCaptured(silenceChunk)
            }
        }
    }

    /**
     * Enqueues 24kHz 16-bit PCM audio bytes received from Gemini serverContent.
     */
    fun enqueuePlaybackPcm24k(pcmBytes: ByteArray) {
        if (!isRunning.get() || pcmBytes.isEmpty()) return
        playbackChannel.trySend(pcmBytes)
    }

    /**
     * Immediately stops and flushes ongoing model audio playback (Barge-In).
     */
    fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) {
            // drain queued chunks immediately
        }
        isModelCurrentlySpeaking.set(false)
        _outputRms.value = 0f
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        captureJob?.cancel()
        playbackJob?.cancel()
        playbackChannel.close()

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        noiseSuppressor = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null

        configureAudioRouting(enable = false)

        _inputRms.value = 0f
        _outputRms.value = 0f
    }

    private fun computeRmsAndBands(pcmBytes: ByteArray, isOutput: Boolean): Float {
        if (pcmBytes.size < 2) return 0f
        val shortCount = pcmBytes.size / 2
        val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSquares = 0.0
        val bands = FloatArray(16)
        val samplesPerBand = (shortCount / 16).coerceAtLeast(1)

        for (i in 0 until shortCount) {
            val sample = shortBuf.get(i).toFloat() / 32768f
            sumSquares += sample * sample
            val bandIdx = min(15, i / samplesPerBand)
            bands[bandIdx] += abs(sample)
        }

        val rms = sqrt(sumSquares / shortCount).toFloat().coerceIn(0f, 1f)
        if (!isOutput || isModelCurrentlySpeaking.get()) {
            for (b in 0 until 16) {
                bands[b] = ((bands[b] / samplesPerBand) * 3.8f).coerceIn(0.05f, 1.0f)
            }
            _spectrumBands.value = bands
        }
        return rms
    }
}
