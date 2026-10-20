package xyz.samukis.hyperoscompat.bridge.progress

import android.content.Context
import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.bridge.ActionRole.CANCEL
import xyz.samukis.hyperoscompat.bridge.ActionRole.OTHER
import xyz.samukis.hyperoscompat.bridge.ActionRole.PAUSE
import xyz.samukis.hyperoscompat.bridge.ActionRole.RESUME
import xyz.samukis.hyperoscompat.bridge.BaseBridge
import xyz.samukis.hyperoscompat.bridge.IslandKind
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.Text

/**
 * Uploads and downloads for any app: Drive, Photos, Mi Cloud, Play Store, Instagram story posts.
 * Only notifications that carry a progress bar (or a percentage in their text) qualify; the
 * island shows the percentage ring and the app's own "N min left" text, and goes away on completion.
 */
class ProgressBridge(
    context: Context,
    packageName: String,
    @StringRes nameRes: Int,
    accentColor: String,
) : BaseBridge(context, packageName, nameRes, accentColor) {

    override fun translate(x: Notif, firstSeen: Long): IslandSpec? {
        val textPercent = Text.percent(x.haystack)
        val hasBar = x.progressMax > 0 || x.indeterminate
        if (!hasBar && textPercent == null) return null

        val percent = when {
            x.progressMax > 0 -> x.progress * 100 / x.progressMax
            textPercent != null -> textPercent
            else -> null
        }?.coerceIn(0, 100)

        // Finished (or failed): the source usually leaves a summary notification; no island for it.
        if ((percent != null && percent >= 100) || kw.looksProgressDone(x.haystack)) return null

        val detail = x.text.ifEmpty { x.sub }
        return IslandSpec(
            kind = IslandKind.PROGRESS,
            title = x.title.ifEmpty { appName },
            subtitle = detail.ifEmpty { null },
            progress = percent,
            trailing = percent?.let { "$it%" },
            actions = x.pick(arrayOf(PAUSE, RESUME, CANCEL, OTHER)),
            accentColor = accentColor,
            expandFirst = false,
        )
    }
}
