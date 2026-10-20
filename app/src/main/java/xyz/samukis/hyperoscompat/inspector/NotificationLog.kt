package xyz.samukis.hyperoscompat.inspector

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import xyz.samukis.hyperoscompat.prefs.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only text log of the notifications we matched, used to tune the translators against the
 * real payloads an app sends. Only written when the user enables the inspector.
 */
object NotificationLog {
    private const val FILE = "inspector.log"
    private const val MAX_BYTES = 200_000L
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun record(context: Context, sbn: StatusBarNotification) {
        try {
            val f = File(context.filesDir, FILE)
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep)
            }
            f.appendText(format(sbn))
        } catch (_: Exception) {
        }
    }

    /** One-line event (removal, action click, resync) for the same log. Only when the inspector is on. */
    fun event(context: Context, text: String) {
        if (!Settings(context).inspectorEnabled) return
        try {
            File(context.filesDir, FILE).appendText("-- ${fmt.format(Date())} $text\n\n")
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String = try {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun format(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val e = n.extras
        val sb = StringBuilder()
        sb.append("== ").append(fmt.format(Date())).append(' ').append(sbn.packageName).append('\n')
        sb.append("key=").append(sbn.key).append(" id=").append(sbn.id).append('\n')
        sb.append("category=").append(n.category)
            .append(" template=").append(e.getString(Notification.EXTRA_TEMPLATE))
            .append(" channel=").append(n.channelId).append('\n')
        sb.append("flags: ongoing=").append(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .append(" summary=").append(n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .append(" fgs=").append(n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            .append(" when=").append(n.`when`)
            .append(" chrono=").append(e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            .append(" largeIcon=").append(n.getLargeIcon() != null).append('\n')
        sb.append("title=").append(e.getCharSequence(Notification.EXTRA_TITLE)).append('\n')
        sb.append("text=").append(e.getCharSequence(Notification.EXTRA_TEXT)).append('\n')
        sb.append("subText=").append(e.getCharSequence(Notification.EXTRA_SUB_TEXT)).append('\n')
        sb.append("bigText=").append(e.getCharSequence(Notification.EXTRA_BIG_TEXT)).append('\n')
        n.actions?.forEachIndexed { i, a ->
            sb.append("action[").append(i).append("]=").append(a.title)
                .append(" semantic=").append(a.semanticAction).append('\n')
        }
        sb.append("extras: ").append(summarize(e)).append("\n\n")
        return sb.toString()
    }

    private fun summarize(b: Bundle): String = b.keySet().sorted().joinToString(", ") { k ->
        @Suppress("DEPRECATION")
        when (val v = b.get(k)) {
            null -> "$k=null"
            is CharSequence, is Boolean, is Int, is Long, is Float, is Double -> "$k=$v"
            else -> "$k:${v.javaClass.simpleName}"
        }
    }
}
