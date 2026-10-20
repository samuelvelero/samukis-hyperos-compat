package xyz.samukis.hyperoscompat.bridge.discord

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole
import xyz.samukis.hyperoscompat.bridge.AppBridge
import xyz.samukis.hyperoscompat.bridge.CallKeywords
import xyz.samukis.hyperoscompat.bridge.CallState
import xyz.samukis.hyperoscompat.bridge.IslandAction
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.island.MicMute
import xyz.samukis.hyperoscompat.receiver.MuteReceiver

/**
 * Discord: incoming calls (answer / decline) and ongoing voice sessions (mute / disconnect).
 * Detection is heuristic on purpose: Discord does not use CallStyle consistently, so we look at
 * the category, the action titles and the texts, in English and Spanish.
 *
 * Observed on a real device (Oct 2026): the voice notification is ongoing, has no chronometer,
 * a title like "Voz conectada — <canal>" and exactly one action, "Desconectar".
 */
class DiscordBridge(private val context: Context) : AppBridge {
    override val packageName = PACKAGE
    override val nameRes = R.string.app_discord
    override val accentColor = "#5865F2"

    private val kw = CallKeywords(context)
    private val incomingLabel = context.getString(R.string.call_incoming)
    private val ongoingLabel = context.getString(R.string.call_ongoing)

    override fun translate(sbn: StatusBarNotification, firstSeen: Long): IslandSpec? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val haystack = "$rawTitle $text $sub".lowercase()

        // "Missed call" / "Call ended" style notifications are history, not a live call.
        if (kw.looksEnded(haystack) || n.category == Notification.CATEGORY_MISSED_CALL) return null

        val actions = n.actions?.filter { it.actionIntent != null }.orEmpty()
        val roles = actions.map { kw.roleOf(it.title) }
        val hasAnswer = ActionRole.ANSWER in roles
        val hasHangup = ActionRole.HANGUP in roles

        val isOngoingFlag = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val isCallCategory = n.category == Notification.CATEGORY_CALL || template.endsWith("CallStyle")
        val textIncoming = kw.looksIncoming(haystack)
        val textOngoing = kw.looksOngoing(haystack)

        val isCall = isCallCategory || hasAnswer || hasHangup || textIncoming || (isOngoingFlag && textOngoing)
        if (!isCall) return null

        val chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val incoming = !chrono && !hasHangup && (hasAnswer || textIncoming)
        // A live voice session is always an ongoing (non-dismissable) notification. Anything else that
        // merely looks call-related is not a reason to keep an island alive.
        if (!incoming && !isOngoingFlag && !chrono) return null
        val state = if (incoming) CallState.INCOMING else CallState.ONGOING

        val startTime = when {
            state == CallState.INCOMING -> 0L
            chrono && n.`when` > 0 -> n.`when`
            else -> firstSeen
        }

        // Discord rotates generic titles ("Voz conectada — General", "Toca para volver a la llamada").
        // Show the channel as the title whatever field it arrives in, and a generic phrase as subtitle.
        val (titleHead, titleTail) = splitTitle(rawTitle)
        val candidates = listOf(titleHead, titleTail.orEmpty(), text, sub).filter { it.isNotEmpty() }
        val channel = candidates.firstOrNull { !kw.looksGeneric(it.lowercase()) }
        val generic = candidates.firstOrNull { kw.looksGeneric(it.lowercase()) }
        val displayTitle = channel ?: titleHead.ifEmpty { "Discord" }
        val subtitle = when (state) {
            CallState.INCOMING -> incomingLabel
            CallState.ONGOING -> generic?.takeIf { it != displayTitle } ?: ongoingLabel
        }

        return IslandSpec(
            state = state,
            title = displayTitle,
            subtitle = subtitle,
            startTime = startTime,
            actions = pickActions(actions, roles, state),
            avatar = n.getLargeIcon(),
            accentColor = accentColor,
        )
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

    /** Up to two round buttons, ordered the way phones do it: neutral/red on the left, green on the right. */
    private fun pickActions(
        actions: List<Notification.Action>,
        roles: List<ActionRole>,
        state: CallState,
    ): List<IslandAction> {
        fun find(role: ActionRole): Int? = roles.indexOf(role).takeIf { it >= 0 }
        fun fromSource(i: Int) = IslandAction(roles[i], actions[i].title ?: "", null, actions[i].actionIntent, sourceIndex = i)

        if (state == CallState.INCOMING) {
            val wanted = listOfNotNull(find(ActionRole.DECLINE), find(ActionRole.ANSWER))
            val filled = if (wanted.size < 2) wanted + actions.indices.filter { it !in wanted }.take(2 - wanted.size) else wanted
            return filled.map(::fromSource)
        }

        // Ongoing: a mute button (Discord's own if it has one, else the system mic mute) and hang up.
        val mute = find(ActionRole.MUTE)?.let(::fromSource) ?: systemMuteAction()
        val hangup = find(ActionRole.HANGUP)?.let(::fromSource)
            ?: actions.indices.firstOrNull { roles[it] != ActionRole.MUTE }?.let(::fromSource)
        return listOfNotNull(mute, hangup)
    }

    private fun systemMuteAction(): IslandAction {
        val muted = MicMute.isMuted()
        return IslandAction(
            role = ActionRole.MUTE,
            title = context.getString(if (muted) R.string.action_unmute else R.string.action_mute),
            icon = null,
            intent = MuteReceiver.pendingIntent(context),
            sourceIndex = -1,
            iconRes = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
            bgColor = if (muted) "#FF9F0A" else "#3A3A3C",
        )
    }

    companion object {
        const val PACKAGE = "com.discord"
        private val SEPARATORS = listOf(" — ", " – ", " - ", " | ")
    }
}
