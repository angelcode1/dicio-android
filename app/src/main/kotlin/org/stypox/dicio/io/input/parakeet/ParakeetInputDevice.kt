package org.stypox.dicio.io.input.parakeet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial

/**
 * English command recognizer backed by sherpa-onnx and Parakeet TDT-CTC 110M INT8.
 */
class ParakeetInputDevice(
    appContext: Context,
    private val okHttpClient: OkHttpClient,
) : SttInputDevice {
    private val context = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val destroyed = AtomicBoolean(false)
    private val finalDispatched = AtomicBoolean(false)

    private val modelDir = File(context.noBackupFilesDir, MODEL_DIR_NAME).apply { mkdirs() }
    private val modelFile = File(modelDir, MODEL_FILE_NAME)
    private val tokensFile = File(modelDir, TOKENS_FILE_NAME)
    private val vadFile = File(modelDir, VAD_FILE_NAME)
    private val archiveFile = File(modelDir, MODEL_ARCHIVE_FILE_NAME)
    private val archiveDownload = FileToDownload(MODEL_ARCHIVE_URL, archiveFile)
    private val vadDownload = FileToDownload(VAD_URL, vadFile)
    private val filesToDownload = listOf(archiveDownload, vadDownload)

    private val _uiState = MutableStateFlow<SttState>(
        if (modelFilesReady()) SttState.NotLoaded else SttState.NotDownloaded
    )
    override val uiState: StateFlow<SttState> = _uiState

    private var operationsJob: Job? = null
    private var listeningJob: Job? = null
    private var pendingListener: ((InputEvent) -> Unit)? = null
    private var pendingRecordingContext: Context? = null
    private var activeListener: ((InputEvent) -> Unit)? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var audioRecord: AudioRecord? = null

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        return tryLoad(thenStartListeningEventListener, context)
    }

    fun tryLoad(
        thenStartListeningEventListener: ((InputEvent) -> Unit)?,
        recordingContext: Context,
    ): Boolean {
        if (destroyed.get()) return false
        when (_uiState.value) {
            SttState.NotDownloaded,
            is SttState.ErrorDownloading ->
                downloadAndLoad(thenStartListeningEventListener, recordingContext)

            is SttState.Downloading,
            is SttState.Unzipping,
            is SttState.Loading -> if (thenStartListeningEventListener != null) {
                synchronized(lock) {
                    pendingListener = thenStartListeningEventListener
                    pendingRecordingContext = recordingContext
                }
            }

            SttState.NotLoaded,
            is SttState.ErrorLoading -> load(thenStartListeningEventListener, recordingContext)

            SttState.Loaded -> if (thenStartListeningEventListener != null) {
                startListening(thenStartListeningEventListener, recordingContext)
            }

            SttState.Listening -> return thenStartListeningEventListener == null
            else -> return false
        }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        if (destroyed.get()) return
        when (_uiState.value) {
            SttState.NotDownloaded,
            is SttState.ErrorDownloading -> downloadAndLoad(eventListener, context)

            SttState.NotLoaded,
            is SttState.ErrorLoading -> load(eventListener, context)

            is SttState.Downloading,
            is SttState.Unzipping,
            is SttState.Loading -> synchronized(lock) {
                if (pendingListener == null) {
                    pendingListener = eventListener
                    pendingRecordingContext = context
                } else {
                    pendingListener = null
                    pendingRecordingContext = null
                }
            }

            SttState.Loaded -> startListening(eventListener, context)
            SttState.Listening -> stopListening()
            else -> Unit
        }
    }

    private fun downloadAndLoad(
        thenStartListeningEventListener: ((InputEvent) -> Unit)?,
        recordingContext: Context,
    ) {
        synchronized(lock) {
            if (destroyed.get() || operationsJob?.isActive == true) return
            pendingListener = thenStartListeningEventListener
            pendingRecordingContext = if (thenStartListeningEventListener != null) recordingContext else null
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

                    if (archiveFile.isFile) {
                        if (!destroyed.get()) _uiState.value = SttState.Unzipping(Progress.UNKNOWN)
                        extractOfficialModelArchive()
                        if (!archiveFile.delete()) {
                            Log.w(TAG, "Could not delete extracted Parakeet archive")
                        }
                    }

                    if (!modelFilesReady()) {
                        throw IOException("Downloaded Parakeet model files failed validation")
                    }
                } catch (throwable: Throwable) {
                    if (!destroyed.get()) {
                        Log.e(TAG, "Failed to prepare Parakeet model", throwable)
                        archiveFile.delete()
                        archiveDownload.lastDownloadedUrlFile.delete()
                        synchronized(lock) {
                            pendingListener = null
                            pendingRecordingContext = null
                        }
                        _uiState.value = SttState.ErrorDownloading(throwable)
                    }
                    return@launch
                }

                if (destroyed.get()) return@launch
                _uiState.value = SttState.NotLoaded
                try {
                    loadModelsAndMaybeStart()
                } catch (throwable: Throwable) {
                    if (!destroyed.get()) {
                        Log.e(TAG, "Failed to load Parakeet model", throwable)
                        releaseModels()
                        synchronized(lock) {
                            pendingListener = null
                            pendingRecordingContext = null
                        }
                        _uiState.value = SttState.ErrorLoading(throwable)
                    }
                }
            }
        }
    }

    private fun extractOfficialModelArchive() {
        val wanted = mapOf(
            MODEL_FILE_NAME to modelFile,
            TOKENS_FILE_NAME to tokensFile,
        )
        val temporary = wanted.mapValues { (_, target) ->
            File(target.parentFile, target.name + ".extracting")
        }
        temporary.values.forEach(File::delete)
        val found = mutableSetOf<String>()

        try {
            BufferedInputStream(archiveFile.inputStream()).use { input ->
                BZip2CompressorInputStream(input).use { bzip2 ->
                    TarArchiveInputStream(bzip2).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val name = entry.name.substringAfterLast('/')
                            val output = temporary[name] ?: continue
                            output.outputStream().buffered().use { tar.copyTo(it) }
                            found += name
                        }
                    }
                }
            }

            if (found != wanted.keys) {
                throw IOException("Parakeet archive did not contain the expected model files")
            }
            for ((name, target) in wanted) {
                val extracted = temporary.getValue(name)
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not replace ${target.name}")
                }
                if (!extracted.renameTo(target)) {
                    throw IOException("Could not install ${target.name}")
                }
            }
        } finally {
            temporary.values.forEach(File::delete)
        }
    }

    private fun load(
        thenStartListeningEventListener: ((InputEvent) -> Unit)?,
        recordingContext: Context,
    ) {
        synchronized(lock) {
            if (destroyed.get() || operationsJob?.isActive == true) return
            pendingListener = thenStartListeningEventListener
            pendingRecordingContext = if (thenStartListeningEventListener != null) recordingContext else null
            _uiState.value = SttState.Loading(thenStartListeningEventListener != null)
            operationsJob = scope.launch(Dispatchers.IO) {
                try {
                    loadModelsAndMaybeStart()
                } catch (throwable: Throwable) {
                    if (!destroyed.get()) {
                        Log.e(TAG, "Failed to load Parakeet model", throwable)
                        releaseModels()
                        synchronized(lock) {
                            pendingListener = null
                            pendingRecordingContext = null
                        }
                        _uiState.value = SttState.ErrorLoading(throwable)
                    }
                }
            }
        }
    }

    private fun loadModelsAndMaybeStart() {
        if (destroyed.get()) return
        _uiState.value = SttState.Loading(synchronized(lock) { pendingListener != null })

        val newRecognizer = OfflineRecognizer(
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
        val newVad = try {
            Vad(
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
        } catch (throwable: Throwable) {
            newRecognizer.release()
            throw throwable
        }

        val committed = synchronized(lock) {
            if (destroyed.get()) {
                false
            } else {
                recognizer = newRecognizer
                vad = newVad
                true
            }
        }
        if (!committed) {
            newVad.release()
            newRecognizer.release()
            return
        }
        if (destroyed.get()) return

        _uiState.value = SttState.Loaded
        val pending = synchronized(lock) {
            val listener = pendingListener
            val recordingContext = pendingRecordingContext ?: context
            pendingListener = null
            pendingRecordingContext = null
            Pair(listener, recordingContext)
        }
        pending.first?.let { startListening(it, pending.second) }
    }

    private fun startListening(eventListener: (InputEvent) -> Unit, recordingContext: Context) {
        if (destroyed.get()) return
        if (recordingContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            eventListener(InputEvent.Error(SecurityException("Microphone permission is not granted")))
            return
        }

        synchronized(lock) {
            if (destroyed.get() || recognizer == null || vad == null || listeningJob?.isActive == true) {
                return
            }
            activeListener = eventListener
            finalDispatched.set(false)
            vad?.reset()
            listeningJob = scope.launch(Dispatchers.IO) {
                try {
                    val recorder = createAudioRecord(recordingContext)
                    synchronized(lock) {
                        if (destroyed.get() || activeListener !== eventListener) {
                            recorder.release()
                            return@launch
                        }
                        audioRecord = recorder
                    }
                    recorder.startRecording()
                    if (!destroyed.get()) _uiState.value = SttState.Listening
                    recordUntilEndpoint(recorder)
                } catch (throwable: Throwable) {
                    if (!finalDispatched.get() && !destroyed.get()) {
                        Log.e(TAG, "Parakeet recognition failed", throwable)
                        finishListening(InputEvent.Error(throwable))
                    }
                }
            }
        }
    }

    private fun createAudioRecord(recordingContext: Context): AudioRecord {
        if (recordingContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, AUDIO_CHUNK_SAMPLES * 2 * 4))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setContext(recordingContext)
        }
        return builder.build()
    }

    private fun recordUntilEndpoint(recorder: AudioRecord) {
        val localVad = synchronized(lock) { vad } ?: return
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
        val localRecognizer = synchronized(lock) { recognizer }
            ?: return finishListening(InputEvent.None)
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
        if (!destroyed.get()) {
            synchronized(lock) {
                if (!destroyed.get()) vad?.reset()
            }
        }
        val listener = synchronized(lock) {
            activeListener.also { activeListener = null }
        }
        if (!destroyed.get()) _uiState.value = SttState.Loaded
        listener?.invoke(event)
    }

    override fun stopListening() {
        if (destroyed.get() || _uiState.value != SttState.Listening) return
        synchronized(lock) { listeningJob }?.cancel()
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
            !archiveDownload.needsToBeDownloaded() &&
            !vadDownload.needsToBeDownloaded()
    }

    private fun invalidateBadDownloadMarkers() {
        if (modelFile.length() < MIN_MODEL_BYTES || tokensFile.length() < MIN_TOKENS_BYTES) {
            modelFile.delete()
            tokensFile.delete()
            archiveDownload.lastDownloadedUrlFile.delete()
        }
        if (vadFile.length() < MIN_VAD_BYTES) {
            vadFile.delete()
            vadDownload.lastDownloadedUrlFile.delete()
        }
    }

    private fun releaseModels() {
        val resources = synchronized(lock) {
            Pair(vad.also { vad = null }, recognizer.also { recognizer = null })
        }
        resources.first?.release()
        resources.second?.release()
    }

    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        synchronized(lock) {
            pendingListener = null
            pendingRecordingContext = null
            activeListener = null
        }
        finalDispatched.set(true)

        val listening = synchronized(lock) { listeningJob }
        listening?.cancel()
        // Stop the blocking AudioRecord read before waiting for the listening coroutine to exit.
        stopAndReleaseAudioRecord()
        listening?.join()

        val operation = synchronized(lock) { operationsJob }
        operation?.cancelAndJoin()

        releaseModels()
        scope.cancel()
        _uiState.value = SttState.NotInitialized
    }

    companion object {
        private const val MODEL_DIR_NAME = "parakeet-110m-int8"
        private const val MODEL_FILE_NAME = "model.int8.onnx"
        private const val TOKENS_FILE_NAME = "tokens.txt"
        private const val VAD_FILE_NAME = "silero_vad.onnx"
        private const val MODEL_ARCHIVE_FILE_NAME = "parakeet-110m-int8.tar.bz2"

        private const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2"
        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

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

        private const val MIN_MODEL_BYTES = 100_000_000L
        private const val MIN_TOKENS_BYTES = 1_000L
        private const val MIN_VAD_BYTES = 100_000L

        private val TAG = ParakeetInputDevice::class.simpleName
    }
}
