package org.stypox.dicio.io.input.moonshine

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.settings.datastore.MoonshineModel

/**
 * Small Moonshine-backed STT device for short assistant commands.
 *
 * Moonshine owns microphone capture, VAD/endpointing and its model cache. Dicio only consumes
 * partial text for UI feedback and the first completed line as the command. This deliberately
 * avoids layering another VAD, audio recorder or recognition abstraction on top of Moonshine.
 */
class MoonshineInputDevice(
    appContext: Context,
    model: MoonshineModel,
) : SttInputDevice {
    private val context = appContext.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _uiState = MutableStateFlow<SttState>(SttState.NotLoaded)
    override val uiState: StateFlow<SttState> = _uiState

    private val modelArch = when (model) {
        MoonshineModel.MOONSHINE_MODEL_LITE -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
        MoonshineModel.UNRECOGNIZED,
        MoonshineModel.MOONSHINE_MODEL_UNSET,
        MoonshineModel.MOONSHINE_MODEL_BALANCED -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
    }

    private val lock = Any()
    private var mic: MicTranscriber? = null
    private var loadJob: Job? = null
    private var pendingListener: ((InputEvent) -> Unit)? = null
    private var activeListener: ((InputEvent) -> Unit)? = null
    private val finalDispatched = AtomicBoolean(false)

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        when (_uiState.value) {
            SttState.NotLoaded,
            is SttState.ErrorLoading -> load(thenStartListeningEventListener)
            is SttState.Loading -> if (thenStartListeningEventListener != null) {
                synchronized(lock) { pendingListener = thenStartListeningEventListener }
            }
            SttState.Loaded -> if (thenStartListeningEventListener != null) {
                startListening(thenStartListeningEventListener)
            }
            SttState.Listening -> return true
            else -> return false
        }
        return true
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        when (_uiState.value) {
            SttState.NotLoaded,
            is SttState.ErrorLoading -> load(eventListener)
            is SttState.Loading -> synchronized(lock) {
                // A second click while loading cancels an auto-start request; another restores it.
                pendingListener = if (pendingListener == null) eventListener else null
            }
            SttState.Loaded -> startListening(eventListener)
            SttState.Listening -> stopListening()
            else -> Unit
        }
    }

    private fun load(thenStartListeningEventListener: ((InputEvent) -> Unit)?) {
        if (loadJob?.isActive == true) return
        synchronized(lock) { pendingListener = thenStartListeningEventListener }
        _uiState.value = SttState.Loading(thenStartListeningEventListener != null)

        loadJob = scope.launch(Dispatchers.IO) {
            var createdMic: MicTranscriber? = null
            try {
                createdMic = MicTranscriber(context)
                    .language("en")
                    .modelArch(modelArch)
                    .callbacksOnMainThread(false)
                    .onText(::onPartialText)
                    .onLine { line -> onCompletedLine(line.text ?: "") }
                    .onError(::onRuntimeError)
                createdMic.load()
                mic = createdMic
                _uiState.value = SttState.Loaded

                val listener = synchronized(lock) {
                    pendingListener.also { pendingListener = null }
                }
                if (listener != null) startListening(listener)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Moonshine", t)
                createdMic?.close()
                mic = null
                synchronized(lock) { pendingListener = null }
                _uiState.value = SttState.ErrorLoading(t)
            }
        }
    }

    private fun startListening(eventListener: (InputEvent) -> Unit) {
        val currentMic = mic ?: return
        synchronized(lock) {
            activeListener = eventListener
            finalDispatched.set(false)
        }
        scope.launch(Dispatchers.IO) {
            try {
                currentMic.start()
                // The model may have been stopped/destroyed while start() was blocking.
                synchronized(lock) {
                    if (activeListener === eventListener) {
                        _uiState.value = SttState.Listening
                    }
                }
            } catch (t: Throwable) {
                onRuntimeError(t)
            }
        }
    }

    private fun onPartialText(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || finalDispatched.get()) return
        val listener = synchronized(lock) { activeListener }
        listener?.invoke(InputEvent.Partial(cleaned))
    }

    private fun onCompletedLine(text: String) {
        if (!finalDispatched.compareAndSet(false, true)) return
        val listener = synchronized(lock) {
            activeListener.also { activeListener = null }
        } ?: return

        // A command is one completed Moonshine line. Stop immediately so a trailing callback or
        // cabin speech cannot execute a second command for the same wake interaction.
        mic?.stop()
        _uiState.value = SttState.Loaded

        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            listener(InputEvent.None)
        } else {
            listener(InputEvent.Final(listOf(cleaned to 1.0f)))
        }
    }

    private fun onRuntimeError(error: Throwable) {
        Log.e(TAG, "Moonshine recognition failed", error)
        val listener = synchronized(lock) {
            activeListener.also { activeListener = null }
        }
        finalDispatched.set(true)
        mic?.stop()
        _uiState.value = SttState.Loaded
        listener?.invoke(InputEvent.Error(error))
    }

    override fun stopListening() {
        if (_uiState.value != SttState.Listening) return
        finalDispatched.set(true)
        val listener = synchronized(lock) {
            activeListener.also { activeListener = null }
        }
        mic?.stop()
        _uiState.value = SttState.Loaded
        listener?.invoke(InputEvent.None)
    }

    override suspend fun destroy() {
        synchronized(lock) {
            pendingListener = null
            activeListener = null
        }
        finalDispatched.set(true)
        loadJob?.cancel()
        mic?.close()
        mic = null
        scope.cancel()
        _uiState.value = SttState.NotInitialized
    }

    companion object {
        private val TAG = MoonshineInputDevice::class.simpleName
    }
}
