package xyz.samukis.hyperoscompat.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import xyz.samukis.hyperoscompat.inspector.NotificationLog
import xyz.samukis.hyperoscompat.island.MicMute
import xyz.samukis.hyperoscompat.service.IslandBridgeService

/** Island "mute" button for apps that do not expose one: toggles the system mic mute. */
class MuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        NotificationLog.event(app, MicMute.toggle(app))
        val handler = Handler(Looper.getMainLooper())
        // Readbacks tell us whether the source app resets the system flag after mirroring it.
        handler.postDelayed({ NotificationLog.event(app, "mic flag +400ms = ${MicMute.systemFlag(app)}") }, 400)
        handler.postDelayed({ NotificationLog.event(app, "mic flag +1500ms = ${MicMute.systemFlag(app)}") }, 1500)
        // HyperOS collapses the island after a button press; rebuild it with the new state.
        handler.postDelayed({ IslandBridgeService.instance?.resync() }, 600)
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, MuteReceiver::class.java).setAction("xyz.samukis.hyperoscompat.TOGGLE_MUTE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
