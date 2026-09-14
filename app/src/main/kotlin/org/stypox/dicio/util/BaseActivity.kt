package org.stypox.dicio.util

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.di.ActivityForResultManager
import org.stypox.dicio.di.LocaleManagerModule
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.theme.AppTheme
import java.util.Locale

abstract class BaseActivity : ComponentActivity() {

    @Inject
    lateinit var activityForResultManager: ActivityForResultManager
    private lateinit var launcher: ActivityResultLauncher<Intent>

    @Inject
    lateinit var dataStore: DataStore<UserSettings>

    protected var isRecreatingForLocaleChange: Boolean = false
        private set

    private fun setLocale(locale: Locale) {
        Locale.setDefault(locale)
        for (resources in sequenceOf(resources, applicationContext.resources)) {
            val configuration = resources.configuration
            configuration.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        // LocaleManager now has an immediate system-locale value and asynchronously applies the
        // persisted language, avoiding a blocking DataStore read on the Activity startup path.
        val localeManager = EntryPointAccessors
            .fromApplication(this, LocaleManagerModule::class.java)
            .getLocaleManager()
        setLocale(localeManager.locale.value)
        lifecycleScope.launch {
            localeManager.locale.drop(1).collect {
                isRecreatingForLocaleChange = true
                recreate()
            }
        }

        super.onCreate(savedInstanceState)
        launcher = activityForResultManager.addLauncher(this)
    }

    fun composeSetContent(content: @Composable () -> Unit) {
        setContent {
            val theme = remember {
                dataStore.data
                    .map { Pair(it.theme, it.dynamicColors) }
                    .distinctUntilChanged()
            }.collectAsState(
                // Render immediately with protobuf defaults, then update when DataStore emits.
                initial = Pair(Theme.THEME_SYSTEM, false)
            )

            AppTheme(
                theme = theme.value.first,
                dynamicColors = theme.value.second,
                content = content,
            )
        }
    }
}
