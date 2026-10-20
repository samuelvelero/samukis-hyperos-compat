package xyz.samukis.hyperoscompat.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import xyz.samukis.hyperoscompat.bridge.ActionRole
import xyz.samukis.hyperoscompat.bridge.AppBridge
import xyz.samukis.hyperoscompat.bridge.BridgeRegistry
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.island.IslandPoster
import xyz.samukis.hyperoscompat.island.MicMute
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
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var enabled: Set<String> = emptySet()
    @Volatile private var inspector = false

    private class Active(val id: Int, val hash: Int, val pkg: String, val spec: IslandSpec)

    /** Source notification key -> our island. Guarded by itself. */
    private val active = HashMap<String, Active>()
    private val firstSeen = HashMap<String, Long>()

    /** Ids we cancelled ourselves, so their removal callback is not mistaken for a user dismissal. */
    private val ourCancels = HashSet<Int>()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        poster = IslandPoster(this, settings)
        bridges = BridgeRegistry.all(this).associateBy { it.packageName }
        reloadPrefs()
        settings.registerListener(this)
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        MicMute.release(this)
        settings.unregisterListener(this)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        // Anything we posted before a restart is stale: drop it all and rebuild from what is live now.
        synchronized(active) {
            active.clear()
            firstSeen.clear()
            ourCancels.clear()
        }
        getSystemService(NotificationManager::class.java).cancelAll()
        resync()
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
            synchronized(active) {
                if (ourCancels.remove(sbn.id)) return
                // HyperOS or the user dismissed our island: forget it so the next source update re-posts it.
                active.entries.removeIf { it.value.id == sbn.id }
            }
            return
        }
        if (!bridges.containsKey(sbn.packageName)) return
        if (inspector) NotificationLog.event(this, "REMOVED ${sbn.key} reason=$reason")
        synchronized(active) {
            active.remove(sbn.key)?.let { cancelOurs(it.id) }
            firstSeen.remove(sbn.key)
            releaseMicIfIdle()
        }
    }

    /** No call island left: give the microphone back if we were the ones muting it. */
    private fun releaseMicIfIdle() {
        if (active.isEmpty()) MicMute.release(this)
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
                active.remove(key)?.let {
                    cancelOurs(it.id)
                    if (inspector) NotificationLog.event(this, "island dropped: $key no longer looks like a call")
                }
                firstSeen.remove(key)
                releaseMicIfIdle()
                return
            }
            firstSeen[key] = seen
            val hash = spec.contentHash()
            val current = active[key]
            if (current != null && current.hash == hash) return
            val id = current?.id ?: idFor(key)
            try {
                poster.post(id, pkg, spec, sbn.notification, sourceKey = key)
                active[key] = Active(id, hash, pkg, spec)
                if (inspector) NotificationLog.event(
                    this,
                    "island ${if (current == null) "posted" else "updated"}: ${spec.state} \"${spec.title}\" " +
                        "actions=${spec.actions.joinToString { "${it.role}:${it.title}" }}",
                )
            } catch (e: Exception) {
                // A malformed payload must never take the listener down.
                if (inspector) NotificationLog.event(this, "post failed: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /**
     * Re-derives every island from the notifications that are live right now and cancels the ones
     * whose source has gone. Cheap, so it is also run when the main screen comes to the foreground.
     */
    fun resync() {
        val live = try { activeNotifications } catch (_: Exception) { null } ?: return
        val liveKeys = HashSet<String>()
        live.forEach { if (it.packageName != packageName) liveKeys.add(it.key) }
        synchronized(active) {
            val it = active.entries.iterator()
            while (it.hasNext()) {
                val (key, island) = it.next()
                if (key !in liveKeys) {
                    cancelOurs(island.id)
                    it.remove()
                    firstSeen.remove(key)
                    if (inspector) NotificationLog.event(this, "resync: dropped orphan island for $key")
                }
            }
            releaseMicIfIdle()
        }
        live.forEach { if (it.packageName != packageName) handlePosted(it) }
    }

    /**
     * Forwards an island button press to the source notification's real action. Looks the action up
     * in the live notification first (freshest PendingIntent), then in our last snapshot.
     */
    fun fireSourceAction(sourceKey: String, sourceIndex: Int) {
        val liveAction = try {
            activeNotifications?.firstOrNull { it.key == sourceKey }
                ?.notification?.actions?.getOrNull(sourceIndex)
        } catch (_: Exception) { null }
        val snapshot = synchronized(active) { active[sourceKey] }
        val snapshotAction = snapshot?.spec?.actions?.firstOrNull { it.sourceIndex == sourceIndex }

        val intent: PendingIntent? = liveAction?.actionIntent ?: snapshotAction?.intent
        val label = liveAction?.title ?: snapshotAction?.title
        if (intent == null) {
            NotificationLog.event(this, "action click: nothing to fire for $sourceKey[$sourceIndex]")
            return
        }
        try {
            intent.send()
            NotificationLog.event(this, "action click: sent \"$label\" to ${sourceKey.substringAfter('|').substringBefore('|')}")
        } catch (e: Exception) {
            NotificationLog.event(this, "action click: send failed for \"$label\": ${e.javaClass.simpleName}")
            return
        }
        // HyperOS collapses the island after a button press. For a toggle like mute the call goes on,
        // so bring the island back; the source app's next update will refresh its content.
        val role = snapshotAction?.role
        if (snapshot != null && role != ActionRole.HANGUP && role != ActionRole.DECLINE) {
            mainHandler.postDelayed({ repost(sourceKey) }, 700)
        }
    }

    private fun repost(sourceKey: String) {
        val sbn = try { activeNotifications?.firstOrNull { it.key == sourceKey } } catch (_: Exception) { null } ?: return
        synchronized(active) { active.remove(sourceKey) }
        handlePosted(sbn)
    }

    private fun cancelOurs(id: Int) {
        ourCancels.add(id)
        poster.cancel(id)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        reloadPrefs()
        // Drop islands of apps that were just disabled.
        synchronized(active) {
            val it = active.entries.iterator()
            while (it.hasNext()) {
                val (srcKey, island) = it.next()
                if (island.pkg !in enabled) {
                    cancelOurs(island.id)
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

        /** Same process as the UI and receivers, so a direct reference is enough. */
        @Volatile var instance: IslandBridgeService? = null
            private set

        fun requestRebind(context: Context) {
            requestRebind(ComponentName(context, IslandBridgeService::class.java))
        }
    }
}
