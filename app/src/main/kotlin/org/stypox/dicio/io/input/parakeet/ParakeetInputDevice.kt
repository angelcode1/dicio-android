package org.stypox.dicio.io.input.parakeet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial

/**
 * English command recognizer backed by sherpa-onnx and Parakeet TDT-CTC 110M INT8.
 *
 * The model is deliberately non-streaming: Dicio only needs one accurate final command after a
 * wake word. Silero VAD performs endpointing, then the first completed speech segment is decoded
 * once. This keeps the driving-assistant path deterministic and prevents duplicate commands.
 */
class ParakeetInputDevice(
    appContext: Context,
    private val okHttpClient: OkHttpClient,
) : SttInputDevice {
    private val context = appContext.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default)
    private val lock = Any()
    private val destroyed = AtomicBoolean(false)
    private val finalDispatched = AtomicBoolean(false)

    private val modelDir = File(context.noBackupFilesDir, MODEL_DIR_NAME).apply { mkdirs() }
    private val modelFile = File(modelDir, MODEL_FILE_NAME)
    private val tokensFile = File(modelDir, TOKENS_FILE_NAME)
    private val vadFile = File(modelDir, VAD_FILE_NAME)
    private val modelDownload = FileToDownload(MODEL_URL, modelFile)
    private val tokensDownload = FileToDownload(TOKENS_URL, tokensFile)
    private val vadDownload = FileToDownload(VAD_URL, vadFile)
    private val filesToDownload = listOf(modelDownload, tokensDownload, vadDownload)

    init {
        migrateLegacyExtractedFiles()
        cleanupLegacyArchiveArtifacts()
    }

    private val _uiState = MutableStateFlow<SttState>(
        if (modelFilesReady()) SttState.NotLoaded else SttState.NotDownloaded
    )
    override val uiState: StateFlow<SttState> = _uiState

    private var operationsJob: Job? = null
    private var listeningJob: Job? = null
    private var pendingListener: ((InputEvent) -> Unit)? = null
    private var activeListener: ((InputEvent) -> Unit)? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var audioRecord: AudioRecord? = null

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (destroyed.get()) return false
        when (_uiState.value) {
            SttState.NotDownloaded,
            is SttState.ErrorDownloading -> downloadAndLoad(thenStartListeningEventListener)

            is SttState.Downloading,
            is SttState.Loading -> if (thenStartListeningEventListener != null) {
                synchronized(lock) { pendingListener = thenStartListeningEventListener }
            }

            SttState.NotLoaded,
            is SttState.ErrorLoading -> load(thenStartListeningEventListener)

            SttState.Loaded -> if (thenStartListeningEventListener != null) {
                startListening(thenStartListeningEventListener)
            }

            SttState.Listening -> return true
            else -> return false
        }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        if (destroyed.get()) return
        when (_uiState.value) {
            SttState.NotDownloaded,
            is SttState.ErrorDownloading -> downloadAndLoad(eventListener)

            SttState.NotLoaded,
            is SttState.ErrorLoading -> load(eventListener)

            is SttState.Downloading,
            is SttState.Loading -> synchronized(lock) {
                // Match Dicio's existing toggle behavior while a recognizer is being prepared.
                pendingListener = if (pendingListener == null) eventListener else null
            }

            SttState.Loaded -> startListening(eventListener)
            SttState.Listening -> stopListening()
            else -> Unit
        }
    }

    private fun downloadAndLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?) {
        if (destroyed.get() || operationsJob?.isActive == true) return
        synchronized(lock) { pendingListener = thenStartListeningEventListener }
        invalidateBadDownloadMarkers()
        _uiState.value = SttState.Downloading(Progress.UNKNOWN)

        operationsJob = scope.launch(Dispatchers.IO) {
            try {
                downloadBinaryFilesWithPartial(
                    urlsFiles = filesToDownload,
                    httpClient = okHttpClient,
                    cacheDir = context.cacheDir,
                ) { progress ->
                    if (!destroyed.get()) _uiState.value = SttState.Downloading(progress)
                }

                verifyDownloadedFile(modelDownload, MODEL_SHA256)
                verifyDownloadedFile(tokensDownload, TOKENS_SHA256)
                verifyDownloadedFile(vadDownload, VAD_SHA256)

                if (!modelFilesReady()) {
                    throw IOException("Downloaded Parakeet model files failed validation")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to prepare Parakeet model", t)
                synchronized(lock) { pendingListener = null }
                if (!destroyed.get()) _uiState.value = SttState.ErrorDownloading(t)
                return@launch
            }

            if (destroyed.get()) return@launch
            _uiState.value = SttState.NotLoaded
            try {
                loadModelsAndMaybeStart()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Parakeet model", t)
                releaseModels()
                synchronized(lock) { pendingListener = null }
                if (!destroyed.get()) _uiState.value = SttState.ErrorLoading(t)
            }
        }
    }

    private fun verifyDownloadedFile(download: FileToDownload, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        download.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            download.file.delete()
            download.lastDownloadedUrlFile.delete()
            throw IOException("Checksum mismatch for ${download.file.name}")
        }
    }

    private fun load(thenStartListeningEventListener: ((InputEvent) -> Unit)?) {
        if (destroyed.get() || operationsJob?.isActive == true) return
        synchronized(lock) { pendingListener = thenStartListeningEventListener }
        _uiState.value = SttState.Loading(thenStartListeningEventListener != null)

        operationsJob = scope.launch(Dispatchers.IO) {
            try {
                loadModelsAndMaybeStart()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Parakeet model", t)
                releaseModels()
                synchronized(lock) { pendingListener = null }
                if (!destroyed.get()) _uiState.value = SttState.ErrorLoading(t)
            }
        }
    }

    private fun loadModelsAndMaybeStart() {
        if (destroyed.get()) return
        _uiState.value = SttState.Loading(synchronized(lock) { pendingListener != null })

        var createdRecognizer: OfflineRecognizer? = null
        var createdVad: Vad? = null
        try {
            createdRecognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath),
                        tokens = tokensFile.absolutePath,
                        numThreads = ASR_THREADS,
                        provider = "cpu",
                    ),
                ),
            )
            createdVad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadFile.absolutePath,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = VAD_MIN_SILENCE_SECONDS,
                        minSpeechDuration = VAD_MIN_SPEECH_SECONDS,
                        windowSize = AUDIO_CHUNK_SAMPLES,
                        maxSpeechDuration = VAD_MAX_SPEECH_SECONDS,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = VAD_THREADS,
                    provider = "cpu",
                ),
            )

            if (destroyed.get()) {
                createdVad.release()
                createdRecognizer.release()
                return
            }

            recognizer = createdRecognizer
            vad = createdVad
            _uiState.value = SttState.Loaded

            val listener = synchronized(lock) {
                pendingListener.also { pendingListener = null }
            }
            if (listener != null) startListening(listener)
        } catch (t: Throwable) {
            createdVad?.release()
            createdRecognizer?.release()
            throw t
        }
    }

    private fun startListening(eventListener: (InputEvent) -> Unit) {
        if (destroyed.get() || recognizer == null || vad == null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            eventListener(InputEvent.Error(SecurityException("Microphone permission is not granted")))
            return
        }

        synchronized(lock) {
            activeListener = eventListener
            finalDispatched.set(false)
        }
        vad?.reset()

        listeningJob = scope.launch(Dispatchers.IO) {
            try {
                val recorder = createAudioRecord()
                synchronized(lock) {
                    if (destroyed.get() || activeListener !== eventListener) {
                        recorder.release()
                        return@launch
                    }
                    audioRecord = recorder
                }
                recorder.startRecording()
                _uiState.value = SttState.Listening
                recordUntilEndpoint(recorder)
            } catch (t: Throwable) {
                if (!finalDispatched.get() && !destroyed.get()) {
                    Log.e(TAG, "Parakeet recognition failed", t)
                    finishListening(InputEvent.Error(t))
                }
            }
        }
    }

    private fun createAudioRecord(): AudioRecord {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is not granted")
        }
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw IllegalStateException("Unsupported 16 kHz mono microphone configuration")
        }
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, AUDIO_CHUNK_SAMPLES * 2 * 4))
            .build()
    }

    private fun recordUntilEndpoint(recorder: AudioRecord) {
        val localVad = vad ?: return
        val buffer = ShortArray(AUDIO_CHUNK_SAMPLES)
        val startedAt = SystemClock.elapsedRealtime()
        var heardSpeech = false

        while (!destroyed.get() && !finalDispatched.get() && listeningJob?.isActive == true) {
            val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read < 0) throw IOException("AudioRecord.read failed with code $read")
            if (read == 0) continue

            val samples = FloatArray(read) { buffer[it] / 32768.0f }
            localVad.acceptWaveform(samples)
            heardSpeech = heardSpeech || localVad.isSpeechDetected()

            if (!localVad.empty()) {
                decodeAndFinish(localVad.front().samples)
                localVad.pop()
                return
            }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (!heardSpeech && elapsed >= NO_SPEECH_TIMEOUT_MS) {
                finishListening(InputEvent.None)
                return
            }
            if (elapsed >= MAX_LISTENING_MS) {
                localVad.flush()
                if (!localVad.empty()) {
                    decodeAndFinish(localVad.front().samples)
                    localVad.pop()
                } else {
                    finishListening(InputEvent.None)
                }
                return
            }
        }
    }

    private fun decodeAndFinish(samples: FloatArray) {
        stopAndReleaseAudioRecord()
        val localRecognizer = recognizer ?: return finishListening(InputEvent.None)
        val stream = localRecognizer.createStream()
        val text = try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            localRecognizer.decode(stream)
            localRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }

        if (text.isBlank()) {
            finishListening(InputEvent.None)
        } else {
            finishListening(InputEvent.Final(listOf(text to 1.0f)))
        }
    }

    private fun finishListening(event: InputEvent) {
        if (!finalDispatched.compareAndSet(false, true)) return
        stopAndReleaseAudioRecord()
        vad?.reset()
        val listener = synchronized(lock) {
            activeListener.also { activeListener = null }
        }
        if (!destroyed.get()) _uiState.value = SttState.Loaded
        listener?.invoke(event)
    }

    override fun stopListening() {
        if (destroyed.get() || _uiState.value != SttState.Listening) return
        listeningJob?.cancel()
        finishListening(InputEvent.None)
    }

    private fun stopAndReleaseAudioRecord() {
        val recorder = synchronized(lock) {
            audioRecord.also { audioRecord = null }
        } ?: return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: IllegalStateException) {
            // Cancellation can race with endpointing.
        }
        recorder.release()
    }

    private fun modelFilesReady(): Boolean {
        return modelFile.length() >= MIN_MODEL_BYTES &&
            tokensFile.length() >= MIN_TOKENS_BYTES &&
            vadFile.length() >= MIN_VAD_BYTES &&
            filesToDownload.none(FileToDownload::needsToBeDownloaded)
    }

    private fun invalidateBadDownloadMarkers() {
        invalidateIfBad(modelDownload, MIN_MODEL_BYTES)
        invalidateIfBad(tokensDownload, MIN_TOKENS_BYTES)
        invalidateIfBad(vadDownload, MIN_VAD_BYTES)
    }

    private fun invalidateIfBad(download: FileToDownload, minimumBytes: Long) {
        if (download.file.length() < minimumBytes) {
            download.file.delete()
            download.lastDownloadedUrlFile.delete()
        }
    }

    private fun migrateLegacyExtractedFiles() {
        // The previous build only renamed these files into their final names after a complete
        // successful archive extraction. Reuse them instead of downloading 126 MB again.
        if (modelFile.length() >= MIN_MODEL_BYTES) {
            modelDownload.lastDownloadedUrlFile.writeText(MODEL_URL)
        }
        if (tokensFile.length() >= MIN_TOKENS_BYTES) {
            tokensDownload.lastDownloadedUrlFile.writeText(TOKENS_URL)
        }
        if (vadFile.length() >= MIN_VAD_BYTES) {
            vadDownload.lastDownloadedUrlFile.writeText(VAD_URL)
        }
    }

    private fun cleanupLegacyArchiveArtifacts() {
        File(modelDir, LEGACY_ARCHIVE_FILE_NAME).delete()
        File(modelDir, LEGACY_ARCHIVE_FILE_NAME + ".url.txt").delete()
        File(modelDir, MODEL_FILE_NAME + ".extracting").delete()
        File(modelDir, TOKENS_FILE_NAME + ".extracting").delete()
    }

    private fun releaseModels() {
        vad?.release()
        vad = null
        recognizer?.release()
        recognizer = null
    }

    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        synchronized(lock) {
            pendingListener = null
            activeListener = null
        }
        finalDispatched.set(true)
        listeningJob?.cancel()
        operationsJob?.cancel()
        stopAndReleaseAudioRecord()
        releaseModels()
        scope.cancel()
        _uiState.value = SttState.NotInitialized
    }

    companion object {
        private const val MODEL_DIR_NAME = "parakeet-110m-int8"
        private const val MODEL_FILE_NAME = "model.int8.onnx"
        private const val TOKENS_FILE_NAME = "tokens.txt"
        private const val VAD_FILE_NAME = "silero_vad.onnx"
        private const val LEGACY_ARCHIVE_FILE_NAME = "parakeet-110m-int8.tar.bz2"

        private const val MODEL_RELEASE_BASE =
            "https://github.com/angelcode1/dicio-android/releases/download/parakeet-110m-int8-v1"
        private const val MODEL_URL = "$MODEL_RELEASE_BASE/model.int8.onnx"
        private const val TOKENS_URL = "$MODEL_RELEASE_BASE/tokens.txt"
        private const val VAD_URL = "$MODEL_RELEASE_BASE/silero_vad.onnx"

        // SHA-256 values generated on GitHub's runner directly from sherpa-onnx's official model
        // archive and official Silero VAD release before the unmodified files were republished.
        private const val MODEL_SHA256 = "9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a"
        private const val TOKENS_SHA256 = "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"
        private const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val AUDIO_CHUNK_SAMPLES = 512
        private const val ASR_THREADS = 2
        private const val VAD_THREADS = 1
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SILENCE_SECONDS = 0.6f
        private const val VAD_MIN_SPEECH_SECONDS = 0.15f
        private const val VAD_MAX_SPEECH_SECONDS = 8.0f
        private const val NO_SPEECH_TIMEOUT_MS = 6000L
        private const val MAX_LISTENING_MS = 12000L
        private const val HASH_BUFFER_BYTES = 1024 * 1024

        private const val MIN_MODEL_BYTES = 100_000_000L
        private const val MIN_TOKENS_BYTES = 1_000L
        private const val MIN_VAD_BYTES = 100_000L

        private val TAG = ParakeetInputDevice::class.simpleName
    }
}
