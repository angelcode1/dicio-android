package org.stypox.dicio.eval

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.fallback.text.TextFallbackInfo
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.weather.WeatherInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillHandler @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val skillContext: SkillContextInternal,
) {
    // TODO improve id handling (maybe just use an int that can point to an Android resource)
    val allSkillInfoList = listOf(
        WeatherInfo,
        SearchInfo,
        LyricsInfo,
        OpenInfo,
        CalculatorInfo,
        NavigationInfo,
        TelephoneInfo,
        TimerInfo,
        CurrentTimeInfo,
        MediaInfo,
        JokeInfo,
        ListeningInfo(dataStore),
        TranslationInfo,
        NotifyInfo,
        FlashlightInfo,
    )

    private val fallbackSkillInfoList = listOf(
        TextFallbackInfo,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialized = CompletableDeferred<Unit>()

    // Will be null until the first settings/locale snapshot has been applied.
    private val _enabledSkillsInfo: MutableStateFlow<List<SkillInfo>?> = MutableStateFlow(null)
    val enabledSkillsInfo: StateFlow<List<SkillInfo>?> = _enabledSkillsInfo

    private val _skillRanker = MutableStateFlow(
        // Bootstrap value only. SkillEvaluator waits for awaitInitialized() before evaluating finals.
        SkillRanker(listOf(), fallbackSkillInfoList[0].build(skillContext)!!)
    )
    val skillRanker: StateFlow<SkillRanker> = _skillRanker

    init {
        scope.launch {
            localeManager.locale
                .combine(dataStore.data) { locale, data -> Pair(locale, data.enabledSkillsMap) }
                .distinctUntilChanged()
                .collectLatest { (_, enabledSkills) ->
                    // Locale is intentionally not read here: skills use the sections locale.
                    val newEnabledSkillsInfo = allSkillInfoList
                        .filter { enabledSkills.getOrDefault(it.id, true) }
                        .mapNotNull { info ->
                            info.build(skillContext)?.let { skill -> Pair(info, skill) }
                        }

                    _skillRanker.value = SkillRanker(
                        newEnabledSkillsInfo.map { (_, skill) -> skill },
                        fallbackSkillInfoList[0].build(skillContext)!!,
                    )
                    _enabledSkillsInfo.value = newEnabledSkillsInfo.map { (info, _) -> info }
                    if (!initialized.isCompleted) initialized.complete(Unit)
                }
        }
    }

    suspend fun awaitInitialized() {
        initialized.await()
    }

    companion object {
        fun newForPreviews(context: Context): SkillHandler {
            return SkillHandler(
                UserSettingsModule.newDataStoreForPreviews(),
                LocaleManager.newForPreviews(context),
                SkillContextImpl.newForPreviews(context),
            )
        }
    }
}
