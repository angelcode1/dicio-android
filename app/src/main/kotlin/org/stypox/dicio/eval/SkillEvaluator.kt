package org.stypox.dicio.eval

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer
import javax.inject.Singleton

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: SkillHandler,
    private val sttInputDevice: SttInputDeviceWrapper,
) : SkillEvaluator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputEvents = Channel<InputEvent>(Channel.UNLIMITED)

    private val skillRanker: SkillRanker
        get() = skillHandler.skillRanker.value

    private val _state = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )
    override val state: StateFlow<InteractionLog> = _state

    // Must be kept up to date even when the activity is recreated.
    @Volatile
    override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }

    init {
        // Input events form one conversation state machine. Process them strictly in arrival order
        // so partial/final events cannot race while mutating SkillRanker, SkillContext or the log.
        scope.launch {
            for (event in inputEvents) {
                try {
                    suspendProcessInputEvent(event)
                } catch (throwable: Throwable) {
                    addErrorInteractionFromPending(throwable)
                }
            }
        }
    }

    override fun processInputEvent(event: InputEvent) {
        if (inputEvents.trySend(event).isFailure) {
            Log.e(TAG, "Could not enqueue input event: $event")
        }
    }

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            is InputEvent.Error -> {
                addErrorInteractionFromPending(event.throwable)
            }
            is InputEvent.Final -> {
                if (event.utterances.isEmpty()) {
                    addErrorInteractionFromPending(
                        IllegalArgumentException("Final input event contained no utterances")
                    )
                    return
                }

                // SkillHandler initializes asynchronously from DataStore. Do not accidentally use
                // its fallback-only bootstrap ranker for a real user request.
                skillHandler.awaitInitialized()

                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterances.first().first,
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
                evaluateMatchingSkill(event.utterances.map { it.first })
            }
            InputEvent.None -> {
                _state.value = _state.value.copy(pendingQuestion = null)
            }
            is InputEvent.Partial -> {
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        // The next input can be a continuation of the last interaction only if the
                        // last skill invocation provided some skill batches.
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
            }
        }
    }

    private suspend fun evaluateMatchingSkill(utterances: List<String>) {
        val (chosenInput, chosenSkill) = try {
            utterances.firstNotNullOfOrNull { input: String ->
                skillContext.standardMatchHelper = MatchHelper(skillContext.parserFormatter, input)
                skillRanker.getBest(skillContext, input)?.let { skillWithResult ->
                    Pair(input, skillWithResult)
                }
            } ?: Pair(utterances.first(), skillRanker.getFallbackSkill(skillContext, utterances.first()))
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            return
        } finally {
            // standardMatchHelper only needs to be set while calling score() on skills, so once
            // all matching and scoring is done, free up the memory it uses.
            skillContext.standardMatchHelper = null
        }
        val skillInfo = chosenSkill.skill.correspondingSkillInfo

        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = chosenInput,
                continuesLastInteraction = skillRanker.hasAnyBatches(),
                skillBeingEvaluated = skillInfo,
            )
        )

        try {
            val permissions = skillInfo.neededPermissions
            if (permissions.isNotEmpty() && !permissionRequester(permissions)) {
                addInteractionFromPending(MissingPermissionsSkillOutput(skillInfo))
                return
            }

            skillContext.previousOutput =
                _state.value.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
            val output = chosenSkill.generateOutput(skillContext)

            val interactionPlan = output.getInteractionPlan(skillContext)
            addInteractionFromPending(output)
            output.getSpeechOutput(skillContext).let {
                if (it.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        skillContext.speechOutputDevice.speak(it)
                    }
                }
            }

            when (interactionPlan) {
                InteractionPlan.FinishInteraction -> skillRanker.removeAllBatches()
                is InteractionPlan.FinishSubInteraction -> skillRanker.removeTopBatch()
                is InteractionPlan.Continue -> Unit
                is InteractionPlan.StartSubInteraction -> {
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
                is InteractionPlan.ReplaceSubInteraction -> {
                    skillRanker.removeTopBatch()
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
            }

            if (interactionPlan.reopenMicrophone) {
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    sttInputDevice.tryLoad(this::processInputEvent)
                }
            }
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
        }
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
        Log.e(TAG, "Error while evaluating skills", throwable)
        addInteractionFromPending(ErrorSkillOutput(throwable, true))
    }

    private fun addInteractionFromPending(skillOutput: SkillOutput) {
        val log = _state.value
        val pendingUserInput = log.pendingQuestion?.userInput
        val pendingContinuesLastInteraction = log.pendingQuestion?.continuesLastInteraction
            ?: skillRanker.hasAnyBatches()
        val pendingSkill = log.pendingQuestion?.skillBeingEvaluated
        val questionAnswer = QuestionAnswer(pendingUserInput, skillOutput)

        val interactions = log.interactions.toMutableList().also { inters ->
            if (pendingContinuesLastInteraction && inters.isNotEmpty()) {
                inters[inters.size - 1] = inters[inters.size - 1].let { interaction ->
                    interaction.copy(
                        questionsAnswers = interaction.questionsAnswers.toMutableList()
                            .apply { add(questionAnswer) }
                            .takeLast(MAX_QUESTIONS_PER_INTERACTION)
                    )
                }
            } else {
                inters.add(
                    Interaction(
                        skill = pendingSkill,
                        questionsAnswers = listOf(questionAnswer)
                    )
                )
            }
        }.takeLast(MAX_INTERACTIONS)

        _state.value = log.copy(
            interactions = interactions,
            pendingQuestion = null,
        )
    }

    companion object {
        val TAG = SkillEvaluator::class.simpleName
        private const val MAX_INTERACTIONS = 100
        private const val MAX_QUESTIONS_PER_INTERACTION = 100
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SkillEvaluatorModule {
    @Provides
    @Singleton
    fun provideSkillEvaluator(
        skillContext: SkillContextInternal,
        skillHandler: SkillHandler,
        sttInputDevice: SttInputDeviceWrapper,
    ): SkillEvaluator {
        return SkillEvaluatorImpl(skillContext, skillHandler, sttInputDevice)
    }
}
