package xyz.samukis.hyperoscompat.bridge

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.calls.MeetBridge
import xyz.samukis.hyperoscompat.bridge.calls.WhatsAppBridge
import xyz.samukis.hyperoscompat.bridge.delivery.DeliveryBridge
import xyz.samukis.hyperoscompat.bridge.discord.DiscordBridge
import xyz.samukis.hyperoscompat.bridge.progress.ProgressBridge
import xyz.samukis.hyperoscompat.bridge.rides.UberBridge

/**
 * One translator per supported app. Gets the raw notification and decides whether it deserves
 * an island. Must be cheap: it runs for every notification the source app posts.
 */
interface AppBridge {
    /** Primary package; also the settings key for the on/off switch. */
    val packageName: String
    /** Every package this bridge handles (e.g. WhatsApp and WhatsApp Business). */
    val packages: Set<String> get() = setOf(packageName)
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
    fun all(context: Context): List<AppBridge> {
        val c = context.applicationContext
        return listOf(
            DiscordBridge(c),
            UberBridge(c),
            MeetBridge(c),
            WhatsAppBridge(c),
            ProgressBridge(c, "com.google.android.apps.docs", R.string.app_drive, "#1EA362"),
            ProgressBridge(c, "com.google.android.apps.photos", R.string.app_photos, "#F9AB00"),
            ProgressBridge(c, "com.miui.cloudservice", R.string.app_micloud, "#FF6900"),
            ProgressBridge(c, "com.android.vending", R.string.app_play, "#00A173"),
            ProgressBridge(c, "com.instagram.android", R.string.app_instagram, "#E1306C"),
            DeliveryBridge(c, "com.pedidosya", R.string.app_pedidosya, "#FA0050"),
            DeliveryBridge(c, "com.grability.rappi", R.string.app_rappi, "#FF441F"),
        )
    }
}
