package org.stypox.dicio.di

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.stypox.dicio.R
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.external_popup.ExternalPopupInputDevice
import org.stypox.dicio.io.input.parakeet.ParakeetInputDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_VOSK
import org.stypox.dicio.settings.datastore.InputDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.UserSettings

interface SttInputDeviceWrapper {
    val uiState: StateFlow<SttState?>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    /**
     * Starts recognition using [recordingContext] when the selected recognizer captures audio
     * itself. RecognitionService uses this to propagate caller attribution on Android 12+.
     */
    fun tryLoadWithRecordingContext(
        recordingContext: Context,
        thenStartListeningEventListener: (InputEvent) -> Unit,
    ): Boolean = tryLoad(thenStartListeningEventListener)

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    fun reinitializeToReleaseResources()
}

class SttInputDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val okHttpClient: OkHttpClient,
    private val activityForResultManager: ActivityForResultManager,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deviceLock = Any()
    private val changeMutex = Mutex()

    private var initialized = false
    private var inputDeviceSetting: InputDevice = INPUT_DEVICE_NOTHING
    private var sttPlaySoundSetting: SttPlaySound = SttPlaySound.STT_PLAY_SOUND_NONE
    private var sttInputDevice: SttInputDevice? = null

    // Calls may arrive during application startup before DataStore emits its first snapshot.
    private var pendingInitialLoad = false
    private var pendingInitialListener: ((InputEvent) -> Unit)? = null
    private var pendingInitialRecordingContext: Context? = null

    private val _uiState: MutableStateFlow<SttState?> = MutableStateFlow(null)
    override val uiState: StateFlow<SttState?> = _uiState
    private var uiStateJob: Job? = null

    init {
        scope.launch {
            dataStore.data
                .map { Pair(it.inputDevice, it.sttPlaySound) }
                .distinctUntilChanged()
                .collect { (inputDevice, sttPlaySound) ->
                    sttPlaySoundSetting = sttPlaySound
                    if (!initialized || inputDeviceSetting != inputDevice) {
                        inputDeviceSetting = inputDevice
                        changeInputDeviceTo(inputDevice)
                    }

                    val pending = synchronized(deviceLock) {
                        initialized = true
                        PendingInitialLoad(
                            requested = pendingInitialLoad,
                            listener = pendingInitialListener,
                            recordingContext = pendingInitialRecordingContext,
                        ).also {
                            pendingInitialLoad = false
                            pendingInitialListener = null
                            pendingInitialRecordingContext = null
                        }
                    }
                    if (pending.requested) {
                        startPendingLoad(pending)
                    }
                }
        }
    }

    private suspend fun changeInputDeviceTo(setting: InputDevice) {
        changeMutex.withLock {
            val newSttInputDevice = buildInputDevice(setting)
            val previous = synchronized(deviceLock) {
                sttInputDevice.also { sttInputDevice = newSttInputDevice }
            }
            restartUiStateJob(newSttInputDevice)
            previous?.destroy()
        }
    }

    private fun buildInputDevice(setting: InputDevice): SttInputDevice? {
        return when (setting) {
            UNRECOGNIZED,
            INPUT_DEVICE_UNSET,
            INPUT_DEVICE_VOSK -> ParakeetInputDevice(appContext, okHttpClient)
            INPUT_DEVICE_EXTERNAL_POPUP ->
                ExternalPopupInputDevice(appContext, activityForResultManager, localeManager)
            INPUT_DEVICE_NOTHING -> null
        }
    }

    private fun restartUiStateJob(newSttInputDevice: SttInputDevice?) {
        uiStateJob?.cancel()
        if (newSttInputDevice == null) {
            uiStateJob = null
            _uiState.value = null
        } else {
            uiStateJob = scope.launch {
                newSttInputDevice.uiState.collect {
                    _uiState.value = it
                    if (it == SttState.Listening) playSound(R.raw.listening_sound)
                }
            }
        }
    }

    private fun playSound(resid: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (sttPlaySoundSetting) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET,
                    SttPlaySound.STT_PLAY_SOUND_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    SttPlaySound.STT_PLAY_SOUND_ALARM -> AudioAttributes.USAGE_ALARM
                    SttPlaySound.STT_PLAY_SOUND_MEDIA -> AudioAttributes.USAGE_MEDIA
                    SttPlaySound.STT_PLAY_SOUND_NONE -> return
                }
            )
            .build()
        val mediaPlayer = MediaPlayer.create(appContext, resid, attributes, 0) ?: return
        mediaPlayer.setVolume(0.75f, 0.75f)
        mediaPlayer.setOnCompletionListener { it.release() }
        mediaPlayer.setOnErrorListener { player, _, _ ->
            player.release()
            true
        }
        mediaPlayer.start()
    }

    private fun wrapEventListener(eventListener: (InputEvent) -> Unit): (InputEvent) -> Unit = {
        if (it is InputEvent.None) {
            scope.launch { playSound(R.raw.listening_no_input_sound) }
        }
        eventListener(it)
    }

    private fun queueIfInitializing(
        listener: ((InputEvent) -> Unit)?,
        recordingContext: Context?,
    ): Boolean {
        synchronized(deviceLock) {
            if (initialized) return false
            pendingInitialLoad = true
            if (listener != null) {
                pendingInitialListener = listener
                pendingInitialRecordingContext = recordingContext
            }
            return true
        }
    }

    private fun startPendingLoad(pending: PendingInitialLoad) {
        val device = synchronized(deviceLock) { sttInputDevice }
        if (device == null) {
            pending.listener?.invoke(
                InputEvent.Error(IllegalStateException("Speech recognition is disabled"))
            )
            return
        }

        val wrappedListener = pending.listener?.let(::wrapEventListener)
        if (device is ParakeetInputDevice && pending.recordingContext != null && wrappedListener != null) {
            device.tryLoad(wrappedListener, pending.recordingContext)
        } else {
            device.tryLoad(wrappedListener)
        }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (queueIfInitializing(thenStartListeningEventListener, null)) return true
        val device = synchronized(deviceLock) { sttInputDevice } ?: return false
        return device.tryLoad(thenStartListeningEventListener?.let(::wrapEventListener))
    }

    override fun tryLoadWithRecordingContext(
        recordingContext: Context,
        thenStartListeningEventListener: (InputEvent) -> Unit,
    ): Boolean {
        if (queueIfInitializing(thenStartListeningEventListener, recordingContext)) return true
        val device = synchronized(deviceLock) { sttInputDevice } ?: return false
        val listener = wrapEventListener(thenStartListeningEventListener)
        return if (device is ParakeetInputDevice) {
            device.tryLoad(listener, recordingContext)
        } else {
            device.tryLoad(listener)
        }
    }

    override fun stopListening() {
        synchronized(deviceLock) { sttInputDevice }?.stopListening()
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        synchronized(deviceLock) { sttInputDevice }?.onClick(wrapEventListener(eventListener))
    }

    override fun reinitializeToReleaseResources() {
        val setting = synchronized(deviceLock) {
            if (!initialized || sttInputDevice == null) return
            inputDeviceSetting
        }
        scope.launch { changeInputDeviceTo(setting) }
    }

    private data class PendingInitialLoad(
        val requested: Boolean,
        val listener: ((InputEvent) -> Unit)?,
        val recordingContext: Context?,
    )
}

@Module
@InstallIn(SingletonComponent::class)
class SttInputDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideInputDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        localeManager: LocaleManager,
        okHttpClient: OkHttpClient,
        activityForResultManager: ActivityForResultManager,
    ): SttInputDeviceWrapper {
        return SttInputDeviceWrapperImpl(
            appContext, dataStore, localeManager, okHttpClient, activityForResultManager
        )
    }
}
