package org.stypox.dicio.io.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import java.util.Locale
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.R

class AndroidTtsSpeechDevice(private var context: Context, locale: Locale) : SpeechOutputDevice {
    private val stateLock = Any()
    private var textToSpeech: TextToSpeech? = null
    @Volatile private var initializedCorrectly = false
    private val runnablesWhenFinished: MutableList<Runnable> = ArrayList()
    private val pendingUtteranceIds: MutableSet<String> = LinkedHashSet()
    private var lastUtteranceId = 0

    init {
        textToSpeech = TextToSpeech(context) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.run {
                    val errorCode = setLanguage(locale)
                    if (errorCode >= 0) { // errors are -1 or -2
                        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) = Unit

                            override fun onDone(utteranceId: String) {
                                finishUtterance(utteranceId)
                            }

                            override fun onStop(utteranceId: String, interrupted: Boolean) {
                                finishUtterance(utteranceId)
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                finishUtterance(utteranceId)
                            }

                            override fun onError(utteranceId: String, errorCode: Int) {
                                finishUtterance(utteranceId)
                            }
                        })
                        initializedCorrectly = true
                    } else {
                        Log.e(TAG, "Unsupported language: $errorCode")
                        handleInitializationError(R.string.android_tts_unsupported_language)
                    }
                }
            } else {
                Log.e(TAG, "TTS error: $status")
                handleInitializationError(R.string.android_tts_error)
            }
        }
    }

    override fun speak(speechOutput: String) {
        var utteranceId: String? = null
        var speakResult = TextToSpeech.ERROR
        val shouldToast = synchronized(stateLock) {
            val tts = textToSpeech
            if (!initializedCorrectly || tts == null) {
                true
            } else {
                lastUtteranceId += 1
                utteranceId = "dicio_$lastUtteranceId"
                pendingUtteranceIds.add(utteranceId!!)
                speakResult = tts.speak(
                    speechOutput,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId,
                )
                false
            }
        }

        if (shouldToast) {
            Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
        } else if (speakResult == TextToSpeech.ERROR) {
            Log.e(TAG, "Could not enqueue TTS utterance")
            finishUtterance(utteranceId!!)
        }
    }

    override fun stopSpeaking() {
        synchronized(stateLock) { textToSpeech }?.stop()
        finishAllUtterances()
    }

    override val isSpeaking: Boolean
        get() = synchronized(stateLock) {
            pendingUtteranceIds.isNotEmpty() || textToSpeech?.isSpeaking == true
        }

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        val runNow = synchronized(stateLock) {
            if (pendingUtteranceIds.isNotEmpty()) {
                runnablesWhenFinished.add(runnable)
                false
            } else {
                true
            }
        }
        if (runNow) runnable.run()
    }

    override fun cleanup() {
        val tts = synchronized(stateLock) {
            initializedCorrectly = false
            textToSpeech.also { textToSpeech = null }
        }
        tts?.shutdown()
        finishAllUtterances()
    }

    private fun finishUtterance(utteranceId: String) {
        val runnables = synchronized(stateLock) {
            pendingUtteranceIds.remove(utteranceId)
            if (pendingUtteranceIds.isEmpty()) drainFinishedRunnablesLocked() else emptyList()
        }
        runnables.forEach(Runnable::run)
    }

    private fun finishAllUtterances() {
        val runnables = synchronized(stateLock) {
            pendingUtteranceIds.clear()
            drainFinishedRunnablesLocked()
        }
        runnables.forEach(Runnable::run)
    }

    /** Must be called while holding [stateLock]. */
    private fun drainFinishedRunnablesLocked(): List<Runnable> {
        if (runnablesWhenFinished.isEmpty()) return emptyList()
        return runnablesWhenFinished.toList().also { runnablesWhenFinished.clear() }
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show()
        cleanup()
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!
    }
}
