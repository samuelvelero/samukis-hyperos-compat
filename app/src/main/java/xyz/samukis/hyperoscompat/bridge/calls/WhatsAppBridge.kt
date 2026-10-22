package xyz.samukis.hyperoscompat.bridge.calls

import android.content.Context
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole.CANCEL
import xyz.samukis.hyperoscompat.bridge.ActionRole.HANGUP
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif

/**
 * WhatsApp and WhatsApp Business: incoming and ongoing calls, plus the voice-note recording
 * notification with its cancel button. Plain messages never reach the island.
 */
class WhatsAppBridge(context: Context) : CallBridge(
    context,
    packageName = "com.whatsapp",
    nameRes = R.string.app_whatsapp,
    descriptionRes = R.string.desc_whatsapp,
    accentColor = "#25D366",
    packages = setOf("com.whatsapp", "com.whatsapp.w4b"),
) {
    override fun other(x: Notif, firstSeen: Long, now: Long): IslandSpec? {
        if (callActive || !kw.looksRecording(x.haystack)) return null
        claim(x, now)
        return IslandSpec(
            kind = IslandKind.TIMER,
            title = str(R.string.recording_voice_note),
            subtitle = x.title.ifEmpty { null },
            startTime = if (x.chrono && x.whenMs > 0) x.whenMs else firstSeen,
            actions = x.pick(arrayOf(CANCEL, HANGUP, OTHER)),
            accentColor = accentColor,
            expandFirst = false,
        )
    }
}
