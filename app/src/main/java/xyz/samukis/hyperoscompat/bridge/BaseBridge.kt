package xyz.samukis.hyperoscompat.bridge

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes

/** Shared plumbing: parses the notification once and hands a [Notif] to the concrete translator. */
abstract class BaseBridge(
    protected val context: Context,
    final override val packageName: String,
    @StringRes final override val nameRes: Int,
    @StringRes final override val descriptionRes: Int,
    final override val category: BridgeCategory,
    final override val accentColor: String,
    final override val packages: Set<String> = setOf(packageName),
    final override val tested: Boolean = false,
) : AppBridge {
    protected val kw: Keywords = Keywords.get(context)

    protected fun str(@StringRes id: Int): String = context.getString(id)
    protected fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
    protected val appName: String get() = str(nameRes)

    final override fun translate(sbn: StatusBarNotification, firstSeen: Long): IslandSpec? {
        val x = Notif(sbn, kw)
        if (x.summary) return null
        return translate(x, firstSeen)
    }

    protected abstract fun translate(x: Notif, firstSeen: Long): IslandSpec?
}
