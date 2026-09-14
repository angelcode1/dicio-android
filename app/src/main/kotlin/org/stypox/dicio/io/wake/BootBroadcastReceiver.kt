package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.stypox.dicio.di.WakeDeviceWrapper

@AndroidEntryPoint
class BootBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var wakeDevice: WakeDeviceWrapper

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Got intent ${intent.action}")

        if (ContextCompat.checkSelfPermission(context, RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Audio permission not granted")
            return
        }

        // WakeDeviceWrapper now initializes DataStore asynchronously to avoid blocking application
        // startup. A boot broadcast may be the first component created in this process, so wait for
        // that first snapshot before deciding whether wake-word recognition is enabled.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val initialized = withTimeoutOrNull(INITIALIZATION_TIMEOUT_MILLIS) {
                    wakeDevice.awaitInitialized()
                    true
                } == true
                if (!initialized) {
                    Log.e(TAG, "Timed out waiting for wake-device settings")
                    return@launch
                }
                handleInitializedWakeDevice(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleInitializedWakeDevice(context: Context) {
        when (wakeDevice.state.value) {
            WakeState.NotLoaded,
            WakeState.Loading,
            WakeState.Loaded -> {
                // Any of these states means wake-word recognition is enabled and the model is
                // already downloaded.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Starting from Android 11, microphone foreground-service restrictions mean we
                    // ask the user to start the service from a notification after boot.
                    Log.d(TAG, "Creating notification")
                    WakeService.createNotificationToStartLater(context)
                } else {
                    Log.d(TAG, "Starting service")
                    WakeService.start(context)
                }
            }
            else -> {
                Log.d(TAG, "Wrong wake device state: ${wakeDevice.state.value}")
            }
        }
    }

    companion object {
        val TAG = BootBroadcastReceiver::class.simpleName
        private const val INITIALIZATION_TIMEOUT_MILLIS = 8_000L
    }
}
