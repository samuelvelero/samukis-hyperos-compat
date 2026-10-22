package xyz.samukis.hyperoscompat.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.service.IslandBridgeService

/**
 * Island buttons that are broadcasts or services point here instead of at the source app's
 * PendingIntent. HyperOS fires our broadcast reliably (type 2), and we forward the click:
 * either to the live source notification's action (freshest intent), or to a PendingIntent we
 * carry in the extras (a button borrowed from another notification of the same app).
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, PendingIntent::class.java)
        if (target != null) {
            try {
                target.send()
                NotificationLog.event(context, "action click: forwarded to ${target.creatorPackage}")
            } catch (e: Exception) {
                NotificationLog.event(context, "action click: forward failed: ${e.javaClass.simpleName}")
            }
            return
        }

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
        private const val EXTRA_TARGET = "target"

        private fun requestCode(islandId: Int, slot: Int) = (islandId shl 2) or (slot and 3)

        /** Forwards to action [sourceIndex] of the live source notification [sourceKey]. */
        fun pendingIntent(context: Context, islandId: Int, slot: Int, sourceKey: String, sourceIndex: Int): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java)
                .setAction(ACTION)
                .setIdentifier("$sourceKey#$sourceIndex")
                .putExtra(EXTRA_KEY, sourceKey)
                .putExtra(EXTRA_INDEX, sourceIndex)
            return PendingIntent.getBroadcast(
                context, requestCode(islandId, slot), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Forwards to [target] as is. */
        fun wrap(context: Context, islandId: Int, slot: Int, target: PendingIntent): PendingIntent {
            val intent = Intent(context, ActionReceiver::class.java)
                .setAction(ACTION)
                .setIdentifier("wrap#$islandId#$slot")
                .putExtra(EXTRA_TARGET, target)
            return PendingIntent.getBroadcast(
                context, requestCode(islandId, slot), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
