package xyz.samukis.hyperoscompat.service

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import xyz.samukis.hyperoscompat.bridge.AppBridge
import xyz.samukis.hyperoscompat.bridge.BridgeRegistry
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.island.IslandPoster
import xyz.samukis.hyperoscompat.prefs.Settings

/**
 * The whole runtime. Android binds this once and calls us only when a notification is posted,
 * updated or removed, so idle cost is zero: no foreground service, no polling, no wake locks.
 * Every callback starts with a package check so unrelated notifications cost one map lookup.
 */
class IslandBridgeService : NotificationListenerService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: Settings
    private lateinit var poster: IslandPoster
    private lateinit var bridges: Map<String, AppBridge>

    @Volatile private var enabled: Set<String> = emptySet()
    @Volatile private var inspector = false

    private class Active(val id: Int, val hash: Int)

    /** Source notification key -> our island. Guarded by itself. */
    private val active = HashMap<String, Active>()
    private val firstSeen = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        poster = IslandPoster(this, settings)
        bridges = BridgeRegistry.all(this).associateBy { it.packageName }
        reloadPrefs()
        settings.registerListener(this)
    }

    override fun onDestroy() {
        settings.unregisterListener(this)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        // Pick up a call that was already running when the system bound us.
        val current = try { activeNotifications } catch (_: Exception) { null } ?: return
        current.forEach(::handlePosted)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        handlePosted(sbn ?: return)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        if (sbn.packageName == packageName) {
            // User swiped our island away: forget it so the next source update can re-post.
            if (reason == REASON_CANCEL) synchronized(active) {
                active.entries.removeIf { it.value.id == sbn.id }
            }
            return
        }
        synchronized(active) {
            active.remove(sbn.key)?.let { poster.cancel(it.id) }
            firstSeen.remove(sbn.key)
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val bridge = bridges[pkg] ?: return
        if (pkg !in enabled) return
        if (inspector) NotificationLog.record(this, sbn)

        val key = sbn.key
        synchronized(active) {
            val seen = firstSeen[key] ?: System.currentTimeMillis()
            val spec = bridge.translate(sbn, seen)
            if (spec == null) {
                active.remove(key)?.let { poster.cancel(it.id) }
                firstSeen.remove(key)
                return
            }
            firstSeen[key] = seen
            val hash = spec.contentHash()
            val current = active[key]
            if (current != null && current.hash == hash) return
            val id = current?.id ?: idFor(key)
            try {
                poster.post(id, pkg, spec, sbn.notification)
                active[key] = Active(id, hash)
            } catch (_: Exception) {
                // A malformed payload must never take the listener down.
            }
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        reloadPrefs()
        // Drop islands of apps that were just disabled.
        synchronized(active) {
            val it = active.entries.iterator()
            while (it.hasNext()) {
                val (srcKey, island) = it.next()
                val pkg = srcKey.split('|').getOrNull(1) ?: continue
                if (pkg !in enabled) {
                    poster.cancel(island.id)
                    it.remove()
                    firstSeen.remove(srcKey)
                }
            }
        }
    }

    private fun reloadPrefs() {
        enabled = bridges.keys.filterTo(HashSet()) { settings.isAppEnabled(it) }
        inspector = settings.inspectorEnabled
    }

    private fun idFor(key: String): Int = ID_BASE + (key.hashCode() and 0x0FFFFFFF)

    companion object {
        private const val ID_BASE = 1000

        @Volatile var connected: Boolean = false
            private set

        fun requestRebind(context: Context) {
            requestRebind(ComponentName(context, IslandBridgeService::class.java))
        }
    }
}
