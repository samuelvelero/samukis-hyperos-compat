package xyz.samukis.hyperoscompat.island

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.toColorInt
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
import xyz.samukis.hyperoscompat.receiver.ActionReceiver
import xyz.samukis.hyperoscompat.receiver.DismissReceiver

/**
 * Turns an [IslandSpec] into a HyperOS focus notification using the HyperIsland ToolKit.
 * One plain notification carries the `miui.focus.*` extras; the system draws the island.
 */
class IslandPoster(private val context: Context, private val settings: Settings) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val iconCache = HashMap<String, Icon>()
    private val roundIconCache = HashMap<String, Icon>()
    private val avatarCache = HashMap<String, Icon>()

    /**
     * @param sourceKey key of the source notification; when given, island buttons are routed through
     *        [ActionReceiver] (a broadcast we own) and forwarded to the source action from our process.
     */
    fun post(
        id: Int,
        pkg: String,
        spec: IslandSpec,
        source: Notification?,
        sourceKey: String? = null,
        timeoutMs: Long = 0L,
    ) {
        val island = HyperIslandNotification.Builder(context, "samukis_$pkg", spec.title)
            .setEnableFloat(false)
            .setIslandFirstFloat(spec.state == CallState.INCOMING)
            .setShowNotification(settings.showInShade)

        // Avatar: the source's large icon if it sends one, else channel initials; the app itself is the badge.
        val avatar = spec.avatar ?: if (pkg != context.packageName) initialsAvatar(spec.title, spec.accentColor) else appIcon(pkg)
        val picKey = "avatar"
        island.addPicture(HyperPicture(picKey, avatar))

        val actionKeys = ArrayList<String>(spec.actions.size)
        spec.actions.forEachIndexed { i, a ->
            // The glyph is part of the key so HyperOS cannot reuse a cached icon when a toggle flips.
            val key = "act${i}_${a.iconRes}_${a.role.ordinal}"
            val intent = if (sourceKey != null && a.sourceIndex >= 0) {
                ActionReceiver.pendingIntent(context, id, i, sourceKey, a.sourceIndex)
            } else a.intent
            val bg = a.bgColor ?: colorFor(a.role)
            // Always our own round glyph: with an icon HyperOS draws a circular button instead of a text pill.
            val icon = roundIcon(if (a.iconRes != 0) a.iconRes else glyphFor(a.role), bg)
            island.addAction(
                HyperAction(
                    key = key,
                    title = a.title,
                    icon = icon,
                    pendingIntent = intent,
                    actionIntentType = ACTION_TYPE_BROADCAST,
                    actionBgColor = bg,
                    titleColor = "#FFFFFF",
                )
            )
            actionKeys += key
        }

        val now = System.currentTimeMillis()
        val counting = spec.state == CallState.ONGOING && spec.startTime > 0
        val timer = if (counting) TimerInfo(1, spec.startTime, now - spec.startTime, now) else null

        // appPkg = the source app, so the small badge HyperOS always draws shows Discord, not us.
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
        builder.setLargeIcon(avatar)
        spec.actions.forEachIndexed { i, a ->
            val intent = if (sourceKey != null && a.sourceIndex >= 0) {
                ActionReceiver.pendingIntent(context, id, i, sourceKey, a.sourceIndex)
            } else a.intent
            val glyph = Icon.createWithResource(context, if (a.iconRes != 0) a.iconRes else glyphFor(a.role))
            builder.addAction(Notification.Action.Builder(glyph, a.title, intent).build())
        }

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
                IslandAction(ActionRole.HANGUP, context.getString(R.string.demo_dismiss), null, dismiss)
            ),
            avatar = null,
            accentColor = "#5865F2",
        )
        post(DEMO_ID, context.packageName, spec, null, sourceKey = null, timeoutMs = 20_000L)
    }

    private fun colorFor(role: ActionRole) = when (role) {
        ActionRole.DECLINE, ActionRole.HANGUP -> "#FF3B30"
        ActionRole.ANSWER -> "#34C759"
        ActionRole.UNMUTE -> "#FF9F0A" // currently muted: warm so it stands out
        else -> "#3A3A3C"
    }

    private fun glyphFor(role: ActionRole) = when (role) {
        ActionRole.DECLINE, ActionRole.HANGUP -> R.drawable.ic_call_end
        ActionRole.ANSWER -> R.drawable.ic_call
        ActionRole.MUTE -> R.drawable.ic_mic
        ActionRole.UNMUTE -> R.drawable.ic_mic_off
        ActionRole.DEAFEN, ActionRole.OTHER -> R.drawable.ic_stat_island
    }

    /**
     * Discord sends no large icon, so the avatar would fall back to the app icon and HyperOS would
     * badge it with the same app icon. Instead: channel initials on the accent colour, badge = app.
     */
    private fun initialsAvatar(name: String, accentHex: String): Icon = avatarCache.getOrPut("$name/$accentHex") {
        val letters = name.filter { it.isLetterOrDigit() }.let { clean ->
            if (clean.length <= 3) clean.uppercase() else {
                val words = name.split(' ', '-', '_', '┃', '|', '·').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
                if (words.size >= 2) (words[0].take(1) + words[1].take(1)).uppercase() else clean.take(2).uppercase()
            }
        }.ifEmpty { "D" }
        val size = 192
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = try { accentHex.toColorInt() } catch (_: Exception) { 0xFF5865F2.toInt() }
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = if (letters.length >= 3) size * 0.36f else size * 0.46f
        }
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letters, size / 2f, baseline, text)
        Icon.createWithBitmap(bitmap)
    }

    /** White glyph centred on a filled circle, rendered once per (glyph, colour) and cached. */
    private fun roundIcon(@DrawableRes glyphRes: Int, bgHex: String): Icon = roundIconCache.getOrPut("$glyphRes/$bgHex") {
        val size = 132
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = try { bgHex.toColorInt() } catch (_: Exception) { 0xFF3A3A3C.toInt() }
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        AppCompatResources.getDrawable(context, glyphRes)?.mutate()?.let { d ->
            d.setTint(Color.WHITE)
            val inset = (size * 0.27f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(canvas)
        }
        Icon.createWithBitmap(bitmap)
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

        /** HyperOS action intent types: 1 = Activity, 2 = Broadcast, 3 = Service. */
        private const val ACTION_TYPE_BROADCAST = 2
    }
}
