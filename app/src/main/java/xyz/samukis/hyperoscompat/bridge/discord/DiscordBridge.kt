package xyz.samukis.hyperoscompat.bridge.discord

import android.content.Context
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.Notif
import xyz.samukis.hyperoscompat.bridge.calls.CallBridge

/**
 * Discord voice. Observed on a real device (Oct 2026): the voice notification is ongoing, has no
 * chronometer and no large icon, title "<fase> — Toca para volver a la llamada" (fase: Esperando
 * al servidor de voz, Conectando, Autenticando, Conectando RTC, Voz conectada), text = channel
 * name, and three actions: "Desconectar", "Silenciar"/"Desactivar silencio",
 * "Ensordecer"/"Desactivar ensordecimiento". The generic call logic already picks
 * mute/unmute + disconnect; this class only makes the channel the headline.
 */
class DiscordBridge(context: Context) : CallBridge(
    context,
    packageName = PACKAGE,
    nameRes = R.string.app_discord,
    descriptionRes = R.string.desc_discord,
    accentColor = "#5865F2",
    tested = true,
) {
    /** Channel as title wherever it arrives, Discord's rotating boilerplate as subtitle. */
    override fun displayTitles(x: Notif): Pair<String, String?> {
        val (head, tail) = splitTitle(x.title)
        val candidates = listOf(head, tail.orEmpty(), x.text, x.sub).filter { it.isNotEmpty() }
        val channel = candidates.firstOrNull { !kw.looksGeneric(it.lowercase()) }
        val generic = candidates.firstOrNull { kw.looksGeneric(it.lowercase()) }
        val title = channel ?: head
        return title to generic?.takeIf { it != title }
    }

    /** Splits "Voz conectada — General" into ("General", "Voz conectada"). No separator: (title, null). */
    private fun splitTitle(raw: String): Pair<String, String?> {
        for (sep in SEPARATORS) {
            val i = raw.indexOf(sep)
            if (i > 0 && i + sep.length < raw.length) {
                return raw.substring(i + sep.length).trim() to raw.substring(0, i).trim()
            }
        }
        return raw to null
    }

    companion object {
        const val PACKAGE = "com.discord"
        private val SEPARATORS = listOf(" — ", " – ", " - ", " | ")
    }
}
