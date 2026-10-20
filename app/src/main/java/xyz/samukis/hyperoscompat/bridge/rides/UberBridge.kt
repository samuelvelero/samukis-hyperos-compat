package xyz.samukis.hyperoscompat.bridge.rides

import android.content.Context
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.CALL
import xyz.samukis.hyperoscompat.bridge.ActionRole.MESSAGE
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.ActionRole.SHARE
import xyz.samukis.hyperoscompat.bridge.BaseBridge
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.Text

/**
 * Uber rider app: driver on the way (ETA), arrival, PIN and the trip itself, with the
 * notification's own call / message / share buttons. Receipts, ratings and promos are ignored.
 * Heuristic until tuned against real payloads from the inspector.
 */
class UberBridge(context: Context) : BaseBridge(
    context,
    packageName = "com.ubercab",
    nameRes = R.string.app_uber,
    accentColor = "#FFFFFF",
) {
    override fun translate(x: Notif, firstSeen: Long): IslandSpec? {
        val h = x.haystack
        if (kw.looksTripOver(h)) return null
        if (!x.ongoing && (!kw.looksRide(h) || kw.looksPromo(h))) return null

        val arrived = kw.looksArrived(h)
        val eta = Text.minutes(h)
        val clock = Text.clock(h)
        val pin = Text.pin(h)

        val trailing = when {
            arrived -> str(R.string.ride_arrived)
            eta != null -> str(R.string.minutes_short, eta)
            clock != null -> clock
            else -> null
        }
        return IslandSpec(
            kind = IslandKind.STATUS,
            title = x.title.ifEmpty { appName },
            subtitle = x.text.ifEmpty { null },
            trailing = trailing,
            highlight = pin?.let { "PIN $it" },
            actions = x.pick(arrayOf(CALL, MESSAGE), arrayOf(SHARE, OTHER)),
            avatar = x.n.getLargeIcon(),
            accentColor = accentColor,
            expandFirst = arrived,
            autoDismissMs = if (x.ongoing) 0L else 15 * 60_000L,
        )
    }
}
