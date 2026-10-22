package xyz.samukis.hyperoscompat.bridge

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes

/**
 * One island per app, fed by every notification the app posts about the same "thing" (a ride, a
 * call, a set of transfers, an order). Subclasses implement [session]:
 * - call [claim] when this notification is the one the island is built from (the owner);
 * - learn from the others (a PIN, a share button) and call [requestRefresh] so the island is rebuilt;
 * - return null for notifications that must not drive the island by themselves.
 * The session resets when its owner goes away ([onEnd]) or after [staleMs] without news.
 */
abstract class SessionBridge(
    context: Context,
    packageName: String,
    @StringRes nameRes: Int,
    @StringRes descriptionRes: Int,
    category: BridgeCategory,
    accentColor: String,
    packages: Set<String> = setOf(packageName),
    tested: Boolean = false,
) : BaseBridge(context, packageName, nameRes, descriptionRes, category, accentColor, packages, tested) {

    final override val singleIsland: Boolean = true

    /** Key of the notification the island is currently built from. */
    protected var ownerKey: String? = null
        private set
    private var touchedAt = 0L
    private var refresh = false

    /** Buttons learned from non-owner notifications, offered when the owner has none. */
    protected val borrowed = LinkedHashMap<ActionRole, IslandAction>()

    protected open val staleMs: Long = 3 * 60 * 60_000L

    final override fun islandKeyFor(sbn: StatusBarNotification): String = "${sbn.packageName}#session"

    final override fun takeRefreshRequest(): Boolean = refresh.also { refresh = false }

    override fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == ownerKey) endSession()
    }

    final override fun translate(x: Notif, firstSeen: Long): IslandSpec? {
        val now = System.currentTimeMillis()
        if (touchedAt != 0L && now - touchedAt > staleMs) endSession()
        return session(x, firstSeen, now)
    }

    protected abstract fun session(x: Notif, firstSeen: Long, now: Long): IslandSpec?

    /** Marks [x] as the notification the island is built from. */
    protected fun claim(x: Notif, now: Long) {
        ownerKey = x.sbn.key
        touchedAt = now
    }

    /** Keeps the session alive when a non-owner brought news. */
    protected fun touch(now: Long) {
        touchedAt = now
    }

    protected fun requestRefresh() {
        refresh = true
    }

    /** Learns the first action of each role from [x]; fired directly later (sourceIndex -1). */
    protected fun borrow(x: Notif, vararg roles: ActionRole) {
        for (role in roles) {
            val a = x.pick(arrayOf(role)).firstOrNull() ?: continue
            if (borrowed[role]?.intent == a.intent) continue
            borrowed[role] = IslandAction(role, a.title, null, a.intent)
            requestRefresh()
        }
    }

    protected fun endSession() {
        ownerKey = null
        touchedAt = 0L
        borrowed.clear()
        onEnd()
    }

    /** Subclass state reset. */
    protected open fun onEnd() {}
}
