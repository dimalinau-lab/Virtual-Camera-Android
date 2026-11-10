package com.example.ccamera

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "virtualcam_prefs"
    private const val KEY_LANGUAGE = "app_language" // "system", "ru", "uk", "en"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "system") ?: "system"
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()
    }

    fun getEffectiveLocale(context: Context): Locale {
        val saved = getSavedLanguage(context)
        if (saved != "system" && saved.isNotBlank()) {
            return when (saved.lowercase()) {
                "ru" -> Locale("ru")
                "uk" -> Locale("uk")
                else -> Locale("en")
            }
        }

        val sysLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            Resources.getSystem().configuration.locale
        }

        val lang = sysLocale?.language?.lowercase() ?: "en"
        return when (lang) {
            "ru" -> Locale("ru")
            "uk" -> Locale("uk")
            else -> Locale("en")
        }
    }

    fun applyLocale(context: Context): Context {
        val locale = getEffectiveLocale(context)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            context
        }
    }

    fun getDisplayCode(context: Context): String {
        val saved = getSavedLanguage(context)
        return when (saved) {
            "ru" -> "🌐 RU"
            "uk" -> "🌐 UK"
            "en" -> "🌐 EN"
            else -> {
                val eff = getEffectiveLocale(context).language.lowercase()
                when (eff) {
                    "ru" -> "🌐 RU"
                    "uk" -> "🌐 UK"
                    else -> "🌐 EN"
                }
            }
        }
    }
}

