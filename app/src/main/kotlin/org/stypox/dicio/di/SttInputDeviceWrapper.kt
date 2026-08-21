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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.external_popup.ExternalPopupInputDevice
import org.stypox.dicio.io.input.moonshine.MoonshineInputDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_VOSK
import org.stypox.dicio.settings.datastore.InputDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.MoonshineModel
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst


interface SttInputDeviceWrapper {
    val uiState: StateFlow<SttState?>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    fun reinitializeToReleaseResources()
}

class SttInputDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val activityForResultManager: ActivityForResultManager,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)

    private var inputDeviceSetting: InputDevice
    private var moonshineModelSetting: MoonshineModel
    private var sttPlaySoundSetting: SttPlaySound
    private var sttInputDevice: SttInputDevice?

    // null means that the user has not enabled any STT input device
    private val _uiState: MutableStateFlow<SttState?> = MutableStateFlow(null)
    override val uiState: StateFlow<SttState?> = _uiState
    private var uiStateJob: Job? = null

    init {
        // Run blocking, because the data store is always available right away.
        val (firstSettings, nextSettingsFlow) = dataStore.data
            .map { Triple(it.inputDevice, it.moonshineModel, it.sttPlaySound) }
            .distinctUntilChangedBlockingFirst()

        inputDeviceSetting = firstSettings.first
        moonshineModelSetting = normalizeMoonshineModel(firstSettings.second)
        sttPlaySoundSetting = firstSettings.third
        sttInputDevice = buildInputDevice(inputDeviceSetting)
        scope.launch { restartUiStateJob() }

        scope.launch {
            nextSettingsFlow.collect { (inputDevice, moonshineModel, sttPlaySound) ->
                sttPlaySoundSetting = sttPlaySound
                val normalizedModel = normalizeMoonshineModel(moonshineModel)
                if (inputDeviceSetting != inputDevice || moonshineModelSetting != normalizedModel) {
                    inputDeviceSetting = inputDevice
                    moonshineModelSetting = normalizedModel
                    changeInputDeviceTo(inputDevice)
                }
            }
        }
    }

    private fun normalizeMoonshineModel(model: MoonshineModel): MoonshineModel = when (model) {
        MoonshineModel.UNRECOGNIZED,
        MoonshineModel.MOONSHINE_MODEL_UNSET -> MoonshineModel.MOONSHINE_MODEL_BALANCED
        else -> model
    }

    private suspend fun changeInputDeviceTo(setting: InputDevice) {
        val prevSttInputDevice = sttInputDevice
        sttInputDevice = buildInputDevice(setting)
        prevSttInputDevice?.destroy()
        restartUiStateJob()
    }

    private fun buildInputDevice(setting: InputDevice): SttInputDevice? {
        return when (setting) {
            UNRECOGNIZED,
            INPUT_DEVICE_UNSET,
            // Keep the existing protobuf enum value for backwards-compatible settings migration;
            // in this fork the old Vosk slot is implemented by Moonshine.
            INPUT_DEVICE_VOSK -> MoonshineInputDevice(appContext, moonshineModelSetting)
            INPUT_DEVICE_EXTERNAL_POPUP ->
                ExternalPopupInputDevice(appContext, activityForResultManager, null)
            INPUT_DEVICE_NOTHING -> null
        }
    }

    private suspend fun restartUiStateJob() {
        uiStateJob?.cancel()
        val newSttInputDevice = sttInputDevice
        if (newSttInputDevice == null) {
            uiStateJob = null
            _uiState.emit(null)
        } else {
            uiStateJob = scope.launch {
                newSttInputDevice.uiState.collect {
                    _uiState.emit(it)
                    if (it == SttState.Listening) {
                        playSound(R.raw.listening_sound)
                    }
                }
            }
        }
    }

    private fun playSound(resid: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (sttPlaySoundSetting) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET -> AudioAttributes.USAGE_NOTIFICATION
                    SttPlaySound.STT_PLAY_SOUND_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    SttPlaySound.STT_PLAY_SOUND_ALARM -> AudioAttributes.USAGE_ALARM
                    SttPlaySound.STT_PLAY_SOUND_MEDIA -> AudioAttributes.USAGE_MEDIA
                    SttPlaySound.STT_PLAY_SOUND_NONE -> return
                }
            )
            .build()
        val mediaPlayer = MediaPlayer.create(appContext, resid, attributes, 0)
        mediaPlayer.setVolume(0.75f, 0.75f)
        mediaPlayer.start()
    }

    private fun wrapEventListener(eventListener: (InputEvent) -> Unit): (InputEvent) -> Unit = {
        if (it is InputEvent.None) {
            scope.launch { playSound(R.raw.listening_no_input_sound) }
        }
        eventListener(it)
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        return sttInputDevice?.tryLoad(
            if (thenStartListeningEventListener != null) wrapEventListener(thenStartListeningEventListener)
            else null
        ) ?: false
    }

    override fun stopListening() {
        sttInputDevice?.stopListening()
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        sttInputDevice?.onClick(wrapEventListener(eventListener))
    }

    override fun reinitializeToReleaseResources() {
        scope.launch { changeInputDeviceTo(inputDeviceSetting) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SttInputDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideInputDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        activityForResultManager: ActivityForResultManager,
    ): SttInputDeviceWrapper {
        return SttInputDeviceWrapperImpl(appContext, dataStore, activityForResultManager)
    }
}
