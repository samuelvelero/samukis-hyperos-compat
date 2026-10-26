package xyz.samukis.hyperoscompat.swipe

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as SysSettings
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import xyz.samukis.hyperoscompat.prefs.Settings

/**
 * Swipe down on the home screen opens QuickSearch instead of HyperOS search.
 *
 * Done entirely with settings the HyperOS launcher already honours, so nothing has to run:
 *  - The launcher's "swipe for Google search" edge effect (`QSB_SCROLL_ENTRANCE_STATUS`) is put on
 *    the top edge (`QSB_SCROLL_OPTION_STATUS=0`) and typed as a search effect (slide-up gesture
 *    `global_search`). With a top effect present the launcher's status-bar swipe controller refuses
 *    the gesture ("pull_down action isn't notification"), so no shade or control centre, and with
 *    the pull-down gesture set to `no_action` AppFinder's controller stays out too.
 *  - On release the effect calls `Activity.startSearch`, which opens the system's global search
 *    activity; `search_global_search_activity` points that at QuickSearch.
 *
 * Third-party apps may not write those keys themselves (only shell and the system can keep
 * arbitrary keys in the system table), so each write goes through Shizuku as `settings put`.
 * Shizuku is only needed while switching on or off: the settings persist across reboots.
 */
object SwipeSearch {
    const val TARGET_PACKAGE = "com.tk.quicksearch"
    const val REQUEST_CODE = 7301
    private const val TAG = "SwipeSearch"
    private val TARGET = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.app.MainActivity")

    enum class State { OFF, OFF_PENDING_RESTORE, NO_TARGET, NO_SHIZUKU, NO_SHIZUKU_PERMISSION, APPLYING, ON }

    private class Key(val name: String, val table: String, val value: String)

    private val keys = listOf(
        Key("search_global_search_activity", "secure", TARGET.flattenToString()),
        Key("QSB_SCROLL_ENTRANCE_STATUS", "system", "1"),
        Key("QSB_SCROLL_OPTION_STATUS", "system", "0"),
        Key("com.miui.home.preferences.launcher_slideup_gesture", "system", "global_search"),
        Key("com.miui.home.preferences.launcher_pulldown_gesture", "system", "no_action"),
    )

    private var listening = false
    @Volatile private var busy = false

    /** Call once from the Application: a pending apply or restore finishes when Shizuku comes up. */
    fun init(context: Context) {
        if (listening) return
        listening = true
        val app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { ensure(app) }
    }

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasShizukuPermission(): Boolean = shizukuRunning() &&
        try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    fun targetInstalled(context: Context): Boolean =
        try { context.packageManager.getApplicationInfo(TARGET_PACKAGE, 0); true } catch (_: Exception) { false }

    fun applied(context: Context): Boolean = keys.all { read(context, it) == it.value }

    private fun pendingRestore(settings: Settings): Boolean = keys.any { settings.backup(it.name) != null }

    fun state(context: Context): State {
        val settings = Settings(context)
        val enabled = settings.swipeSearchEnabled
        return when {
            !enabled && !pendingRestore(settings) -> State.OFF
            enabled && !targetInstalled(context) -> State.NO_TARGET
            enabled && applied(context) -> State.ON
            busy -> State.APPLYING
            !shizukuRunning() -> if (enabled) State.NO_SHIZUKU else State.OFF_PENDING_RESTORE
            !hasShizukuPermission() -> State.NO_SHIZUKU_PERMISSION
            else -> State.APPLYING
        }
    }

    /** Applies or restores the settings to match the switch, through Shizuku. Safe to call often. */
    @Synchronized
    fun ensure(context: Context) {
        val settings = Settings(context)
        val enabled = settings.swipeSearchEnabled
        if (enabled && applied(context)) return
        if (!enabled && !pendingRestore(settings)) return
        if (!hasShizukuPermission() || busy) return
        busy = true
        Thread {
            try {
                if (enabled) apply(context, settings) else restore(context, settings)
            } catch (e: Throwable) {
                Log.w(TAG, "settings write failed: $e")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun apply(context: Context, settings: Settings) {
        for (k in keys) {
            val current = read(context, k)
            if (current == k.value) continue
            // Remember what the user had, once, so switching off puts it back.
            if (settings.backup(k.name) == null) settings.setBackup(k.name, current ?: NONE)
            write(k, k.value)
        }
        Log.i(TAG, "launcher settings applied")
    }

    private fun restore(context: Context, settings: Settings) {
        for (k in keys) {
            val saved = settings.backup(k.name) ?: continue
            if (read(context, k) == k.value) write(k, saved.takeIf { it != NONE })
            settings.setBackup(k.name, null)
        }
        Log.i(TAG, "launcher settings restored")
    }

    private fun read(context: Context, k: Key): String? = try {
        if (k.table == "secure") SysSettings.Secure.getString(context.contentResolver, k.name)
        else SysSettings.System.getString(context.contentResolver, k.name)
    } catch (_: Exception) { null }

    /** `settings put` / `delete` as shell: the one caller allowed to keep these keys. */
    private fun write(k: Key, value: String?) {
        val cmd = if (value == null) arrayOf("settings", "delete", k.table, k.name)
        else arrayOf("settings", "put", k.table, k.name, value)
        exec(*cmd)?.waitFor()
    }

    /** `Shizuku.newProcess` is not public API, but it is the one call that runs a command as shell. */
    private fun exec(vararg cmd: String): ShizukuRemoteProcess? {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf(*cmd), null, null) as? ShizukuRemoteProcess
    }

    fun requestShizukuPermission() {
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) {}
    }

    private const val NONE = "\u0000"
}
