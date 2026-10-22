package xyz.samukis.hyperoscompat.bridge

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes

/**
 * How HyperOS should lay the island out.
 * INCOMING: ringing call, expanded first, two buttons.
 * TIMER: something running with a count-up clock (call, recording).
 * PROGRESS: a transfer with a percentage (or indeterminate).
 * STATUS: a live state with an optional trailing value (ETA, "Arrived") and progress bar.
 */
enum class IslandKind { INCOMING, TIMER, PROGRESS, STATUS }

enum class ActionRole {
    ANSWER, DECLINE, HANGUP, JOIN,
    MUTE, UNMUTE, DEAFEN, CAMERA, SPEAKER,
    CANCEL, PAUSE, RESUME,
    CALL, MESSAGE, SHARE,
    OTHER,
}

class IslandAction(
    val role: ActionRole,
    val title: CharSequence,
    val icon: Icon?,
    val intent: PendingIntent,
    /** Index of this action inside the source notification's actions array; -1 when not from it. */
    val sourceIndex: Int = -1,
    /** Glyph drawn on the round button; 0 = pick from [role]. */
    val iconRes: Int = 0,
    /** Button background; null = pick from [role]. */
    val bgColor: String? = null,
)

/** Everything the poster needs to render an island. Produced by an [AppBridge]. */
class IslandSpec(
    val kind: IslandKind,
    val title: String,
    val subtitle: String? = null,
    /** TIMER: epoch millis the clock counts from; 0 = now. */
    val startTime: Long = 0L,
    /** PROGRESS / STATUS: 0..100, or null for none (indeterminate for PROGRESS). */
    val progress: Int? = null,
    /** Short text for the summary pill's right side: "3 min", "42%", "18:45". */
    val trailing: String? = null,
    /** Emphasised chip in the expanded card, e.g. "PIN 4821". */
    val highlight: String? = null,
    /** Text next to the icon in the summary pill; null = [title]. */
    val pillText: String? = null,
    /** Secondary line of the expanded card; null = [trailing]. */
    val detail: String? = null,
    /** Glyphs riding on and ending the progress bar (STATUS only); 0 = none. */
    @DrawableRes val progressGlyph: Int = 0,
    @DrawableRes val progressEndGlyph: Int = 0,
    val actions: List<IslandAction> = emptyList(),
    val avatar: Icon? = null,
    val accentColor: String,
    /** Show the expanded island the first time it appears. */
    val expandFirst: Boolean = kind == IslandKind.INCOMING,
    /** Expand the island on this particular update (e.g. "driver arrived"). */
    val popOnUpdate: Boolean = false,
    /** 0 = live until the source notification goes away; otherwise auto-dismiss after this long. */
    val autoDismissMs: Long = 0L,
) {
    /** Cheap fingerprint so identical updates from the source app are skipped. */
    fun contentHash(): Int {
        var h = kind.ordinal
        h = h * 31 + title.hashCode()
        h = h * 31 + (subtitle?.hashCode() ?: 0)
        h = h * 31 + (startTime / 1000L).hashCode()
        h = h * 31 + (progress ?: -1)
        h = h * 31 + (trailing?.hashCode() ?: 0)
        h = h * 31 + (highlight?.hashCode() ?: 0)
        h = h * 31 + (pillText?.hashCode() ?: 0)
        h = h * 31 + (detail?.hashCode() ?: 0)
        h = h * 31 + progressGlyph + progressEndGlyph
        h = h * 31 + popOnUpdate.hashCode()
        h = h * 31 + (avatar != null).hashCode()
        for (a in actions) {
            h = h * 31 + a.role.ordinal
            h = h * 31 + a.title.toString().hashCode()
            h = h * 31 + a.iconRes
        }
        return h
    }
}
