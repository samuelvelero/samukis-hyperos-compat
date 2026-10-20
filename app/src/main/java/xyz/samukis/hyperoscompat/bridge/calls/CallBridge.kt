package xyz.samukis.hyperoscompat.bridge.calls

import android.content.Context
import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.ANSWER
import xyz.samukis.hyperoscompat.bridge.ActionRole.CAMERA
import xyz.samukis.hyperoscompat.bridge.ActionRole.DECLINE
import xyz.samukis.hyperoscompat.bridge.ActionRole.HANGUP
import xyz.samukis.hyperoscompat.bridge.ActionRole.JOIN
import xyz.samukis.hyperoscompat.bridge.ActionRole.MUTE
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.ActionRole.SPEAKER
import xyz.samukis.hyperoscompat.bridge.ActionRole.UNMUTE
import xyz.samukis.hyperoscompat.bridge.BaseBridge
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif

/**
 * Generic voice/video call translator: ringing (answer / decline) and in-call (mute / hang up)
 * with a count-up timer. Subclasses handle an app's non-call extras via [other] and can reshape
 * the texts via [displayTitles].
 */
open class CallBridge(
    context: Context,
    packageName: String,
    @StringRes nameRes: Int,
    accentColor: String,
    packages: Set<String> = setOf(packageName),
) : BaseBridge(context, packageName, nameRes, accentColor, packages) {

    override fun translate(x: Notif, firstSeen: Long): IslandSpec? {
        // "Missed call" / "Call ended" style notifications are history, not a live call.
        if (x.isMissedCall || kw.looksEnded(x.haystack)) return null

        // Chat messages (with their "Reply" / "Mark as read" buttons) are never calls.
        if (x.isMessage) return other(x, firstSeen)

        // Ringing needs real evidence: the call category, "incoming call" text, or a decline + answer pair.
        val ringing = x.isCallCategory || kw.looksIncoming(x.haystack) ||
            (x.has(DECLINE) && (x.has(ANSWER) || x.has(JOIN)))
        val isCall = ringing || x.has(HANGUP) || (x.ongoing && kw.looksOngoing(x.haystack))
        if (!isCall) return other(x, firstSeen)

        val incoming = ringing && !x.chrono && !x.has(HANGUP)
        // A live call is always an ongoing (non-dismissable) notification. Anything else that merely
        // looks call-related is not a reason to keep an island alive.
        if (!incoming && !x.ongoing && !x.chrono) return null

        val (title, subtitle) = displayTitles(x)
        return if (incoming) {
            IslandSpec(
                kind = IslandKind.INCOMING,
                title = title.ifEmpty { appName },
                subtitle = str(R.string.call_incoming),
                trailing = str(R.string.call_incoming),
                actions = x.pick(arrayOf(DECLINE, HANGUP), arrayOf(ANSWER, JOIN, OTHER)),
                avatar = x.n.getLargeIcon(),
                accentColor = accentColor,
            )
        } else {
            IslandSpec(
                kind = IslandKind.TIMER,
                title = title.ifEmpty { appName },
                subtitle = subtitle ?: str(R.string.call_ongoing),
                startTime = if (x.chrono && x.whenMs > 0) x.whenMs else firstSeen,
                actions = x.pick(arrayOf(MUTE, UNMUTE, CAMERA, SPEAKER), arrayOf(HANGUP, OTHER)),
                avatar = x.n.getLargeIcon(),
                accentColor = accentColor,
            )
        }
    }

    /** Hook for notifications of this app that are not calls (reminders, recordings). */
    protected open fun other(x: Notif, firstSeen: Long): IslandSpec? = null

    /** (title, subtitle) for the island; default is the notification's own title and text. */
    protected open fun displayTitles(x: Notif): Pair<String, String?> = x.title to x.text.ifEmpty { null }
}
