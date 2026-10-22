package xyz.samukis.hyperoscompat.bridge.delivery

import android.content.Context
import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.CALL
import xyz.samukis.hyperoscompat.bridge.ActionRole.MESSAGE
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.ActionRole.SHARE
import xyz.samukis.hyperoscompat.bridge.BridgeCategory
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.SessionBridge
import xyz.samukis.hyperoscompat.bridge.Text

/**
 * Food delivery (PedidosYa, Rappi), one island per order:
 * - a persistent tracking notification, when the app shows one, owns the island; status pushes
 *   then only feed it (ETA, call / chat buttons);
 * - without one, each status push updates the same island in place and pops it open briefly.
 * The ETA is remembered as a time of arrival, so a later push without minutes still counts down.
 * Promotions are filtered out. Untested on device so far: tune the keywords from the inspector.
 */
class DeliveryBridge(
    context: Context,
    packageName: String,
    @StringRes nameRes: Int,
    @StringRes descriptionRes: Int,
    accentColor: String,
) : SessionBridge(context, packageName, nameRes, descriptionRes, BridgeCategory.TRANSPORT, accentColor) {

    /** Expected arrival (epoch millis), 0 = unknown. */
    private var etaAt = 0L
    /** The island is owned by a persistent tracking notification. */
    private var trackedByOngoing = false

    override val staleMs: Long = 3 * 60 * 60_000L

    override fun onEnd() {
        etaAt = 0L
        trackedByOngoing = false
    }

    override fun session(x: Notif, firstSeen: Long, now: Long): IslandSpec? {
        val h = x.haystack
        if (!x.ongoing && (!kw.looksDelivery(h) || kw.looksPromo(h))) return null

        val done = kw.looksDeliveryDone(h)
        Text.minutes(h)?.let { etaAt = now + it * 60_000L }

        if (!x.ongoing && trackedByOngoing && x.sbn.key != ownerKey) {
            // The tracking notification stays the owner; this push only brings news.
            borrow(x, CALL, MESSAGE, SHARE)
            touch(now)
            requestRefresh()
            return null
        }
        claim(x, now)
        if (x.ongoing) trackedByOngoing = true

        val minutesLeft = if (etaAt > now) ((etaAt - now + 59_999L) / 60_000L).toInt() else null
        val own = x.pick(arrayOf(CALL, MESSAGE), arrayOf(SHARE, OTHER))
        val contact = own.firstOrNull { it.role == CALL || it.role == MESSAGE } ?: borrowed[CALL] ?: borrowed[MESSAGE]
        val other = own.firstOrNull { it.role == SHARE || it.role == OTHER } ?: borrowed[SHARE]

        val spec = IslandSpec(
            kind = IslandKind.STATUS,
            title = x.title.ifEmpty { appName },
            subtitle = x.text.ifEmpty { null },
            trailing = if (!done && minutesLeft != null) str(R.string.minutes_short, minutesLeft) else null,
            actions = listOfNotNull(contact, other),
            avatar = x.n.getLargeIcon(),
            accentColor = accentColor,
            expandFirst = true,
            popOnUpdate = !x.ongoing,
            autoDismissMs = when {
                x.ongoing -> 0L
                done -> 2 * 60_000L
                else -> 12 * 60_000L
            },
        )
        if (done) endSession()
        return spec
    }
}
