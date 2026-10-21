package xyz.samukis.hyperoscompat.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import xyz.samukis.hyperoscompat.service.IslandBridgeService

/**
 * After a reboot or an app update HyperOS sometimes leaves the notification listener unbound even
 * though access is granted. Nudge the system: ask for a rebind, and re-enable the component so the
 * binding is re-evaluated. Nothing here keeps a process alive; it runs once and exits.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val component = ComponentName(context, IslandBridgeService::class.java)
                val pm = context.packageManager
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                IslandBridgeService.requestRebind(context)
            }
        }
    }
}
