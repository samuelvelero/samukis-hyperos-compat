package xyz.samukis.hyperoscompat.ui

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import androidx.core.net.toUri

/** Read-only probes of the HyperOS plumbing, plus deep links into its settings pages. */
object HyperOsChecks {
    fun hasFocusPermission(context: Context): Boolean = try {
        val extras = Bundle().apply { putString("package", context.packageName) }
        val result = context.contentResolver.call(
            "content://miui.statusbar.notification.public".toUri(), "canShowFocus", null, extras,
        )
        result?.getBoolean("canShowFocus", false) ?: false
    } catch (_: Exception) {
        false
    }

    @SuppressLint("PrivateApi")
    fun isIslandSupported(): Boolean = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        m.invoke(null, "persist.sys.feature.island", false) as Boolean
    } catch (_: Exception) {
        false
    }

    /**
     * HyperOS "Autostart" permission, kept as a private AppOps operation (10008). Null when the ROM
     * does not expose it or blocks the lookup; the UI then offers the settings page without a verdict.
     */
    fun isAutostartAllowed(context: Context): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val m = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        (m.invoke(ops, OP_AUTO_START, Process.myUid(), context.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        null
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** HyperOS Security app, Autostart management list. */
    fun autostartIntent(): Intent = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )

    /** HyperOS per-app battery saver page ("No restrictions"). */
    fun batterySaverIntent(context: Context): Intent = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST")
        .addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())

    private const val OP_AUTO_START = 10008
}
