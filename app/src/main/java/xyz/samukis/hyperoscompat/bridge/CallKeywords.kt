package xyz.samukis.hyperoscompat.bridge

import android.content.Context
import androidx.annotation.ArrayRes
import xyz.samukis.hyperoscompat.R

/** Keyword lists live in res/values/arrays.xml so new languages are a resource change. */
class CallKeywords(context: Context) {
    private val answer = load(context, R.array.kw_answer)
    private val decline = load(context, R.array.kw_decline)
    private val hangup = load(context, R.array.kw_hangup)
    private val mute = load(context, R.array.kw_mute)
    private val incoming = load(context, R.array.kw_incoming)
    private val ongoing = load(context, R.array.kw_ongoing)
    private val ended = load(context, R.array.kw_ended)

    fun roleOf(title: CharSequence?): ActionRole {
        val t = title?.toString()?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return ActionRole.OTHER
        return when {
            hangup.any { t.contains(it) } -> ActionRole.HANGUP
            decline.any { t.contains(it) } -> ActionRole.DECLINE
            answer.any { t.contains(it) } -> ActionRole.ANSWER
            mute.any { t.contains(it) } -> ActionRole.MUTE
            else -> ActionRole.OTHER
        }
    }

    fun looksIncoming(text: String): Boolean = incoming.any { text.contains(it) }
    fun looksOngoing(text: String): Boolean = ongoing.any { text.contains(it) }
    fun looksEnded(text: String): Boolean = ended.any { text.contains(it) }

    private fun load(context: Context, @ArrayRes res: Int): List<String> =
        context.resources.getStringArray(res).map { it.lowercase() }
}
