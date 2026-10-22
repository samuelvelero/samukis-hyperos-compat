package xyz.samukis.hyperoscompat.island

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import xyz.samukis.hyperoscompat.bridge.IslandAction
import xyz.samukis.hyperoscompat.bridge.IslandKind
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
    private val glyphCache = HashMap<Int, Icon>()

    /**
     * @param sourceKey key of the source notification; when given, island buttons that are not
     *        activities are routed through [ActionReceiver] and forwarded from our process.
     * @param timeoutMs overrides the spec's auto-dismiss when > 0.
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
            .setEnableFloat(spec.popOnUpdate)
            .setIslandFirstFloat(spec.expandFirst)
            .setShowNotification(settings.showInShade)
            .setShowSmallIcon(false)

        // Avatar: the source's large icon if it sends one, else the source app's icon. In the collapsed
        // pill this is the only thing visible, so it must say which app it is.
        val avatar = spec.avatar ?: appIcon(pkg)
        val picKey = "avatar"
        island.addPicture(HyperPicture(picKey, avatar))

        val routes = spec.actions.mapIndexed { i, a -> route(id, i, a, sourceKey) }
        val actionKeys = ArrayList<String>(spec.actions.size)
        spec.actions.forEachIndexed { i, a ->
            // The glyph is part of the key so HyperOS cannot reuse a cached icon when a toggle flips.
            val key = "act${i}_${a.iconRes}_${a.role.ordinal}"
            val bg = a.bgColor ?: colorFor(a.role)
            // Always our own round glyph: with an icon HyperOS draws a circular button, not a text pill.
            val icon = roundIcon(if (a.iconRes != 0) a.iconRes else glyphFor(a.role), bg)
            val (intent, type) = routes[i]
            island.addAction(
                HyperAction(
                    key = key,
                    title = a.title,
                    icon = icon,
                    pendingIntent = intent,
                    actionIntentType = type,
                    actionBgColor = bg,
                    titleColor = "#FFFFFF",
                )
            )
            actionKeys += key
        }
        val keys = actionKeys.ifEmpty { null }
        val accent = spec.accentColor
        val now = System.currentTimeMillis()
        val start = if (spec.startTime > 0) spec.startTime else now

        when (spec.kind) {
            IslandKind.INCOMING -> {
                island.setChatInfo(title = spec.title, content = spec.subtitle, pictureKey = picKey, appPkg = pkg, actionKeys = keys)
                island.setBigIslandInfo(
                    left = ImageTextInfoLeft(type = 1, picInfo = PicInfo(type = 1, pic = picKey), textInfo = TextInfo(title = spec.pillText ?: spec.title)),
                    right = ImageTextInfoRight(type = 2, textInfo = TextInfo(title = spec.trailing ?: spec.subtitle.orEmpty())),
                )
                island.setSmallIsland(picKey)
            }
            IslandKind.TIMER -> {
                island.setChatInfo(
                    title = spec.title, content = spec.subtitle, pictureKey = picKey, appPkg = pkg,
                    timer = TimerInfo(1, start, now - start, now), actionKeys = keys,
                )
                island.setBigIslandCountUp(start, picKey)
                island.setSmallIsland(picKey)
            }
            IslandKind.PROGRESS -> {
                island.setBaseInfo(
                    title = spec.title,
                    content = spec.subtitle ?: spec.trailing.orEmpty(),
                    subContent = if (spec.subtitle != null) spec.trailing else null,
                    pictureKey = picKey,
                    actionKeys = keys,
                )
                val p = spec.progress
                if (p != null) {
                    island.setProgressBar(p, accent)
                    island.setBigIslandProgressCircle(picKey, "", p, accent, isCCW = true)
                    island.setSmallIslandCircularProgress(picKey, p, accent, isCCW = true)
                } else {
                    island.setBigIslandInfo(
                        left = ImageTextInfoLeft(type = 1, picInfo = PicInfo(type = 1, pic = picKey), textInfo = TextInfo(title = spec.pillText ?: spec.title)),
                        right = ImageTextInfoRight(type = 2, textInfo = TextInfo(title = spec.subtitle ?: "…")),
                    )
                    island.setSmallIsland(picKey)
                }
            }
            IslandKind.STATUS -> {
                island.setBaseInfo(
                    title = spec.title,
                    content = spec.subtitle.orEmpty(),
                    subContent = spec.detail ?: spec.trailing,
                    specialTitle = spec.highlight,
                    colorSpecialBg = accent,
                    colorSpecialTitle = if (accent.equals("#FFFFFF", ignoreCase = true)) "#000000" else "#FFFFFF",
                    pictureKey = picKey,
                    actionKeys = keys,
                )
                spec.progress?.let { p ->
                    island.setProgressBar(
                        p, accent,
                        picForwardKey = glyphKey(island, "pfwd", spec.progressGlyph),
                        picEndKey = glyphKey(island, "pend", spec.progressEndGlyph),
                    )
                }
                island.setBigIslandInfo(
                    left = ImageTextInfoLeft(type = 1, picInfo = PicInfo(type = 1, pic = picKey), textInfo = TextInfo(title = spec.pillText ?: spec.title)),
                    right = ImageTextInfoRight(type = 2, textInfo = TextInfo(title = spec.trailing ?: spec.highlight.orEmpty())),
                )
                island.setSmallIsland(picKey)
            }
        }
        island.setIslandConfig(highlightColor = accent)

        val timeout = if (timeoutMs > 0) timeoutMs else spec.autoDismissMs
        val shadeText = listOfNotNull(spec.subtitle, spec.detail ?: spec.trailing, spec.highlight).joinToString(" · ").ifEmpty { null }
        val builder = Notification.Builder(context, App.CHANNEL_ISLAND)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(spec.title)
            .setContentText(shadeText)
            .setCategory(categoryFor(spec.kind))
            .setOngoing(timeout == 0L)
            .setOnlyAlertOnce(true)
            .setLargeIcon(avatar)
            .addExtras(island.buildResourceBundle())
        if (spec.kind == IslandKind.TIMER) {
            builder.setShowWhen(true).setWhen(start).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        if (spec.kind == IslandKind.PROGRESS) builder.setProgress(100, spec.progress ?: 0, spec.progress == null)
        if (spec.kind == IslandKind.STATUS && spec.progress != null) builder.setProgress(100, spec.progress, false)
        if (timeout > 0) builder.setTimeoutAfter(timeout)
        source?.contentIntent?.let { builder.setContentIntent(it) }
        spec.actions.forEachIndexed { i, a ->
            val glyph = Icon.createWithResource(context, if (a.iconRes != 0) a.iconRes else glyphFor(a.role))
            builder.addAction(Notification.Action.Builder(glyph, a.title, routes[i].first).build())
        }

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", island.buildJsonParam())
        nm.notify(id, notification)
    }

    fun cancel(id: Int) = nm.cancel(id)

    /** Posts a 20 s timer island so the user can confirm permissions without a real call. */
    fun postDemo() {
        val dismiss = PendingIntent.getBroadcast(
            context, 0, Intent(context, DismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val spec = IslandSpec(
            kind = IslandKind.TIMER,
            title = context.getString(R.string.demo_title),
            subtitle = context.getString(R.string.demo_text),
            startTime = System.currentTimeMillis(),
            actions = listOf(IslandAction(ActionRole.HANGUP, context.getString(R.string.demo_dismiss), null, dismiss)),
            accentColor = "#5865F2",
        )
        post(DEMO_ID, context.packageName, spec, null, sourceKey = null, timeoutMs = 20_000L)
    }

    /**
     * Where an island button points and how HyperOS must fire it.
     * - Activities (share sheet, dialer, open app) go straight to SystemUI as type 1: a trampoline
     *   through our receiver would be blocked by Android's background-activity rules.
     * - Broadcasts and services (Discord mute / disconnect) go through our receiver as type 2,
     *   which HyperOS fires reliably; type 1 silently drops them.
     */
    private fun route(id: Int, slot: Int, a: IslandAction, sourceKey: String?): Pair<PendingIntent, Int> {
        val target = a.intent
        if (target.creatorPackage == context.packageName) return target to ACTION_TYPE_BROADCAST
        if (isActivity(target)) return target to ACTION_TYPE_ACTIVITY
        val routed = if (sourceKey != null && a.sourceIndex >= 0) {
            ActionReceiver.pendingIntent(context, id, slot, sourceKey, a.sourceIndex)
        } else {
            ActionReceiver.wrap(context, id, slot, target)
        }
        return routed to ACTION_TYPE_BROADCAST
    }

    private fun isActivity(pi: PendingIntent): Boolean = try { pi.isActivity } catch (_: Exception) { false }

    private fun categoryFor(kind: IslandKind) = when (kind) {
        IslandKind.INCOMING, IslandKind.TIMER -> Notification.CATEGORY_CALL
        IslandKind.PROGRESS -> Notification.CATEGORY_PROGRESS
        IslandKind.STATUS -> Notification.CATEGORY_STATUS
    }

    private fun colorFor(role: ActionRole) = when (role) {
        ActionRole.DECLINE, ActionRole.HANGUP, ActionRole.CANCEL -> "#FF3B30"
        ActionRole.ANSWER, ActionRole.JOIN, ActionRole.CALL -> "#34C759"
        ActionRole.UNMUTE -> "#FF9F0A" // currently muted: warm so it stands out
        else -> "#3A3A3C"
    }

    private fun glyphFor(role: ActionRole) = when (role) {
        ActionRole.DECLINE, ActionRole.HANGUP -> R.drawable.ic_call_end
        ActionRole.ANSWER, ActionRole.CALL -> R.drawable.ic_call
        ActionRole.JOIN -> R.drawable.ic_videocam
        ActionRole.MUTE -> R.drawable.ic_mic
        ActionRole.UNMUTE -> R.drawable.ic_mic_off
        ActionRole.CAMERA -> R.drawable.ic_videocam
        ActionRole.SPEAKER -> R.drawable.ic_speaker
        ActionRole.CANCEL -> R.drawable.ic_close
        ActionRole.PAUSE -> R.drawable.ic_pause
        ActionRole.RESUME -> R.drawable.ic_play
        ActionRole.SHARE -> R.drawable.ic_share
        ActionRole.MESSAGE -> R.drawable.ic_chat
        ActionRole.DEAFEN, ActionRole.OTHER -> R.drawable.ic_stat_island
    }

    /** Registers a white glyph picture under [key]; null when there is no glyph. */
    private fun glyphKey(island: HyperIslandNotification, key: String, @DrawableRes res: Int): String? {
        if (res == 0) return null
        island.addPicture(HyperPicture(key, glyphIcon(res)))
        return key
    }

    private fun glyphIcon(@DrawableRes res: Int): Icon = glyphCache.getOrPut(res) {
        val size = 96
        val bitmap = createBitmap(size, size)
        AppCompatResources.getDrawable(context, res)?.mutate()?.let { d ->
            d.setTint(Color.WHITE)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bitmap))
        }
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
        private const val ACTION_TYPE_ACTIVITY = 1
        private const val ACTION_TYPE_BROADCAST = 2
    }
}
