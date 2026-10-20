package xyz.samukis.hyperoscompat.island

import android.content.Context
import android.media.AudioManager

/**
 * System-level microphone mute, used when the source app offers no mute action of its own.
 * Discord keeps rendering itself as unmuted; what changes is that the mic feeds silence.
 * We only ever un-mute what we muted, and we release it as soon as the call island goes away.
 */
object MicMute {
    @Volatile private var mutedByUs = false

    fun isMuted(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).isMicrophoneMute

    /** Flips the mute state. Returns the new state. */
    fun toggle(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val target = !am.isMicrophoneMute
        am.isMicrophoneMute = target
        mutedByUs = target
        return am.isMicrophoneMute
    }

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
