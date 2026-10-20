package xyz.samukis.hyperoscompat.bridge.delivery

import android.content.Context
import androidx.annotation.StringRes
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
 * Food delivery (PedidosYa, Rappi): order status pushes become a short-lived status island with
 * the ETA when the text has one. Promotions are filtered out. Untested on device so far: tune
 * the keyword lists from the inspector once real orders have been logged.
 */
class DeliveryBridge(
    context: Context,
    packageName: String,
    @StringRes nameRes: Int,
    accentColor: String,
) : BaseBridge(context, packageName, nameRes, accentColor) {

    override fun translate(x: Notif, firstSeen: Long): IslandSpec? {
        val h = x.haystack
        if (!x.ongoing && (!kw.looksDelivery(h) || kw.looksPromo(h))) return null

        val done = kw.looksDeliveryDone(h)
        val eta = Text.minutes(h)
        return IslandSpec(
            kind = IslandKind.STATUS,
            title = x.title.ifEmpty { appName },
            subtitle = x.text.ifEmpty { null },
            trailing = if (!done && eta != null) str(R.string.minutes_short, eta) else null,
            actions = x.pick(arrayOf(CALL, MESSAGE), arrayOf(SHARE, OTHER)),
            avatar = x.n.getLargeIcon(),
            accentColor = accentColor,
            expandFirst = true,
            autoDismissMs = when {
                x.ongoing -> 0L
                done -> 2 * 60_000L
                else -> 12 * 60_000L
            },
        )
    }
}
