package org.stypox.dicio.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.io.wake.oww.OpenWakeWordDevice
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.WakeDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.WakeDevice.WAKE_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.WakeDevice.WAKE_DEVICE_OWW
import org.stypox.dicio.settings.datastore.WakeDevice.WAKE_DEVICE_UNSET
import javax.inject.Singleton

interface WakeDeviceWrapper {
    val state: StateFlow<WakeState?>
    val isHeyDicio: StateFlow<Boolean>
    suspend fun awaitInitialized()
    fun download()
    fun processFrame(audio16bitPcm: ShortArray): Boolean
    fun frameSize(): Int
    fun reinitialize()
    fun reinitializeToReleaseResources()
}

typealias DataStoreWakeDevice = org.stypox.dicio.settings.datastore.WakeDevice

class WakeDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val okHttpClient: OkHttpClient,
) : WakeDeviceWrapper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deviceLock = Any()
    private val initialized = CompletableDeferred<Unit>()

    private var currentSetting: DataStoreWakeDevice = WAKE_DEVICE_NOTHING
    private var lastFrameHadWrongSize = false

    private val _state: MutableStateFlow<WakeState?> = MutableStateFlow(null)
    override val state: StateFlow<WakeState?> = _state
    private val _isHeyDicio = MutableStateFlow(true)
    override val isHeyDicio: StateFlow<Boolean> = _isHeyDicio
    private val currentDevice = MutableStateFlow<WakeDevice?>(null)

    init {
        scope.launch {
            currentDevice.collectLatest { newWakeDevice ->
                if (newWakeDevice == null) {
                    _state.emit(null)
                } else {
                    newWakeDevice.state.collect { _state.emit(it) }
                }
            }
        }

        scope.launch {
            dataStore.data
                .map { it.wakeDevice }
                .distinctUntilChanged()
                .collect { setting ->
                    changeWakeDeviceTo(setting)
                    if (!initialized.isCompleted) initialized.complete(Unit)
                }
        }
    }

    override suspend fun awaitInitialized() {
        initialized.await()
    }

    private fun changeWakeDeviceTo(setting: DataStoreWakeDevice) {
        Log.d(TAG, "changeWakeDeviceTo($setting) called")
        synchronized(deviceLock) {
            currentSetting = setting
            replaceWakeDeviceLocked(setting)
        }
    }

    /** Must be called while holding [deviceLock]. */
    private fun replaceWakeDeviceLocked(setting: DataStoreWakeDevice) {
        val previous = currentDevice.value
        val replacement = buildInputDevice(setting)
        lastFrameHadWrongSize = false

        // Publish the replacement's current state synchronously before currentDevice wakes the
        // collector. awaitInitialized() can therefore guarantee that state.value is already valid.
        _isHeyDicio.value = replacement?.isHeyDicio() ?: true
        _state.value = replacement?.state?.value
        currentDevice.value = replacement

        // processFrame() uses the same lock, so no inference can still be using previous here.
        previous?.destroy()
    }

    private fun buildInputDevice(setting: DataStoreWakeDevice): WakeDevice? {
        return when (setting) {
            UNRECOGNIZED,
            WAKE_DEVICE_UNSET,
            WAKE_DEVICE_OWW -> OpenWakeWordDevice(appContext, okHttpClient)
            WAKE_DEVICE_NOTHING -> null
        }
    }

    override fun download() {
        synchronized(deviceLock) { currentDevice.value?.download() }
    }

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        return synchronized(deviceLock) {
            val device = currentDevice.value
                ?: throw IllegalArgumentException("No wake word device is enabled")

            if (audio16bitPcm.size != device.frameSize()) {
                if (lastFrameHadWrongSize) {
                    throw IllegalArgumentException(
                        "Wrong audio frame size: expected ${device.frameSize()} samples " +
                            "but got ${audio16bitPcm.size}"
                    )
                }
                lastFrameHadWrongSize = true
                false
            } else {
                lastFrameHadWrongSize = false
                device.processFrame(audio16bitPcm)
            }
        }
    }

    override fun frameSize(): Int {
        return synchronized(deviceLock) { currentDevice.value?.frameSize() ?: 0 }
    }

    override fun reinitialize() {
        synchronized(deviceLock) {
            replaceWakeDeviceLocked(currentSetting)
        }
    }

    override fun reinitializeToReleaseResources() {
        synchronized(deviceLock) {
            if (currentDevice.value?.isOccupyingResources() == true) {
                replaceWakeDeviceLocked(currentSetting)
            }
        }
    }

    companion object {
        const val TAG: String = "WakeDeviceWrapper"
    }
}

@Module
@InstallIn(SingletonComponent::class)
class WakeDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideWakeDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        okHttpClient: OkHttpClient,
    ): WakeDeviceWrapper {
        return WakeDeviceWrapperImpl(appContext, dataStore, okHttpClient)
    }
}
