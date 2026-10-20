package xyz.samukis.hyperoscompat.island

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.graphics.drawable.toBitmap
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import io.github.d4viddf.hyperisland_kit.models.TimerInfo
import xyz.samukis.hyperoscompat.App
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.ActionRole
import xyz.samukis.hyperoscompat.bridge.CallState
import xyz.samukis.hyperoscompat.bridge.IslandAction
import xyz.samukis.hyperoscompat.bridge.IslandSpec
import xyz.samukis.hyperoscompat.prefs.Settings
import xyz.samukis.hyperoscompat.receiver.DismissReceiver

/**
 * Turns an [IslandSpec] into a HyperOS focus notification using the HyperIsland ToolKit.
 * One plain notification carries the `miui.focus.*` extras; the system draws the island.
 */
class IslandPoster(private val context: Context, private val settings: Settings) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val iconCache = HashMap<String, Icon>()

    fun post(id: Int, pkg: String, spec: IslandSpec, source: Notification?, timeoutMs: Long = 0L) {
        val island = HyperIslandNotification.Builder(context, "samukis_$pkg", spec.title)
            .setEnableFloat(false)
            .setIslandFirstFloat(spec.state == CallState.INCOMING)
            .setShowNotification(settings.showInShade)

        val appKey = "app"
        island.addPicture(HyperPicture(appKey, appIcon(pkg)))
        val picKey = spec.avatar?.let { island.addPicture(HyperPicture("avatar", it)); "avatar" } ?: appKey

        val actionKeys = ArrayList<String>(spec.actions.size)
        spec.actions.forEachIndexed { i, a ->
            val key = "act$i"
            island.addAction(
                HyperAction(
                    key = key,
                    title = a.title,
                    icon = a.icon,
                    pendingIntent = a.intent,
                    actionIntentType = 1,
                    actionBgColor = colorFor(a.role),
                    titleColor = "#FFFFFF",
                )
            )
            actionKeys += key
        }

        val now = System.currentTimeMillis()
        val counting = spec.state == CallState.ONGOING && spec.startTime > 0
        val timer = if (counting) TimerInfo(1, spec.startTime, now - spec.startTime, now) else null

        island.setChatInfo(
            title = spec.title,
            content = spec.subtitle,
            pictureKey = picKey,
            appPkg = pkg,
            timer = timer,
            actionKeys = actionKeys.ifEmpty { null },
        )
        island.setSmallIsland(picKey)
        island.setIslandConfig(highlightColor = spec.accentColor)
        if (counting) {
            island.setBigIslandCountUp(spec.startTime, picKey)
        } else {
            island.setBigIslandInfo(
                left = ImageTextInfoLeft(
                    type = 1,
                    picInfo = PicInfo(type = 1, pic = picKey),
                    textInfo = TextInfo(title = spec.title),
                ),
                right = ImageTextInfoRight(type = 2, textInfo = TextInfo(title = spec.subtitle.orEmpty())),
            )
        }

        val builder = Notification.Builder(context, App.CHANNEL_ISLAND)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(spec.title)
            .setContentText(spec.subtitle)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(counting)
            .setWhen(if (counting) spec.startTime else now)
            .setUsesChronometer(counting)
            .addExtras(island.buildResourceBundle())
        if (timeoutMs > 0) builder.setTimeoutAfter(timeoutMs)
        source?.contentIntent?.let { builder.setContentIntent(it) }
        spec.avatar?.let { builder.setLargeIcon(it) }
        spec.actions.forEach { builder.addAction(Notification.Action.Builder(it.icon, it.title, it.intent).build()) }

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", island.buildJsonParam())
        nm.notify(id, notification)
    }

    fun cancel(id: Int) = nm.cancel(id)

    /** Posts a 20 s ongoing island so the user can confirm permissions without a real call. */
    fun postDemo() {
        val dismiss = PendingIntent.getBroadcast(
            context, 0, Intent(context, DismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val spec = IslandSpec(
            state = CallState.ONGOING,
            title = context.getString(R.string.demo_title),
            subtitle = context.getString(R.string.demo_text),
            startTime = System.currentTimeMillis(),
            actions = listOf(
                IslandAction(
                    ActionRole.HANGUP,
                    context.getString(R.string.demo_dismiss),
                    Icon.createWithResource(context, R.drawable.ic_stat_island),
                    dismiss,
                )
            ),
            avatar = null,
            accentColor = "#5865F2",
        )
        post(DEMO_ID, context.packageName, spec, null, timeoutMs = 20_000L)
    }

    private fun colorFor(role: ActionRole) = when (role) {
        ActionRole.DECLINE, ActionRole.HANGUP -> "#FF3B30"
        ActionRole.ANSWER -> "#34C759"
        else -> "#8E8E93"
    }

    private fun appIcon(pkg: String): Icon = iconCache.getOrPut(pkg) {
        try {
            Icon.createWithBitmap(context.packageManager.getApplicationIcon(pkg).toBitmap(192, 192))
        } catch (_: Exception) {
            Icon.createWithResource(context, R.mipmap.ic_launcher)
        }
    }

    companion object {
        const val DEMO_ID = 7
    }
}
