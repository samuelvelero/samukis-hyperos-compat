package xyz.samukis.hyperoscompat.bridge

import android.app.Notification
import android.service.notification.StatusBarNotification

/** A source notification parsed once: texts, flags, and its actions classified into roles. */
class Notif(val sbn: StatusBarNotification, kw: Keywords) {
    val n: Notification = sbn.notification
    private val e = n.extras

    val title: String = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val text: String = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val sub: String = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
    val big: String = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
    val template: String = e.getString(Notification.EXTRA_TEMPLATE).orEmpty()

    val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
    val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
    val chrono = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
    val whenMs = n.`when`
    val isCallCategory = n.category == Notification.CATEGORY_CALL || template.endsWith("CallStyle")
    val isMissedCall = n.category == Notification.CATEGORY_MISSED_CALL
    /** Chat messages: MessagingStyle, inbox or the message category. Never a call unless CallStyle says so. */
    val isMessage = !isCallCategory && (
        n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL ||
            n.category == Notification.CATEGORY_SOCIAL || template.contains("MessagingStyle") || template.contains("InboxStyle")
        )

    /** Android 16 Live Update status-bar chip text ("3 min"), when the app provides one. */
    val shortCritical: String? = e.getCharSequence("android.shortCriticalText")?.toString()?.trim()?.ifEmpty { null }

    val progressMax = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
    val progress = e.getInt(Notification.EXTRA_PROGRESS, 0)
    val indeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    /** All visible text, lower-cased, for keyword checks. */
    val haystack: String = listOf(title, text, sub, big).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    val actions: List<Notification.Action> = n.actions?.filter { it.actionIntent != null }.orEmpty()
    /** A button with a text field is a quick reply ("Responder"), never an answer-call button. */
    val roles: List<ActionRole> = actions.map { a ->
        if (a.remoteInputs?.isNotEmpty() == true) ActionRole.OTHER else kw.roleOf(a.title)
    }

    fun has(role: ActionRole) = role in roles

    private fun action(i: Int) = IslandAction(roles[i], actions[i].title ?: "", null, actions[i].actionIntent, sourceIndex = i)

    /**
     * One button per slot. Each slot lists roles in order of preference; the first action matching
     * any of them (and not already used by an earlier slot) wins. Slots with no match are skipped.
     */
    fun pick(vararg slots: Array<out ActionRole>): List<IslandAction> {
        val used = HashSet<Int>()
        val out = ArrayList<IslandAction>(slots.size)
        for (slot in slots) {
            val i = slot.firstNotNullOfOrNull { role -> roles.indices.firstOrNull { roles[it] == role && it !in used } } ?: continue
            used += i
            out += action(i)
        }
        return out
    }
}

/** Small regex helpers over free text. */
object Text {
    private val minutes = Regex("""(\d{1,3})\s*(?:min\b|mins\b|minutos?\b|minutes?\b)""")
    private val percent = Regex("""\b(\d{1,3})\s*%""")
    private val clock = Regex("""\b(\d{1,2}:\d{2})\b""")
    private val clock12 = Regex("""\b(\d{1,2}):([0-5]\d)\s*([ap])\.?\s?m\b\.?""", RegexOption.IGNORE_CASE)
    private val clock24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val pin = Regex("""\b(?:pin|c[oó]digo|code)\b\D{0,20}?(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)

    /** A time of day found in text, as shown ("18:45", "6:45 pm") and as the next matching instant. */
    data class Clock(val text: String, val millis: Long)

    fun minutes(s: String): Int? = minutes.find(s)?.groupValues?.get(1)?.toIntOrNull()
    fun percent(s: String): Int? = percent.find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    fun clock(s: String): String? = clock.find(s)?.groupValues?.get(1)
    fun pin(s: String): String? = pin.find(s)?.groupValues?.get(1)

    fun clockTime(s: String, now: Long = System.currentTimeMillis()): Clock? {
        val hour: Int
        val minute: Int
        val text: String
        val m12 = clock12.find(s)
        if (m12 != null) {
            val raw = m12.groupValues[1].toInt()
            if (raw !in 1..12) return null
            val pm = m12.groupValues[3].equals("p", ignoreCase = true)
            hour = raw % 12 + if (pm) 12 else 0
            minute = m12.groupValues[2].toInt()
            text = "$raw:${m12.groupValues[2]} ${if (pm) "pm" else "am"}"
        } else {
            val m24 = clock24.find(s) ?: return null
            hour = m24.groupValues[1].toInt()
            minute = m24.groupValues[2].toInt()
            text = "%d:%02d".format(hour, minute)
        }
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        // "Arriving at 00:10" seen at 23:50 means tomorrow.
        if (cal.timeInMillis < now - 15 * 60_000L) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return Clock(text, cal.timeInMillis)
    }
}
