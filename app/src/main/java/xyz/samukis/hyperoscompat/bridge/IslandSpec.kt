package xyz.samukis.hyperoscompat.bridge

import android.app.PendingIntent
import android.graphics.drawable.Icon

enum class CallState { INCOMING, ONGOING }

enum class ActionRole { ANSWER, DECLINE, HANGUP, MUTE, OTHER }

class IslandAction(
    val role: ActionRole,
    val title: CharSequence,
    val icon: Icon?,
    val intent: PendingIntent,
    /** Index of this action inside the source notification's actions array; -1 when not from a source. */
    val sourceIndex: Int = -1,
)

/** Everything the poster needs to render an island. Produced by an [AppBridge]. */
class IslandSpec(
    val state: CallState,
    val title: String,
    val subtitle: String?,
    /** Epoch millis when the call started; 0 when unknown. */
    val startTime: Long,
    val actions: List<IslandAction>,
    val avatar: Icon?,
    val accentColor: String,
) {
    /** Cheap fingerprint so identical updates from the source app are skipped. */
    fun contentHash(): Int {
        var h = state.ordinal
        h = h * 31 + title.hashCode()
        h = h * 31 + (subtitle?.hashCode() ?: 0)
        h = h * 31 + (startTime / 1000L).hashCode()
        h = h * 31 + (avatar != null).hashCode()
        for (a in actions) {
            h = h * 31 + a.role.ordinal
            h = h * 31 + a.title.toString().hashCode()
        }
        return h
    }
}
