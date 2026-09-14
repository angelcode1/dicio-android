package org.stypox.dicio.io.input.stt_service

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState

@AndroidEntryPoint
class SttService : RecognitionService() {

    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper

    @Inject
    lateinit var localeManager: LocaleManager

    private val sessionActive = AtomicBoolean(false)

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (!sessionActive.compareAndSet(false, true)) {
            logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }

        val wantedLanguageExtra = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        if (wantedLanguageExtra != null && wantedLanguageExtra != "und") {
            val appLanguage = localeManager.locale.value.language
            val wantedLanguage = Locale.forLanguageTag(wantedLanguageExtra).language
            if (appLanguage != wantedLanguage) {
                sessionActive.set(false)
                Log.e(TAG, "Unsupported language: app=$appLanguage wanted=$wantedLanguageExtra")
                logRemoteExceptions { listener.error(ERROR_LANGUAGE_UNAVAILABLE) }
                return
            }
        }

        if (sttInputDevice.uiState.value.let {
                it == SttState.Listening || it == SttState.WaitingForResult
            }) {
            sessionActive.set(false)
            logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }

        var speechStarted = false
        val eventListener: (InputEvent) -> Unit = { inputEvent ->
            when (inputEvent) {
                is InputEvent.Error -> {
                    if (speechStarted) logRemoteExceptions { listener.endOfSpeech() }
                    sessionActive.set(false)
                    logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_SERVER) }
                }

                is InputEvent.Final -> {
                    if (!speechStarted) {
                        logRemoteExceptions { listener.beginningOfSpeech() }
                        speechStarted = true
                    }
                    logRemoteExceptions { listener.endOfSpeech() }

                    val results = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            ArrayList(inputEvent.utterances.map { it.first })
                        )
                        putFloatArray(
                            SpeechRecognizer.CONFIDENCE_SCORES,
                            inputEvent.utterances.map { it.second }.toFloatArray()
                        )
                    }
                    sessionActive.set(false)
                    logRemoteExceptions { listener.results(results) }
                }

                InputEvent.None -> {
                    if (speechStarted) logRemoteExceptions { listener.endOfSpeech() }
                    sessionActive.set(false)
                    logRemoteExceptions { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                }

                is InputEvent.Partial -> {
                    if (!speechStarted) {
                        logRemoteExceptions { listener.beginningOfSpeech() }
                        speechStarted = true
                    }

                    val partResult = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(inputEvent.utterance)
                        )
                    }
                    logRemoteExceptions { listener.partialResults(partResult) }
                }
            }
        }

        val recordingContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createCallerAttributionContext(listener)
        } else {
            this
        }
        val willStartListening = sttInputDevice.tryLoadWithRecordingContext(
            recordingContext,
            eventListener,
        )

        if (!willStartListening) {
            sessionActive.set(false)
            val error = if (sttInputDevice.uiState.value.let {
                    it == SttState.Listening || it == SttState.WaitingForResult
                }) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            } else {
                ERROR_LANGUAGE_UNAVAILABLE
            }
            Log.w(TAG, "Could not start STT recognizer, error=$error")
            logRemoteExceptions { listener.error(error) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createCallerAttributionContext(listener: Callback): Context {
        return createContext(
            ContextParams.Builder()
                .setNextAttributionSource(listener.callingAttributionSource)
                .build()
        )
    }

    override fun onCancel(listener: Callback) {
        if (sessionActive.getAndSet(false)) {
            sttInputDevice.stopListening()
        }
    }

    override fun onStopListening(listener: Callback) {
        if (sessionActive.get()) {
            sttInputDevice.stopListening()
        }
    }

    companion object {
        val TAG = SttService::class.simpleName

        val ERROR_LANGUAGE_UNAVAILABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        } else {
            SpeechRecognizer.ERROR_SERVER
        }

        fun logRemoteExceptions(f: () -> Unit) {
            try {
                f()
            } catch (e: RemoteException) {
                Log.e(TAG, "Remote exception", e)
            }
        }
    }
}
