package xyz.samukis.hyperoscompat.bridge.progress

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.CANCEL
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.ActionRole.PAUSE
import xyz.samukis.hyperoscompat.bridge.ActionRole.RESUME
import xyz.samukis.hyperoscompat.bridge.BridgeCategory
import xyz.samukis.hyperoscompat.bridge.IslandAction
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.SessionBridge
import xyz.samukis.hyperoscompat.bridge.Text

/**
 * Uploads and downloads for any app: Drive, Photos, Mi Cloud, Play Store, Instagram story posts.
 * Only notifications that carry a progress bar (or a percentage in their text) qualify.
 *
 * One island per app however many transfers run at once (Photos backing up N files, Play Store
 * updating N apps): each transfer notification updates a table, and the island shows either the
 * single transfer or "N in progress" with the average percentage. It goes away when all finish.
 */
class ProgressBridge(
    context: Context,
    packageName: String,
    @StringRes nameRes: Int,
    @StringRes descriptionRes: Int,
    accentColor: String,
) : SessionBridge(context, packageName, nameRes, descriptionRes, BridgeCategory.TRANSFERS, accentColor) {

    private class Transfer(
        val title: String,
        val detail: String?,
        val percent: Int?,
        val actions: List<IslandAction>,
        val updatedAt: Long,
    )

    /** Source notification key -> transfer, in arrival order. */
    private val transfers = LinkedHashMap<String, Transfer>()

    override val staleMs: Long = 2 * 60 * 60_000L

    override fun onEnd() = transfers.clear()

    override fun onRemoved(sbn: StatusBarNotification) {
        if (transfers.remove(sbn.key) != null && sbn.key != ownerKey) requestRefresh()
        super.onRemoved(sbn)
    }

    override fun session(x: Notif, firstSeen: Long, now: Long): IslandSpec? {
        val key = x.sbn.key
        val t = parse(x, now)
        if (t == null) {
            // Finished, failed or not a transfer: it leaves the table; the others keep the island.
            if (transfers.remove(key) != null && key != ownerKey) requestRefresh()
        } else {
            transfers[key] = t
        }
        // A transfer that has not moved for a while is stuck or was dropped without notice.
        transfers.entries.removeIf { now - it.value.updatedAt > STUCK_MS }
        if (transfers.isEmpty()) return null

        claim(x, now)
        return if (transfers.size == 1) single(transfers.values.first()) else combined(transfers.values.toList())
    }

    private fun parse(x: Notif, now: Long): Transfer? {
        val textPercent = Text.percent(x.haystack)
        val hasBar = x.progressMax > 0 || x.indeterminate
        if (!hasBar && textPercent == null) return null
        val percent = when {
            x.progressMax > 0 -> x.progress * 100 / x.progressMax
            textPercent != null -> textPercent
            else -> null
        }?.coerceIn(0, 100)
        if ((percent != null && percent >= 100) || kw.looksProgressDone(x.haystack)) return null
        // Buttons are stored as direct intents (sourceIndex -1) so they stay valid whichever
        // transfer notification owns the island.
        val actions = x.pick(arrayOf(PAUSE, RESUME, CANCEL, OTHER)).map { IslandAction(it.role, it.title, null, it.intent) }
        return Transfer(
            title = x.title.ifEmpty { appName },
            detail = x.text.ifEmpty { x.sub }.ifEmpty { null },
            percent = percent,
            actions = actions,
            updatedAt = now,
        )
    }

    private fun single(t: Transfer) = IslandSpec(
        kind = IslandKind.PROGRESS,
        title = t.title,
        subtitle = t.detail,
        progress = t.percent,
        trailing = t.percent?.let { "$it%" },
        actions = t.actions,
        accentColor = accentColor,
        expandFirst = false,
    )

    private fun combined(all: List<Transfer>): IslandSpec {
        val known = all.mapNotNull { it.percent }
        val avg = if (known.isEmpty()) null else known.sum() / known.size
        return IslandSpec(
            kind = IslandKind.PROGRESS,
            title = str(R.string.transfers_count, all.size),
            subtitle = all.last().title,
            progress = avg,
            trailing = avg?.let { "$it%" },
            accentColor = accentColor,
            expandFirst = false,
        )
    }

    companion object {
        private const val STUCK_MS = 15 * 60_000L
    }
}
