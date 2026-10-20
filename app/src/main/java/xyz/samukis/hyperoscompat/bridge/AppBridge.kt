package xyz.samukis.hyperoscompat.bridge

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.bridge.discord.DiscordBridge

/**
 * One translator per supported app. Gets the raw notification and decides whether it deserves
 * an island. Must be cheap: it runs for every notification the source app posts.
 */
interface AppBridge {
    val packageName: String
    @get:StringRes val nameRes: Int
    val accentColor: String

    /**
     * @param firstSeen millis when this notification key was first observed; useful as a fallback
     *        start time for ongoing calls that do not carry a chronometer.
     * @return null when the notification is not island-worthy (any existing island is removed).
     */
    fun translate(sbn: StatusBarNotification, firstSeen: Long): IslandSpec?
}

object BridgeRegistry {
    fun all(context: Context): List<AppBridge> = listOf(
        DiscordBridge(context.applicationContext),
    )
}
