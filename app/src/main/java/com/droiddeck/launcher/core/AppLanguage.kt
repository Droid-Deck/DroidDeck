package com.droiddeck.launcher.core

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The language the app shows itself in.
 *
 * Nothing chosen (the default) means the system's: Android's own language, or the per-app language
 * Android 13+ lets a user set for DroidDeck in its settings, exactly as Android resolves it. Picking
 * one in Setup overrides that until the user picks "System default" again. Each activity and service
 * applies the choice to its own context ([wrap], [applyTo]), so it works on every Android version
 * the app runs on rather than only where the platform's per-app language API exists.
 *
 * Steam follows the same choice: [SteamLanguage] turns [effective] into the client's language.
 */
object AppLanguage {
    /** The stored value meaning "follow the system". */
    const val SYSTEM = ""

    /** The languages the app ships, as BCP 47 tags, in the order the picker lists them. */
    val supported: List<String> = listOf("en", "es", "fr", "ru", "ja", "ko", "zh-CN", "zh-TW", "zh-HK")

    private const val PREFS = "language"
    private const val KEY = "app_language"

    /** The locale Android gave the process before any choice here was applied. */
    @Volatile private var systemLocale: Locale? = null

    /** The stored choice: one of [supported], or [SYSTEM]. */
    fun chosen(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
            ?.takeIf { it in supported } ?: SYSTEM

    fun setChosen(context: Context, tag: String) {
        val value = if (tag in supported) tag else SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).commit()
        val locale = value.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: system(context)
        Locale.setDefault(locale)
        // Code that reads strings through the application context sees the new language too.
        val app = context.applicationContext
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(
            Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)) },
            app.resources.displayMetrics,
        )
    }

    /** The locale Android chose for the app, ignoring the choice made here. */
    fun system(context: Context): Locale =
        systemLocale ?: context.resources.configuration.locales[0] ?: Locale.getDefault()

    /** The locale the app is shown in: the chosen language, or else the system's. */
    fun effective(context: Context): Locale =
        chosen(context).takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: system(context)

    /** Remembers the system's locale; call with the untouched base context, before [wrap]. */
    fun noteSystem(base: Context) {
        if (systemLocale == null) systemLocale = base.resources.configuration.locales[0]
    }

    /**
     * [base], showing the chosen language: for an Application's or a Service's attachBaseContext.
     * Only the locale is overridden, so screen size, density and night mode keep following the
     * device as they change.
     */
    fun wrap(base: Context): Context {
        noteSystem(base)
        val tag = chosen(base).ifEmpty { return base }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(override(locale))
    }

    /**
     * Applies the chosen language to an activity; call from its attachBaseContext, after super.
     * applyOverrideConfiguration keeps the locale through rotation and window resizes, which an
     * activity here handles itself rather than being recreated.
     */
    fun applyTo(activity: Activity, base: Context) {
        noteSystem(base)
        val tag = chosen(base).ifEmpty { return }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        activity.applyOverrideConfiguration(override(locale))
    }

    /** Follows a change of the system's language while the process lives (Application.onConfigurationChanged). */
    fun systemChanged(config: Configuration) {
        config.locales[0]?.let { systemLocale = it }
    }

    // A new Configuration's fontScale is 1, which as an override would undo the user's font size;
    // 0 leaves it undefined, so only the locale is changed.
    private fun override(locale: Locale) = Configuration().apply { fontScale = 0f; setLocales(LocaleList(locale)) }

    /** Which of the app's languages [locale] reads as, or null when the app does not ship it. */
    fun supportedTag(locale: Locale): String? = when (locale.language) {
        "en", "es", "fr", "ru", "ja", "ko" -> locale.language
        "zh" -> when {
            locale.script == "Hans" -> "zh-CN"
            locale.country == "HK" || locale.country == "MO" -> "zh-HK"
            locale.script == "Hant" || locale.country == "TW" -> "zh-TW"
            else -> "zh-CN"
        }
        else -> null
    }

    /** [locale]'s language named in itself, without its region unless the app tells them apart. */
    fun displayName(locale: Locale): String =
        supportedTag(locale)?.let(::nativeName) ?: locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }

    /** A language's name in that language, for the picker: what a reader of it looks for. */
    fun nativeName(tag: String): String = when (tag) {
        "en" -> "English"
        "es" -> "Español"
        "fr" -> "Français"
        "ru" -> "Русский"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁體中文（台灣）"
        "zh-HK" -> "繁體中文（香港）"
        else -> Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
    }
}
