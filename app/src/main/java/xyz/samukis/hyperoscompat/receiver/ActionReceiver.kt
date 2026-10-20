package xyz.samukis.hyperoscompat.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.service.IslandBridgeService

/**
 * Every island button points here instead of at the source app's PendingIntent. HyperOS fires our
 * broadcast reliably (we declare it as type 2 = broadcast), and we forward the click to the real
 * action of the source notification from inside our own process.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val service = IslandBridgeService.instance
        if (service == null) {
            NotificationLog.event(context, "action click dropped: bridge service not running (key=$key idx=$index)")
            return
        }
        service.fireSourceAction(key, index)
    }

    companion object {
        private const val ACTION = "xyz.samukis.hyperoscompat.ISLAND_ACTION"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_INDEX = "index"

        fun pendingIntent(context: Context, islandId: Int, slot: Int, sourceKey: String, sourceIndex: Int): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java)
                .setAction(ACTION)
                .setIdentifier("$sourceKey#$sourceIndex")
                .putExtra(EXTRA_KEY, sourceKey)
                .putExtra(EXTRA_INDEX, sourceIndex)
            val requestCode = (islandId shl 2) or (slot and 3)
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
