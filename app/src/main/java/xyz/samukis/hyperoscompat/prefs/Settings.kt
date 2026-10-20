package xyz.samukis.hyperoscompat.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Tiny SharedPreferences wrapper. Read once by the service, refreshed on change. */
class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isAppEnabled(pkg: String): Boolean = sp.getBoolean(appKey(pkg), true)
    fun setAppEnabled(pkg: String, enabled: Boolean) = sp.edit { putBoolean(appKey(pkg), enabled) }

    var inspectorEnabled: Boolean
        get() = sp.getBoolean(KEY_INSPECTOR, false)
        set(value) = sp.edit { putBoolean(KEY_INSPECTOR, value) }

    var showInShade: Boolean
        get() = sp.getBoolean(KEY_SHADE, true)
        set(value) = sp.edit { putBoolean(KEY_SHADE, value) }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_INSPECTOR = "inspector"
        const val KEY_SHADE = "show_in_shade"
        fun appKey(pkg: String) = "app_$pkg"
    }
}
