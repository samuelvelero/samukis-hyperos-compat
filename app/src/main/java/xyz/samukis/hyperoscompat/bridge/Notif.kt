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

    val progressMax = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
    val progress = e.getInt(Notification.EXTRA_PROGRESS, 0)
    val indeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    /** All visible text, lower-cased, for keyword checks. */
    val haystack: String = listOf(title, text, sub, big).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    val actions: List<Notification.Action> = n.actions?.filter { it.actionIntent != null }.orEmpty()
    val roles: List<ActionRole> = actions.map { kw.roleOf(it.title) }

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
    private val pin = Regex("""\bpin\b\D{0,10}(\d{4})""", RegexOption.IGNORE_CASE)

    fun minutes(s: String): Int? = minutes.find(s)?.groupValues?.get(1)?.toIntOrNull()
    fun percent(s: String): Int? = percent.find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    fun clock(s: String): String? = clock.find(s)?.groupValues?.get(1)
    fun pin(s: String): String? = pin.find(s)?.groupValues?.get(1)
}
