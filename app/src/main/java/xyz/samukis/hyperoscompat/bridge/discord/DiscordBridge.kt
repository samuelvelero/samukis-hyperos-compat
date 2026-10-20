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

/**
 * Discord: incoming calls (answer / decline) and ongoing voice sessions (mute / disconnect).
 * Detection is heuristic on purpose: Discord does not use CallStyle consistently, so we look at
 * the category, the action titles and the texts, in English and Spanish.
 */
class DiscordBridge(context: Context) : AppBridge {
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
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val haystack = "$title $text $sub".lowercase()

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
        val state = if (incoming) CallState.INCOMING else CallState.ONGOING

        val startTime = when {
            state == CallState.INCOMING -> 0L
            chrono && n.`when` > 0 -> n.`when`
            else -> firstSeen
        }

        val displayTitle = title.ifEmpty { "Discord" }
        val subtitle = if (state == CallState.INCOMING) incomingLabel else text.ifEmpty { ongoingLabel }

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

    /** Up to two buttons, ordered the way phones do it: red on the left, green on the right. */
    private fun pickActions(
        actions: List<Notification.Action>,
        roles: List<ActionRole>,
        state: CallState,
    ): List<IslandAction> {
        fun find(role: ActionRole) = roles.indexOf(role).takeIf { it >= 0 }?.let { actions[it] to role }

        val wanted = if (state == CallState.INCOMING) {
            listOfNotNull(find(ActionRole.DECLINE), find(ActionRole.ANSWER))
        } else {
            listOfNotNull(find(ActionRole.MUTE), find(ActionRole.HANGUP))
        }
        val chosen = wanted.ifEmpty {
            actions.take(2).mapIndexed { i, a -> a to roles[i] }
        }
        return chosen.map { (a, role) ->
            IslandAction(role, a.title ?: "", a.getIcon(), a.actionIntent)
        }
    }

    companion object {
        const val PACKAGE = "com.discord"
    }
}
