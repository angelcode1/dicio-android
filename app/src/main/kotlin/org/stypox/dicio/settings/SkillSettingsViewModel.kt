package org.stypox.dicio.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.settings.datastore.UserSettings

@HiltViewModel
class SkillSettingsViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<UserSettings>,
    val skillContext: SkillContextInternal,
    private val skillHandler: SkillHandler,
) : AndroidViewModel(application) {

    val skills: List<SkillInfo> get() = skillHandler.allSkillInfoList

    val enabledSkills: StateFlow<Map<String, Boolean>> = dataStore.data
        .map { it.enabledSkillsMap }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap(),
        )

    val numberLibraryNotAvailable = skillContext.parserFormatter == null

    fun setSkillEnabled(id: String, state: Boolean) {
        viewModelScope.launch {
            dataStore.updateData {
                it.toBuilder()
                    .putEnabledSkills(id, state)
                    .build()
            }
        }
    }
}
