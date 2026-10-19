package dev.kinesis.editor.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.format.EasingSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
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
    private const val SAVED_EASINGS = "saved_easings"
    private const val PREVIEW_SCALE = "preview_scale"
    private const val PREVIEW_QUALITY_3D = "preview_quality_3d"
    private const val MIRROR_DOCUMENTS = "mirror_documents"
    private const val USE_PROXIES = "use_proxies"
    private const val AUTO_KEYFRAME = "auto_keyframe"
    private const val SHOW_LAYER_NAMES = "show_layer_names"

    var themeMode by mutableStateOf(ThemeMode.DARK)
        private set
    var language by mutableStateOf(AppLanguage.SYSTEM)
        private set
    var onboardingDone by mutableStateOf(true)
        private set

    /** Curves the user saved in the curve editor, reusable on any keyframe. */
    var savedEasings by mutableStateOf<List<Easing>>(emptyList())
        private set

    /** Preview resolution: 1 (full), 0.5 or 0.25 of the on-screen size. */
    var previewScale by mutableStateOf(1f)
        private set

    /** 3D quality of the preview: 0 low, 1 medium, 2 high. Export always uses high. */
    var previewQuality3D by mutableStateOf(1)
        private set

    /** Keep a copy of every project as a .knsproj in Documents/Kinesis (survives reinstalling the app). */
    var mirrorToDocuments by mutableStateOf(true)
        private set

    /** Play heavy videos from light proxies in the editor (export always uses the originals). */
    var useProxies by mutableStateOf(true)
        private set

    /** Moving the camera from inside it (viewport gestures) records keyframes. */
    var autoKeyframe by mutableStateOf(false)
        private set

    /** Show the layer names on the timeline clips (hide them for a cleaner timeline). */
    var showLayerNames by mutableStateOf(true)
        private set

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        themeMode = ThemeMode.of(p.getString(THEME, null))
        language = AppLanguage.of(p.getString(LANGUAGE, null))
        onboardingDone = p.getBoolean(ONBOARDING, false)
        savedEasings = p.getString(SAVED_EASINGS, null)?.let(::decodeEasings).orEmpty()
        previewScale = p.getFloat(PREVIEW_SCALE, 1f).takeIf { it in listOf(1f, 0.5f, 0.25f) } ?: 1f
        previewQuality3D = p.getInt(PREVIEW_QUALITY_3D, 1).coerceIn(0, 2)
        mirrorToDocuments = p.getBoolean(MIRROR_DOCUMENTS, true)
        useProxies = p.getBoolean(USE_PROXIES, true)
        autoKeyframe = p.getBoolean(AUTO_KEYFRAME, false)
        showLayerNames = p.getBoolean(SHOW_LAYER_NAMES, true)
    }

    fun setShowLayerNames(context: Context, on: Boolean) {
        showLayerNames = on
        prefs(context).edit().putBoolean(SHOW_LAYER_NAMES, on).apply()
    }

    fun setAutoKeyframe(context: Context, on: Boolean) {
        autoKeyframe = on
        prefs(context).edit().putBoolean(AUTO_KEYFRAME, on).apply()
    }

    fun saveEasing(context: Context, easing: Easing) {
        if (easing in savedEasings) return
        savedEasings = (savedEasings + easing).takeLast(MAX_SAVED_EASINGS)
        prefs(context).edit().putString(SAVED_EASINGS, encodeEasings(savedEasings)).apply()
    }

    fun deleteEasing(context: Context, easing: Easing) {
        savedEasings = savedEasings - easing
        prefs(context).edit().putString(SAVED_EASINGS, encodeEasings(savedEasings)).apply()
    }

    fun setPreviewScale(context: Context, scale: Float) {
        previewScale = scale
        prefs(context).edit().putFloat(PREVIEW_SCALE, scale).apply()
    }

    fun setPreviewQuality3D(context: Context, level: Int) {
        previewQuality3D = level.coerceIn(0, 2)
        prefs(context).edit().putInt(PREVIEW_QUALITY_3D, previewQuality3D).apply()
    }

    fun setMirrorToDocuments(context: Context, on: Boolean) {
        mirrorToDocuments = on
        prefs(context).edit().putBoolean(MIRROR_DOCUMENTS, on).apply()
    }

    fun setUseProxies(context: Context, on: Boolean) {
        useProxies = on
        prefs(context).edit().putBoolean(USE_PROXIES, on).apply()
    }

    private const val MAX_SAVED_EASINGS = 24

    private fun encodeEasings(list: List<Easing>): String = JsonArray(list.map { EasingSerializer.toJson(it) }).toString()

    private fun decodeEasings(text: String): List<Easing> = try {
        Json.parseToJsonElement(text).jsonArray.mapNotNull { runCatching { EasingSerializer.fromJson(it) }.getOrNull() }
    } catch (e: Exception) {
        emptyList()
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
