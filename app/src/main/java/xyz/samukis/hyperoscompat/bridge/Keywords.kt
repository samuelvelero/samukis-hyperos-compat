package xyz.samukis.hyperoscompat.bridge

import android.content.Context
import androidx.annotation.ArrayRes
import xyz.samukis.hyperoscompat.R

/**
 * Keyword lists live in res/values/arrays.xml so new languages are a resource change.
 * One shared instance: the lists are loaded once per process.
 */
class Keywords private constructor(appContext: Context) {
    private val resources = appContext.resources
    private fun list(@ArrayRes res: Int) = resources.getStringArray(res).map { it.lowercase() }

    private val answer = list(R.array.kw_answer)
    private val decline = list(R.array.kw_decline)
    private val hangup = list(R.array.kw_hangup)
    private val join = list(R.array.kw_join)
    private val mute = list(R.array.kw_mute)
    private val unmute = list(R.array.kw_unmute)
    private val deafen = list(R.array.kw_deafen)
    private val camera = list(R.array.kw_camera)
    private val speaker = list(R.array.kw_speaker)
    private val cancel = list(R.array.kw_cancel)
    private val pause = list(R.array.kw_pause)
    private val resume = list(R.array.kw_resume)
    private val call = list(R.array.kw_call)
    private val message = list(R.array.kw_message)
    private val share = list(R.array.kw_share)

    private val incoming = list(R.array.kw_incoming)
    private val ongoing = list(R.array.kw_ongoing)
    private val ended = list(R.array.kw_ended)
    private val generic = list(R.array.kw_generic)
    private val ride = list(R.array.kw_ride)
    private val arrived = list(R.array.kw_arrived)
    private val tripOver = list(R.array.kw_trip_over)
    private val delivery = list(R.array.kw_delivery)
    private val deliveryDone = list(R.array.kw_delivery_done)
    private val promo = list(R.array.kw_promo)
    private val meetingSoon = list(R.array.kw_meeting_soon)
    private val recording = list(R.array.kw_recording)
    private val progressDone = list(R.array.kw_progress_done)

    /**
     * Order matters: "Desactivar ensordecimiento" must land on DEAFEN before anything else,
     * "Desactivar silencio" / "Unmute" on UNMUTE before the plain MUTE substrings, "End call"
     * on HANGUP before CALL, "Answer video" on ANSWER before CAMERA.
     */
    fun roleOf(title: CharSequence?): ActionRole {
        val t = title?.toString()?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return ActionRole.OTHER
        return when {
            t.hasAny(deafen) -> ActionRole.DEAFEN
            t.hasAny(unmute) -> ActionRole.UNMUTE
            t.hasAny(hangup) -> ActionRole.HANGUP
            t.hasAny(decline) -> ActionRole.DECLINE
            t.hasAny(cancel) -> ActionRole.CANCEL
            t.hasAny(answer) -> ActionRole.ANSWER
            t.hasAny(join) -> ActionRole.JOIN
            t.hasAny(mute) -> ActionRole.MUTE
            t.hasAny(camera) -> ActionRole.CAMERA
            t.hasAny(speaker) -> ActionRole.SPEAKER
            t.hasAny(pause) -> ActionRole.PAUSE
            t.hasAny(resume) -> ActionRole.RESUME
            t.hasAny(share) -> ActionRole.SHARE
            t.hasAny(message) -> ActionRole.MESSAGE
            t.hasAny(call) -> ActionRole.CALL
            else -> ActionRole.OTHER
        }
    }

    fun looksIncoming(text: String) = text.hasAny(incoming)
    fun looksOngoing(text: String) = text.hasAny(ongoing)
    fun looksEnded(text: String) = text.hasAny(ended)
    /** Boilerplate an app shows instead of a channel name ("Tap to return to the call"). */
    fun looksGeneric(text: String) = text.hasAny(generic)
    fun looksRide(text: String) = text.hasAny(ride)
    fun looksArrived(text: String) = text.hasAny(arrived)
    fun looksTripOver(text: String) = text.hasAny(tripOver)
    fun looksDelivery(text: String) = text.hasAny(delivery)
    fun looksDeliveryDone(text: String) = text.hasAny(deliveryDone)
    fun looksPromo(text: String) = text.hasAny(promo)
    fun looksMeetingSoon(text: String) = text.hasAny(meetingSoon)
    fun looksRecording(text: String) = text.hasAny(recording)
    fun looksProgressDone(text: String) = text.hasAny(progressDone)

    private fun String.hasAny(words: List<String>) = words.any { contains(it) }

    companion object {
        @Volatile private var shared: Keywords? = null

        fun get(context: Context): Keywords = shared ?: synchronized(this) {
            shared ?: Keywords(context.applicationContext).also { shared = it }
        }
    }
}
