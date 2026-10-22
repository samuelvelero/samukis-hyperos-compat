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
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.island.IslandPoster
import xyz.samukis.hyperoscompat.prefs.Settings

/**
 * The whole runtime. Android binds this once and calls us only when a notification is posted,
 * updated or removed, so idle cost is zero: no foreground service, no polling, no wake locks.
 * Every callback starts with a package check so unrelated notifications cost one map lookup.
 *
 * Islands are indexed by an *island key* chosen by the bridge ([AppBridge.islandKeyFor]). Session
 * bridges map every notification of an app to one key, so several notifications feed one island.
 * The notification that produced the island's current content is its *owner* (used for buttons).
 */
class IslandBridgeService : NotificationListenerService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: Settings
    private lateinit var poster: IslandPoster
    private lateinit var bridges: List<AppBridge>
    /** Source package -> bridge (a bridge may serve several packages). */
    private lateinit var bridgeByPackage: Map<String, AppBridge>
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Source packages whose bridge is switched on. */
    @Volatile private var enabled: Set<String> = emptySet()
    @Volatile private var inspector = false
    @Volatile private var paused = false
    @Volatile private var respectDnd = false

    private class Active(val id: Int, val hash: Int, val pkg: String, val spec: IslandSpec, val sourceKey: String)

    /** Island key -> our island. Guarded by itself. */
    private val active = HashMap<String, Active>()
    /** Island key -> when it first appeared (fallback start time for timers). */
    private val firstSeen = HashMap<String, Long>()

    /** Ids we cancelled ourselves, so their removal callback is not mistaken for a user dismissal. */
    private val ourCancels = HashSet<Int>()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        poster = IslandPoster(this, settings)
        bridges = BridgeRegistry.all(this)
        bridgeByPackage = bridges.flatMap { b -> b.packages.map { it to b } }.toMap()
        reloadPrefs()
        settings.registerListener(this)
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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
                // HyperOS, a timeout or the user dismissed our island: forget it so the next source
                // update can re-post it.
                active.entries.removeIf { it.value.id == sbn.id }
            }
            return
        }
        val bridge = bridgeByPackage[sbn.packageName] ?: return
        bridge.onRemoved(sbn)
        if (inspector) NotificationLog.event(this, "REMOVED ${sbn.key} reason=$reason")
        val ikey = bridge.islandKeyFor(sbn)
        val owned = synchronized(active) { active[ikey]?.sourceKey == sbn.key }
        if (owned) {
            // Apps often swap one notification for another (ringing -> in call). Wait a moment so
            // the replacement can take the island over in place instead of the island blinking.
            mainHandler.postDelayed({
                synchronized(active) {
                    if (active[ikey]?.sourceKey == sbn.key) releaseOwner(bridge, ikey, sbn.key, sbn.packageName)
                }
            }, HANDOVER_MS)
        } else if (bridge.takeRefreshRequest()) {
            // A non-owner went away but changed the session (e.g. one of several transfers ended).
            refreshPackage(sbn.packageName, sbn.key)
        }
    }

    /**
     * @param fromRefresh true when re-run because the bridge learned something from another
     *        notification; such runs are not logged again and never trigger another refresh.
     */
    private fun handlePosted(sbn: StatusBarNotification, fromRefresh: Boolean = false) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val bridge = bridgeByPackage[pkg] ?: return
        if (pkg !in enabled || paused) return
        if (inspector && !fromRefresh) NotificationLog.record(this, sbn)

        synchronized(active) { applyLocked(bridge, sbn, pkg) }
        // e.g. Uber: a push carried the PIN, so the live ride island is rebuilt with it.
        if (bridge.takeRefreshRequest() && !fromRefresh) refreshPackage(pkg, sbn.key)
    }

    /** Re-translates the owners of this package's islands, except [exceptKey]. */
    private fun refreshPackage(pkg: String, exceptKey: String) {
        val owners = synchronized(active) {
            active.values.filter { it.pkg == pkg && it.sourceKey != exceptKey }.map { it.sourceKey }.toSet()
        }
        if (owners.isEmpty()) return
        val live = liveNotifications() ?: return
        live.filter { it.key in owners }.forEach { handlePosted(it, fromRefresh = true) }
    }

    /** Translates one source notification and posts, updates or drops its island. Caller holds [active]. */
    private fun applyLocked(bridge: AppBridge, sbn: StatusBarNotification, pkg: String) {
        val key = sbn.key
        val ikey = bridge.islandKeyFor(sbn)
        val seen = firstSeen[ikey] ?: System.currentTimeMillis()
        var spec = bridge.translate(sbn, seen)
        // Do Not Disturb: only calls are worth an island while it is on.
        if (spec != null && respectDnd && dndActive() &&
            spec.kind != IslandKind.INCOMING && spec.kind != IslandKind.TIMER
        ) spec = null
        val current = active[ikey]
        if (spec == null) {
            // Only the owner can take its island down; a push that is "not an island" leaves it alone.
            if (current?.sourceKey == key) releaseOwner(bridge, ikey, key, pkg)
            return
        }
        firstSeen[ikey] = seen
        val hash = spec.contentHash()
        if (current != null && current.hash == hash && current.sourceKey == key) return

        // Timed status islands (order updates) come as separate notifications, and some apps
        // must only ever show one island: keep only this one for the app.
        if (spec.autoDismissMs > 0 || bridge.singleIsland) {
            val stale = active.entries.filter { it.value.pkg == pkg && it.key != ikey }
            stale.forEach { cancelOurs(it.value.id); firstSeen.remove(it.key) }
            active.keys.removeAll(stale.map { it.key }.toSet())
        }

        val id = current?.id ?: idFor(ikey)
        try {
            poster.post(id, pkg, spec, sbn.notification, sourceKey = key)
            active[ikey] = Active(id, hash, pkg, spec, key)
            if (inspector) NotificationLog.event(
                this,
                "island ${if (current == null) "posted" else "updated"} [$ikey]: ${spec.kind} \"${spec.title}\" " +
                    "pill=${spec.pillText} trailing=${spec.trailing} pin=${spec.highlight} progress=${spec.progress} " +
                    "actions=${spec.actions.joinToString { "${it.role}:${it.title}" }}",
            )
        } catch (e: Exception) {
            // A malformed payload must never take the listener down.
            if (inspector) NotificationLog.event(this, "post failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * The owner of island [ikey] went away or stopped qualifying. Another live notification of the
     * same app and island key may take over (same id, so HyperOS updates in place); otherwise the
     * island is cancelled. Caller holds [active].
     */
    private fun releaseOwner(bridge: AppBridge, ikey: String, ownerKey: String, pkg: String) {
        val old = active.remove(ikey) ?: return
        val candidates = liveNotifications().orEmpty()
            .filter { it.packageName == pkg && it.key != ownerKey && bridge.islandKeyFor(it) == ikey }
            .sortedByDescending { it.postTime }
        for (c in candidates) {
            applyLocked(bridge, c, pkg)
            if (active[ikey] != null) {
                if (inspector) NotificationLog.event(this, "island handed over [$ikey]: ${ownerKey} -> ${c.key}")
                return
            }
        }
        cancelOurs(old.id)
        firstSeen.remove(ikey)
        if (inspector) NotificationLog.event(this, "island dropped [$ikey]: owner $ownerKey gone")
    }

    /**
     * Re-derives every island from the notifications that are live right now and cancels the ones
     * whose owner has gone. Cheap, so it is also run when the main screen comes to the foreground.
     */
    fun resync() {
        val live = liveNotifications() ?: return
        val liveKeys = live.filter { it.packageName != packageName }.mapTo(HashSet()) { it.key }
        synchronized(active) {
            val orphans = active.entries.filter { it.value.sourceKey !in liveKeys }.map { it.key to it.value }
            for ((ikey, island) in orphans) {
                val bridge = bridgeByPackage[island.pkg]
                if (bridge != null) releaseOwner(bridge, ikey, island.sourceKey, island.pkg)
                else { active.remove(ikey); cancelOurs(island.id); firstSeen.remove(ikey) }
            }
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
        val snapshot = synchronized(active) { active.values.firstOrNull { it.sourceKey == sourceKey } }
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
        // HyperOS collapses the island after a button press. For toggles the activity goes on,
        // so bring the island back; the source app's next update will refresh its content.
        val role = snapshotAction?.role
        val terminal = role == ActionRole.HANGUP || role == ActionRole.DECLINE || role == ActionRole.CANCEL
        if (snapshot != null && !terminal) {
            mainHandler.postDelayed({ repost(sourceKey) }, 700)
        }
    }

    private fun repost(sourceKey: String) {
        val sbn = try { activeNotifications?.firstOrNull { it.key == sourceKey } } catch (_: Exception) { null } ?: return
        synchronized(active) { active.entries.removeIf { it.value.sourceKey == sourceKey } }
        handlePosted(sbn)
    }

    private fun liveNotifications(): Array<StatusBarNotification>? = try { activeNotifications } catch (_: Exception) { null }

    private fun cancelOurs(id: Int) {
        ourCancels.add(id)
        poster.cancel(id)
    }

    private fun dndActive(): Boolean = try {
        val filter = currentInterruptionFilter
        filter != INTERRUPTION_FILTER_ALL && filter != INTERRUPTION_FILTER_UNKNOWN
    } catch (_: Exception) {
        false
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        reloadPrefs()
        // Drop islands of apps that were just disabled, or everything when paused.
        synchronized(active) {
            val it = active.entries.iterator()
            while (it.hasNext()) {
                val (ikey, island) = it.next()
                if (paused || island.pkg !in enabled) {
                    cancelOurs(island.id)
                    it.remove()
                    firstSeen.remove(ikey)
                }
            }
        }
    }

    private fun reloadPrefs() {
        enabled = bridges.filter { settings.isAppEnabled(it.packageName) }.flatMapTo(HashSet()) { it.packages }
        inspector = settings.inspectorEnabled
        paused = settings.paused
        respectDnd = settings.respectDnd
    }

    private fun idFor(key: String): Int = ID_BASE + (key.hashCode() and 0x0FFFFFFF)

    companion object {
        private const val ID_BASE = 1000
        private const val HANDOVER_MS = 400L

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
