package xyz.samukis.hyperoscompat.island

import android.content.Context
import android.media.AudioManager

/**
 * System-level microphone mute, used when the source app offers no mute action of its own.
 *
 * Discord listens to the system mute flag and mirrors it as its own self-mute, but it also appears
 * to reset the flag after reading it, so the flag cannot be trusted as our state. We keep our own
 * notion of "muted by us" and only ever issue explicit mute / un-mute transitions from it.
 */
object MicMute {
    @Volatile var mutedByUs = false
        private set

    /** Our view of the state, which is what the island button reflects. */
    fun isMuted(): Boolean = mutedByUs

    fun systemFlag(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).isMicrophoneMute

    /** Explicit transition. Returns a diagnostic line for the inspector. */
    fun set(context: Context, mute: Boolean): String {
        val am = context.getSystemService(AudioManager::class.java)
        val before = am.isMicrophoneMute
        am.isMicrophoneMute = mute
        mutedByUs = mute
        return "mic set=$mute (flag before=$before, after=${am.isMicrophoneMute})"
    }

    fun toggle(context: Context): String = set(context, !mutedByUs)

    /** Un-mutes only if the mute was ours. Safe to call often. */
    fun release(context: Context) {
        if (!mutedByUs) return
        mutedByUs = false
        try {
            context.getSystemService(AudioManager::class.java).isMicrophoneMute = false
        } catch (_: Exception) {
        }
    }
}
