package org.stypox.dicio.di

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityForResultManager @Inject constructor() {
    private val launchers: ArrayList<WeakReference<ActivityResultLauncher<Intent>>> = ArrayList()
    private var callback: ((ActivityResult) -> Unit)? = null

    fun addLauncher(activity: ComponentActivity): ActivityResultLauncher<Intent> {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
            ::handleResult
        )

        synchronized(launchers) {
            launchers.removeAll { it.get() == null }
            launchers.add(WeakReference(launcher))
        }
        return launcher
    }

    fun launch(input: Intent, newCallback: (ActivityResult) -> Unit): Boolean {
        // The newest live Activity is the best owner during configuration recreation.
        val launcher = synchronized(launchers) {
            launchers.removeAll { it.get() == null }
            launchers.asReversed().firstNotNullOfOrNull { it.get() }
        } ?: return false

        synchronized(this) {
            if (callback != null) {
                throw IllegalStateException("An activity for result request is already active")
            }
            callback = newCallback
        }

        try {
            launcher.launch(input)
        } catch (throwable: Throwable) {
            // Roll back the reservation so one failed/stale launcher cannot poison all future calls.
            synchronized(this) {
                if (callback === newCallback) callback = null
            }
            throw throwable
        }
        return true
    }

    private fun handleResult(activityResult: ActivityResult) {
        // Clear ownership before invoking app code. If the callback throws or immediately starts a
        // second request, the manager is already in a consistent state and no monitor is held.
        val resultCallback = synchronized(this) {
            callback.also { callback = null }
        }
        resultCallback?.invoke(activityResult)
    }
}
