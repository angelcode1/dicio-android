package org.stypox.dicio

import android.Manifest
import android.content.Intent
import android.content.Intent.ACTION_ASSIST
import android.content.Intent.ACTION_VOICE_COMMAND
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shreyaspatil.permissionFlow.PermissionFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.io.wake.WakeState.Loaded
import org.stypox.dicio.io.wake.WakeState.Loading
import org.stypox.dicio.io.wake.WakeState.NotLoaded
import org.stypox.dicio.ui.home.wakeWordPermissions
import org.stypox.dicio.ui.nav.Navigation
import org.stypox.dicio.util.BaseActivity

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject lateinit var skillEvaluator: SkillEvaluator
    @Inject lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject lateinit var wakeDevice: WakeDeviceWrapper

    private var sttPermissionJob: Job? = null
    private var wakeServiceJob: Job? = null
    private var nextAssistAllowedElapsed = 0L

    private fun onAssistIntentReceived() {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextAssistAllowedElapsed) {
            nextAssistAllowedElapsed = now + INTENT_BACKOFF_MILLIS
            Log.d(TAG, "Received assist intent")
            sttInputDevice.tryLoad(skillEvaluator::processInputEvent)
        } else {
            Log.w(TAG, "Ignoring duplicate assist intent")
        }
    }

    private fun handleWakeWordTurnOnScreen(intent: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            intent?.action == ACTION_WAKE_WORD
        ) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        WakeService.cancelTriggeredNotification(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeWordTurnOnScreen(intent)
        if (isAssistIntent(intent)) onAssistIntentReceived()
    }

    override fun onStart() {
        super.onStart()
        foregroundCount.incrementAndGet()
    }

    override fun onStop() {
        super.onStop()
        foregroundCount.decrementAndGet()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdCount.incrementAndGet()

        handleWakeWordTurnOnScreen(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        } else if (intent.action != ACTION_WAKE_WORD) {
            sttInputDevice.tryLoad(null)
        }

        WakeService.start(this)
        wakeServiceJob?.cancel()
        wakeServiceJob = lifecycleScope.launch {
            wakeDevice.state
                .map { it == NotLoaded || it == Loading || it == Loaded }
                .combine(
                    PermissionFlow.getInstance().getMultiplePermissionState(*wakeWordPermissions)
                ) { wakeState, permGranted ->
                    wakeState && permGranted.allGranted
                }
                .distinctUntilChanged()
                .filter { it }
                .collect { WakeService.start(this@MainActivity) }
        }

        sttPermissionJob?.cancel()
        sttPermissionJob = lifecycleScope.launch {
            PermissionFlow.getInstance().getPermissionState(Manifest.permission.RECORD_AUDIO)
                .drop(1)
                .filter { it.isGranted }
                .collect { sttInputDevice.tryLoad(null) }
        }

        composeSetContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.safeDrawingPadding()) {
                    Navigation()
                }
            }
        }
    }

    override fun onDestroy() {
        // Preserve expensive STT models across configuration/locale recreation. If the Activity is
        // actually going away, release resources that the background wake service does not need.
        if (!isChangingConfigurations && !isRecreatingForLocaleChange) {
            sttInputDevice.reinitializeToReleaseResources()
        }
        createdCount.decrementAndGet()
        super.onDestroy()
    }

    companion object {
        private const val INTENT_BACKOFF_MILLIS = 100L
        private val TAG = MainActivity::class.simpleName
        const val ACTION_WAKE_WORD = "org.stypox.dicio.MainActivity.ACTION_WAKE_WORD"

        private val foregroundCount = AtomicInteger(0)
        private val createdCount = AtomicInteger(0)

        val isInForeground: Int
            get() = foregroundCount.get()
        val isCreated: Int
            get() = createdCount.get()

        private fun isAssistIntent(intent: Intent?): Boolean {
            return when (intent?.action) {
                ACTION_ASSIST, ACTION_VOICE_COMMAND -> true
                else -> false
            }
        }
    }
}
