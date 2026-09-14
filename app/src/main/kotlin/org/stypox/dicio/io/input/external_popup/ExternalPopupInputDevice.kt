package org.stypox.dicio.io.input.external_popup

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.result.ActivityResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.di.ActivityForResultManager
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState

class ExternalPopupInputDevice(
    @param:ApplicationContext val context: Context,
    private val activityForResultManager: ActivityForResultManager,
    localeManager: LocaleManager,
) : SttInputDevice {

    @Volatile
    private var locale: Locale = localeManager.locale.value

    private val localeWithCountry: Locale
        get() = if (locale.country.isEmpty()) {
            Locale.getAvailableLocales()
                .firstOrNull { it.language == locale.language && it.country.isNotEmpty() }
                ?: locale
        } else {
            locale
        }

    private val destroyed = AtomicBoolean(false)
    private val _state = MutableStateFlow(stateFromResolveActivity())
    private val _uiState = MutableStateFlow(_state.value.toUiState())
    override val uiState: StateFlow<SttState> = _uiState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            _state.collect { _uiState.value = it.toUiState() }
        }

        scope.launch {
            // StateFlow already supplies the current locale above. Re-resolve only on later changes,
            // and never overwrite an in-flight request while waiting for its Activity result.
            localeManager.locale.drop(1).collect { newLocale ->
                locale = newLocale
                _state.update { current ->
                    if (current is ExternalPopupState.WaitingForResult) current
                    else stateFromResolveActivity()
                }
            }
        }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (destroyed.get()) return false
        if (thenStartListeningEventListener != null) {
            return startListening(thenStartListeningEventListener)
        }
        return when (_state.value) {
            ExternalPopupState.NotAvailable, is ExternalPopupState.ErrorStartingActivity -> false
            else -> true
        }
    }

    override fun stopListening() {
        // An external recognition Activity owns its recording lifecycle.
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        startListening(eventListener)
    }

    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        scope.cancel()
    }

    private fun getIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeWithCountry.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.stt_say_something))
        }
    }

    private fun stateFromResolveActivity(): ExternalPopupState {
        return if (getIntent().resolveActivity(context.packageManager) == null) {
            ExternalPopupState.NotAvailable
        } else {
            ExternalPopupState.Available
        }
    }

    private fun startListening(eventListener: (InputEvent) -> Unit): Boolean {
        if (destroyed.get()) return false

        val waiting = ExternalPopupState.WaitingForResult(eventListener)
        while (true) {
            val current = _state.value
            when (current) {
                ExternalPopupState.NotAvailable, is ExternalPopupState.WaitingForResult -> return false
                ExternalPopupState.Available,
                is ExternalPopupState.ErrorStartingActivity,
                is ExternalPopupState.ErrorActivityResult -> {
                    if (_state.compareAndSet(current, waiting)) break
                }
            }
        }

        return try {
            if (activityForResultManager.launch(getIntent(), this::onActivityResult)) {
                true
            } else {
                val error = IllegalStateException("No active Activity is available to launch STT")
                _state.compareAndSet(waiting, ExternalPopupState.ErrorStartingActivity(error))
                false
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "Could not start STT activity", throwable)
            _state.compareAndSet(waiting, ExternalPopupState.ErrorStartingActivity(throwable))
            false
        }
    }

    private fun onActivityResult(result: ActivityResult) {
        val waiting = _state.value as? ExternalPopupState.WaitingForResult ?: return
        val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val confidences = result.data?.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)

        if (result.resultCode == RESULT_OK && !results.isNullOrEmpty()) {
            _state.compareAndSet(waiting, stateFromResolveActivity())
            if (results.size == confidences?.size) {
                waiting.listener(InputEvent.Final(results.zip(confidences.toList())))
            } else {
                waiting.listener(InputEvent.Final(results.map { Pair(it, 1.0f) }))
            }
        } else if (result.resultCode == RESULT_CANCELED) {
            _state.compareAndSet(waiting, stateFromResolveActivity())
            waiting.listener(InputEvent.None)
        } else {
            _state.compareAndSet(
                waiting,
                ExternalPopupState.ErrorActivityResult(result.resultCode)
            )
            waiting.listener(InputEvent.Error(ResultCodeException(result.resultCode)))
        }
    }

    companion object {
        val TAG = ExternalPopupInputDevice::class.simpleName
    }
}
