package org.stypox.dicio.skills.timer

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import java.time.Duration
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Timer
import org.stypox.dicio.util.StringUtils
import org.stypox.dicio.util.getString

// TODO cleanup this skill and use a service to manage timers
class TimerSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Timer>,
    private val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
) : StandardRecognizerSkill<Timer>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Timer): SkillOutput {
        return when (inputData) {
            is Timer.Set -> {
                if (inputData.duration == null) {
                    TimerOutput.SetAskDuration { setTimer(ctx, it, inputData.name) }
                } else {
                    setTimer(ctx, inputData.duration.toJavaDuration(), inputData.name)
                }
            }
            is Timer.Query -> queryTimer(ctx, inputData.name)
            is Timer.Cancel -> {
                if (inputData.name == null && timerCount() > 1) {
                    TimerOutput.ConfirmCancel(yesNoData) { cancelTimer(ctx, null) }
                } else {
                    cancelTimer(ctx, inputData.name)
                }
            }
        }
    }

    private suspend fun setTimer(
        ctx: SkillContext,
        duration: Duration,
        name: String?,
    ): SkillOutput {
        var ringtone: Ringtone? = null

        val setTimer = withContext(Dispatchers.Main) {
            SetTimer(
                duration = duration,
                name = name,
                onMillisTickCallback = { milliseconds ->
                    if (milliseconds < 0 && ringtone?.isPlaying == false) {
                        ringtone?.play()
                    }
                },
                onSecondsTickCallback = { seconds ->
                    if (seconds <= 5) {
                        ctx.speechOutputDevice.speak(
                            ctx.parserFormatter!!
                                .pronounceNumber(seconds.toDouble())
                                .get()
                        )
                    }
                },
                onExpiredCallback = { theName ->
                    // initialize ringtone when the timer has expired (play will be called right after)
                    ringtone = RingtoneManager.getActualDefaultRingtoneUri(
                        ctx.android, RingtoneManager.TYPE_ALARM
                    )
                        ?.let { RingtoneManager.getRingtone(ctx.android, it) }
                        ?.also {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                // on older API versions it is looped manually in onMillisTickCallback
                                it.isLooping = true
                            }
                            it.play()
                        }

                    if (ringtone == null) {
                        // we could not load a ringtone, so we can announce via speech instead
                        ctx.speechOutputDevice.speak(
                            formatStringWithName(
                                ctx.android,
                                theName,
                                R.string.skill_timer_expired,
                                R.string.skill_timer_expired_name
                            )
                        )
                    }
                },
                onCancelCallback = { timerToCancel ->
                    ringtone?.stop()
                    ringtone = null
                    synchronized(SET_TIMERS) {
                        SET_TIMERS.removeIf { timer -> timer === timerToCancel }
                    }
                }
            )
        }

        synchronized(SET_TIMERS) { SET_TIMERS.add(setTimer) }

        return TimerOutput.Set(
            duration.toMillis(),
            setTimer.lastTickMillisState,
            name,
        )
    }

    private fun cancelTimer(ctx: SkillContext, name: String?): SkillOutput {
        val timers = timersSnapshot()
        val message: String
        if (timers.isEmpty()) {
            message = ctx.android.getString(R.string.skill_timer_no_active)
        } else if (name == null) {
            message = if (timers.size == 1) {
                formatStringWithName(
                    ctx.android,
                    timers[0].name,
                    R.string.skill_timer_canceled,
                    R.string.skill_timer_canceled_name
                )
            } else {
                ctx.getString(R.string.skill_timer_all_canceled)
            }

            // Work from a stable snapshot: cancel() invokes a callback that removes the timer from
            // the shared list, potentially on another thread.
            for (setTimer in timers) {
                setTimer.cancel()
            }
            if (timerCount() != 0) {
                Log.w(TAG, "Calling cancel() on all timers did not remove them all from the list")
                synchronized(SET_TIMERS) { SET_TIMERS.clear() }
            }
        } else {
            val setTimer = getSetTimerWithSimilarName(name)
            if (setTimer == null) {
                message = ctx.android.getString(R.string.skill_timer_no_active_name, name)
            } else {
                message = ctx.android.getString(
                    R.string.skill_timer_canceled_name,
                    setTimer.name,
                )
                setTimer.cancel()
            }
        }

        return TimerOutput.Cancel(message)
    }

    private fun queryTimer(ctx: SkillContext, name: String?): SkillOutput {
        val timers = timersSnapshot()
        val message = if (timers.isEmpty()) {
            ctx.getString(R.string.skill_timer_no_active)
        } else if (name == null) {
            // no name provided by the user: query the last timer, but adapt the message if only one
            val lastTimer = timers.last()
            @StringRes val noNameQueryString: Int = if (timers.size == 1)
                R.string.skill_timer_query
            else
                R.string.skill_timer_query_last

            formatStringWithName(
                ctx,
                lastTimer.name,
                lastTimer.lastTickMillis,
                noNameQueryString,
                R.string.skill_timer_query_name
            )
        } else {
            val setTimer = getSetTimerWithSimilarName(name)
            if (setTimer == null) {
                ctx.getString(R.string.skill_timer_no_active_name, name)
            } else {
                ctx.getString(
                    R.string.skill_timer_query_name, setTimer.name,
                    getFormattedDuration(ctx.parserFormatter!!, setTimer.lastTickMillis, true)
                )
            }
        }

        return TimerOutput.Query(message)
    }

    private fun getSetTimerWithSimilarName(name: String): SetTimer? {
        class Pair(val setTimer: SetTimer, val distance: Int)
        return timersSnapshot()
            .mapNotNull { setTimer: SetTimer ->
                setTimer.name?.let { timerName ->
                    Pair(setTimer, StringUtils.customStringDistance(name, timerName))
                }
            }
            .filter { pair -> pair.distance < 6 }
            .minByOrNull { pair -> pair.distance }
            ?.setTimer
    }

    companion object {
        val SET_TIMERS: MutableList<SetTimer> = Collections.synchronizedList(ArrayList())
        val TAG: String = TimerSkill::class.simpleName!!

        private fun timersSnapshot(): List<SetTimer> =
            synchronized(SET_TIMERS) { SET_TIMERS.toList() }

        private fun timerCount(): Int = synchronized(SET_TIMERS) { SET_TIMERS.size }
    }
}
