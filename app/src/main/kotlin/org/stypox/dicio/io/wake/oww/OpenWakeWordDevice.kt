package org.stypox.dicio.io.wake.oww

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import org.tensorflow.lite.Interpreter

class OpenWakeWordDevice(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) : WakeDevice {
    private val _state: MutableStateFlow<WakeState>
    override val state: StateFlow<WakeState>

    private val cacheDir: File = appContext.cacheDir
    private val owwFolder = File(appContext.filesDir, "openWakeWord")
    private val melFile = FileToDownload(MEL_URL, File(owwFolder, "melspectrogram.tflite"))
    private val embFile = FileToDownload(EMB_URL, File(owwFolder, "embedding.tflite"))
    private val wakeFile = FileToDownload(WAKE_URL, File(owwFolder, "wake.tflite"))
    private val userWakeFile = userWakeFile(appContext)
    private val allModelFiles: List<FileToDownload>
        get() = if (userWakeFile.isFile) listOf(melFile, embFile) else listOf(melFile, embFile, wakeFile)

    private val audio = FloatArray(OwwModel.MEL_INPUT_COUNT)
    private val modelLock = Any()
    private var model: OwwModel? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val destroyed = AtomicBoolean(false)
    private var downloadJob: Job? = null

    init {
        _state = if (allModelFiles.any(FileToDownload::needsToBeDownloaded)) {
            MutableStateFlow(WakeState.NotDownloaded)
        } else {
            MutableStateFlow(WakeState.NotLoaded)
        }
        state = _state
    }

    override fun download() {
        if (destroyed.get() || downloadJob?.isActive == true) return
        _state.value = WakeState.Downloading(Progress.UNKNOWN)

        downloadJob = scope.launch {
            try {
                owwFolder.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = allModelFiles,
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress ->
                    if (!destroyed.get()) _state.value = WakeState.Downloading(progress)
                }
            } catch (throwable: Throwable) {
                if (!destroyed.get()) {
                    Log.e(TAG, "Can't download OpenWakeWord model", throwable)
                    _state.value = WakeState.ErrorDownloading(throwable)
                }
                return@launch
            }

            if (!destroyed.get()) _state.value = WakeState.NotLoaded
        }
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (audio16bitPcm.size != OwwModel.MEL_INPUT_COUNT) {
            throw IllegalArgumentException(
                "OwwModel can only process audio frames of ${OwwModel.MEL_INPUT_COUNT} samples"
            )
        }

        return synchronized(modelLock) {
            if (destroyed.get()) throw IOException("Wake word device has been destroyed")

            var activeModel = model
            if (activeModel == null) {
                if (_state.value.let { it != WakeState.NotLoaded && it !is WakeState.ErrorLoading }) {
                    throw IOException("Model has not been downloaded yet")
                }

                try {
                    _state.value = WakeState.Loading
                    activeModel = OwwModel(
                        melFile.file,
                        embFile.file,
                        if (userWakeFile.isFile) userWakeFile else wakeFile.file,
                    )
                    model = activeModel
                    _state.value = WakeState.Loaded
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Failed to load model", throwable)
                    _state.value = WakeState.ErrorLoading(throwable)
                    throw throwable
                }
            }

            for (i in 0..<OwwModel.MEL_INPUT_COUNT) {
                audio[i] = audio16bitPcm[i].toFloat() / 32768.0f
            }

            activeModel!!.processFrame(audio) > 0.8f
        }
    }

    override fun frameSize(): Int = OwwModel.MEL_INPUT_COUNT

    override fun isOccupyingResources(): Boolean = synchronized(modelLock) { model != null }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        downloadJob?.cancel()
        synchronized(modelLock) {
            model?.close()
            model = null
        }
        scope.cancel()
    }

    override fun isHeyDicio(): Boolean = !userWakeFile.isFile

    companion object {
        val TAG = OpenWakeWordDevice::class.simpleName
        const val MEL_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite"
        const val EMB_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite"
        const val WAKE_URL = "https://github.com/Stypox/dicio-android/releases/download/v2.0/hey_dicio_v6.0.tflite"

        private fun userWakeFile(context: Context) =
            File(context.filesDir, "openWakeWord/userwake.tflite")

        suspend fun addUserWakeFile(context: Context, source: Uri) {
            val userWakeFile = userWakeFile(context)
            withContext(Dispatchers.IO) {
                val partialFile = File.createTempFile(userWakeFile.name, ".part", context.cacheDir)
                try {
                    val inputStream = context.contentResolver.openInputStream(source)
                        ?: throw IOException("Could not open custom wake model: $source")
                    inputStream.use { input ->
                        partialFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    validateWakeModel(partialFile)
                    userWakeFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Could not create wake model directory $parent")
                        }
                    }
                    try {
                        Files.move(
                            partialFile.toPath(),
                            userWakeFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            partialFile.toPath(),
                            userWakeFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    partialFile.delete()
                }
            }
        }

        suspend fun removeUserWakeFile(context: Context) {
            withContext(Dispatchers.IO) {
                val file = userWakeFile(context)
                if (file.exists() && !file.delete()) {
                    throw IOException("Could not remove custom wake model $file")
                }
            }
        }

        @Throws(IOException::class)
        private fun validateWakeModel(file: File) {
            val interpreter = try {
                Interpreter(file)
            } catch (throwable: Throwable) {
                throw IOException("Custom wake model is not a valid TensorFlow Lite model", throwable)
            }
            try {
                interpreter.allocateTensors()
                val inputShape = interpreter.getInputTensor(0).shape()
                val outputShape = interpreter.getOutputTensor(0).shape()
                if (!inputShape.contentEquals(
                        intArrayOf(1, OwwModel.WAKE_INPUT_COUNT, OwwModel.EMB_FEATURE_SIZE)
                    ) || !outputShape.contentEquals(intArrayOf(1, 1))
                ) {
                    throw IOException(
                        "Custom wake model has incompatible tensors: " +
                            "input=${inputShape.contentToString()}, " +
                            "output=${outputShape.contentToString()}"
                    )
                }
            } finally {
                interpreter.close()
            }
        }
    }
}
