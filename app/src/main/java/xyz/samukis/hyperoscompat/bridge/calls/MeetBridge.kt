package xyz.samukis.hyperoscompat.bridge.calls

import android.content.Context
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.ANSWER
import xyz.samukis.hyperoscompat.bridge.ActionRole.JOIN
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.Text

/**
 * Google Meet (both the current app, formerly Duo, and the legacy Meet package).
 * In-call: timer, mute / camera, leave. Plus the "starts in N min" reminder with a Join button.
 */
class MeetBridge(context: Context) : CallBridge(
    context,
    packageName = "com.google.android.apps.tachyon",
    nameRes = R.string.app_meet,
    accentColor = "#00897B",
    packages = setOf("com.google.android.apps.tachyon", "com.google.android.apps.meetings"),
) {
    override fun other(x: Notif, firstSeen: Long): IslandSpec? {
        if (x.ongoing || !kw.looksMeetingSoon(x.haystack)) return null
        val mins = Text.minutes(x.haystack)
        return IslandSpec(
            kind = IslandKind.STATUS,
            title = x.title.ifEmpty { appName },
            subtitle = x.text.ifEmpty { str(R.string.meeting_soon) },
            trailing = mins?.let { str(R.string.minutes_short, it) } ?: str(R.string.meeting_soon),
            actions = x.pick(arrayOf(JOIN, ANSWER, OTHER)),
            accentColor = accentColor,
            expandFirst = true,
            autoDismissMs = 5 * 60_000L,
        )
    }
}
