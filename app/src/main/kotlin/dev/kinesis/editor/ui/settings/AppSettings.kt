package dev.kinesis.editor.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class ThemeMode(val id: String) {
    DARK("dark"), LIGHT("light"), SYSTEM("system");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: DARK
    }
}

/** Interface language: the system's, or one of the two supported ones. */
enum class AppLanguage(val id: String, val tag: String?) {
    SYSTEM("system", null), ENGLISH("en", "en"), PORTUGUESE("pt", "pt");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * User preferences that outlive projects: theme, language and whether the welcome tour was seen. Stored in
 * SharedPreferences; the values are Compose state so screens follow changes immediately.
 */
object AppSettings {
    private const val FILE = "kinesis_settings"
    private const val THEME = "theme"
    private const val LANGUAGE = "language"
    private const val ONBOARDING = "onboarding_done"

    var themeMode by mutableStateOf(ThemeMode.DARK)
        private set
    var language by mutableStateOf(AppLanguage.SYSTEM)
        private set
    var onboardingDone by mutableStateOf(true)
        private set

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        themeMode = ThemeMode.of(p.getString(THEME, null))
        language = AppLanguage.of(p.getString(LANGUAGE, null))
        onboardingDone = p.getBoolean(ONBOARDING, false)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setThemeMode(context: Context, mode: ThemeMode) {
        themeMode = mode
        prefs(context).edit().putString(THEME, mode.id).apply()
    }

    fun setOnboardingDone(context: Context, done: Boolean) {
        onboardingDone = done
        prefs(context).edit().putBoolean(ONBOARDING, done).apply()
    }

    /** Saves the language and applies it: application resources now, the activity is recreated to redraw in it. */
    fun setLanguage(activity: Activity, value: AppLanguage) {
        language = value
        prefs(activity).edit().putString(LANGUAGE, value.id).apply()
        val locale = value.tag?.let(Locale::forLanguageTag) ?: systemLocale
        Locale.setDefault(locale)
        // ViewModels read strings from the application context: update it too.
        val app = activity.applicationContext as Application
        val cfg = Configuration(app.resources.configuration)
        cfg.setLocale(locale)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(cfg, app.resources.displayMetrics)
        activity.recreate()
    }

    private val systemLocale: Locale = Locale.getDefault()

    /** The context with the chosen language, for `attachBaseContext` of the application and the activity. */
    fun wrap(base: Context): Context {
        val id = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LANGUAGE, null)
        val tag = AppLanguage.of(id).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
