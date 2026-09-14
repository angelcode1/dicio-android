package org.stypox.dicio.di

import android.content.Context
import android.util.Log
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule.Companion.newDataStoreForPreviews
import org.stypox.dicio.util.LocaleUtils

/**
 * Chooses a supported locale from the user's configured language and system locale list.
 */
@Singleton
class LocaleManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
) {
    // Capture the real system locales before BaseActivity applies Dicio's resolved locale.
    private val systemLocaleList: LocaleListCompat =
        ConfigurationCompat.getLocales(appContext.resources.configuration)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _locale: MutableStateFlow<Locale>
    val locale: StateFlow<Locale>
    private val _sentencesLanguage: MutableStateFlow<String>
    val sentencesLanguage: StateFlow<String>

    init {
        // Start immediately from the system language instead of blocking the main thread on
        // DataStore. If the user selected a different language, the first DataStore emission below
        // updates these flows and BaseActivity recreates itself with the persisted choice.
        val initialResolutionResult = getSentencesLocale(Language.LANGUAGE_SYSTEM)
        _locale = MutableStateFlow(initialResolutionResult.availableLocale)
        locale = _locale
        _sentencesLanguage = MutableStateFlow(initialResolutionResult.supportedLocaleString)
        sentencesLanguage = _sentencesLanguage

        scope.launch {
            dataStore.data
                .map { it.language }
                .distinctUntilChanged()
                .collect { newLanguage ->
                    val resolutionResult = getSentencesLocale(newLanguage)
                    _locale.value = resolutionResult.availableLocale
                    _sentencesLanguage.value = resolutionResult.supportedLocaleString
                }
        }
    }

    private fun getSentencesLocale(language: Language): LocaleUtils.LocaleResolutionResult {
        return try {
            LocaleUtils.resolveSupportedLocaleOrThrow(
                getAvailableLocalesFromLanguage(language),
                Sentences.languages
            )
        } catch (e: LocaleUtils.UnsupportedLocaleException) {
            Log.w(TAG, "Current locale is not supported, defaulting to English", e)
            LocaleUtils.LocaleResolutionResult(
                availableLocale = Locale.ENGLISH,
                supportedLocaleString = "en",
            )
        }
    }

    private fun getAvailableLocalesFromLanguage(language: Language): LocaleListCompat {
        return when (language) {
            Language.LANGUAGE_SYSTEM,
            Language.UNRECOGNIZED -> systemLocaleList
            else -> LocaleListCompat.create(
                LocaleUtils.parseLanguageCountry(
                    language.toString().removePrefix("LANGUAGE_")
                )
            )
        }
    }

    companion object {
        val TAG = LocaleManager::class.simpleName

        fun newForPreviews(context: Context): LocaleManager {
            return LocaleManager(
                context,
                newDataStoreForPreviews(),
            )
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleManagerModule {
    fun getLocaleManager(): LocaleManager
}
