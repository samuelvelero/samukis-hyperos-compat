package xyz.samukis.hyperoscompat.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import xyz.samukis.hyperoscompat.BuildConfig
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.AppBridge
import xyz.samukis.hyperoscompat.bridge.BridgeCategory
import xyz.samukis.hyperoscompat.bridge.BridgeRegistry
import xyz.samukis.hyperoscompat.databinding.ActivityMainBinding
import xyz.samukis.hyperoscompat.databinding.ItemAppBinding
import xyz.samukis.hyperoscompat.databinding.ItemAppGroupBinding
import xyz.samukis.hyperoscompat.databinding.ItemCheckBinding
import xyz.samukis.hyperoscompat.databinding.ItemSectionBinding
import xyz.samukis.hyperoscompat.island.IslandPoster
import xyz.samukis.hyperoscompat.prefs.Settings
import xyz.samukis.hyperoscompat.service.IslandBridgeService
import com.google.android.material.R as MR

/**
 * Screen order follows what the user needs, in order:
 * 1. Does it work? One hero card with a verdict and the single next step.
 * 2. What will I get? The apps, grouped, each saying what its island shows.
 * 3. Something is off: diagnostics, last, because they only matter on a bad day.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    /** One prerequisite: its label, whether it holds, and how to fix it (null = informational). */
    private class Check(@StringRes val label: Int, val ok: Boolean, val fix: (() -> Unit)?)

    private enum class Hero { OK, WARN, BLOCKED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        setupApps()
        setupDiagnostics()
        b.footer.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
        b.footerRepo.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://${getString(R.string.footer_repo)}".toUri()))
        }

        if (!hasPostPermission()) requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Opening the app is a good moment to drop islands whose source already ended.
        IslandBridgeService.instance?.resync()
        ensureServiceBound()
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---------------------------------------------------------------- hero + checks

    private fun refreshStatus() {
        val post = hasPostPermission()
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val focus = HyperOsChecks.hasFocusPermission(this)
        val service = IslandBridgeService.connected
        val island = HyperOsChecks.isIslandSupported()

        val askPost: () -> Unit = { requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val rebind: () -> Unit = {
            IslandBridgeService.requestRebind(this)
            Toast.makeText(this, R.string.toast_rebind, Toast.LENGTH_SHORT).show()
            handler.postDelayed({ refreshStatus() }, 2500)
        }

        renderChecks(
            listOf(
                Check(R.string.check_post, post, askPost),
                Check(R.string.status_listener, listener, ::openListenerSettings),
                Check(R.string.status_focus, focus, ::openAppNotificationSettings),
                Check(R.string.status_service, service, if (listener) rebind else null),
                Check(R.string.status_island, island, null),
            )
        )

        // The single most important next step, in the order a new install hits them.
        when {
            !post -> hero(Hero.BLOCKED, R.string.hero_blocked_title, R.string.blocker_post, R.string.action_allow, askPost)
            !listener -> hero(Hero.BLOCKED, R.string.hero_blocked_title, R.string.blocker_listener, R.string.action_grant, ::openListenerSettings)
            !focus -> hero(Hero.BLOCKED, R.string.hero_blocked_title, R.string.blocker_focus, R.string.action_open, ::openAppNotificationSettings)
            !service -> hero(Hero.BLOCKED, R.string.hero_blocked_title, R.string.blocker_service, R.string.action_rebind, rebind)
            !island -> hero(Hero.WARN, R.string.hero_warn_title, R.string.blocker_island, R.string.action_test_island, ::postDemo)
            else -> hero(Hero.OK, R.string.hero_ok_title, R.string.hero_ok_body, R.string.action_test_island, ::postDemo)
        }
    }

    private fun hero(state: Hero, @StringRes title: Int, @StringRes body: Int, @StringRes button: Int, onClick: () -> Unit) {
        val (bgAttr, fgAttr, icon) = when (state) {
            Hero.OK -> Triple(MR.attr.colorPrimaryContainer, MR.attr.colorOnPrimaryContainer, R.drawable.ic_check_circle)
            Hero.WARN -> Triple(MR.attr.colorTertiaryContainer, MR.attr.colorOnTertiaryContainer, R.drawable.ic_info_circle)
            Hero.BLOCKED -> Triple(MR.attr.colorErrorContainer, MR.attr.colorOnErrorContainer, R.drawable.ic_error_circle)
        }
        val fg = color(fgAttr)
        b.heroCard.setCardBackgroundColor(color(bgAttr))
        b.heroIcon.setImageResource(icon)
        b.heroIcon.imageTintList = ColorStateList.valueOf(fg)
        b.heroTitle.setText(title)
        b.heroTitle.setTextColor(fg)
        b.heroBody.setText(body)
        b.heroBody.setTextColor(fg)
        b.heroButton.setText(button)
        b.heroButton.setOnClickListener { onClick() }
    }

    private fun renderChecks(checks: List<Check>) {
        val inflater = LayoutInflater.from(this)
        b.checks.removeAllViews()
        for (c in checks) {
            val row = ItemCheckBinding.inflate(inflater, b.checks, true)
            row.label.setText(c.label)
            val actionable = !c.ok && c.fix != null
            val (iconRes, tint) = when {
                c.ok -> R.drawable.ic_check_circle to R.color.status_ok
                actionable -> R.drawable.ic_error_circle to R.color.status_bad
                else -> R.drawable.ic_info_circle to R.color.status_warn
            }
            row.icon.setImageResource(iconRes)
            row.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
            row.label.setTextColor(color(MR.attr.colorOnSurface))
            row.chevron.visibility = if (actionable) View.VISIBLE else View.GONE
            row.chevron.imageTintList = ColorStateList.valueOf(color(MR.attr.colorOnSurfaceVariant))
            row.root.isClickable = actionable
            row.root.setOnClickListener(if (actionable) View.OnClickListener { c.fix?.invoke() } else null)
        }
    }

    private fun hasPostPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openListenerSettings() {
        val component = ComponentName(this, IslandBridgeService::class.java)
        val detail = Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(SysSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        try {
            startActivity(detail)
        } catch (_: Exception) {
            startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun openAppNotificationSettings() {
        startActivity(
            Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun postDemo() {
        if (!hasPostPermission()) {
            Toast.makeText(this, R.string.toast_need_post_permission, Toast.LENGTH_SHORT).show()
            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        IslandPoster(this, settings).postDemo()
        Toast.makeText(this, R.string.toast_test_posted, Toast.LENGTH_SHORT).show()
    }

    /**
     * After an update HyperOS often leaves the listener unbound even though access is granted.
     * Ask for a rebind; if that is ignored, toggling the component forces the system to bind again.
     */
    private fun ensureServiceBound() {
        val listenerOk = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (!listenerOk || IslandBridgeService.connected) return
        IslandBridgeService.requestRebind(this)
        handler.postDelayed({
            if (IslandBridgeService.connected) { refreshStatus(); return@postDelayed }
            val component = ComponentName(this, IslandBridgeService::class.java)
            packageManager.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
            packageManager.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
            IslandBridgeService.requestRebind(this)
            handler.postDelayed({ refreshStatus() }, 3000)
        }, 2500)
    }

    // ---------------------------------------------------------------- apps

    private fun setupApps() {
        val inflater = LayoutInflater.from(this)
        b.appsContainer.removeAllViews()
        val bridges = BridgeRegistry.all(this)
        for (category in BridgeCategory.entries) {
            val group = bridges.filter { it.category == category }
            if (group.isEmpty()) continue
            ItemSectionBinding.inflate(inflater, b.appsContainer, true).title.setText(category.titleRes)
            val card = ItemAppGroupBinding.inflate(inflater, b.appsContainer, true)
            // Installed apps first; the rest stay visible so the user knows what else is supported.
            val ordered = group.sortedBy { if (installedPackage(it) == null) 1 else 0 }
            ordered.forEachIndexed { i, bridge ->
                bindAppRow(ItemAppBinding.inflate(inflater, card.rows, true), bridge, last = i == ordered.lastIndex)
            }
        }
    }

    private fun bindAppRow(row: ItemAppBinding, bridge: AppBridge, last: Boolean) {
        val pkg = installedPackage(bridge)
        row.name.setText(bridge.nameRes)
        row.divider.visibility = if (last) View.GONE else View.VISIBLE

        if (bridge.tested) {
            row.badge.setText(R.string.badge_tested)
            row.badge.backgroundTintList = ColorStateList.valueOf(color(MR.attr.colorSecondaryContainer))
            row.badge.setTextColor(color(MR.attr.colorOnSecondaryContainer))
        } else {
            row.badge.setText(R.string.badge_beta)
            row.badge.backgroundTintList = ColorStateList.valueOf(color(MR.attr.colorTertiaryContainer))
            row.badge.setTextColor(color(MR.attr.colorOnTertiaryContainer))
        }

        if (pkg != null) {
            row.icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
            row.detail.setText(bridge.descriptionRes)
            row.root.alpha = 1f
            row.toggle.isEnabled = true
            row.toggle.isChecked = settings.isAppEnabled(bridge.packageName)
            row.toggle.setOnCheckedChangeListener { _, checked -> settings.setAppEnabled(bridge.packageName, checked) }
        } else {
            row.icon.setImageResource(R.drawable.ic_launcher_foreground)
            row.icon.setBackgroundResource(R.drawable.bg_app_icon)
            row.detail.setText(R.string.app_not_installed)
            row.root.alpha = 0.55f
            row.toggle.isEnabled = false
            row.toggle.isChecked = false
        }
    }

    private fun installedPackage(bridge: AppBridge): String? = bridge.packages.firstOrNull { pkg ->
        try { packageManager.getApplicationInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }

    // ---------------------------------------------------------------- diagnostics

    private fun setupDiagnostics() {
        b.switchShade.isChecked = settings.showInShade
        b.switchShade.setOnCheckedChangeListener { _, checked -> settings.showInShade = checked }

        b.switchInspector.isChecked = settings.inspectorEnabled
        b.switchInspector.setOnCheckedChangeListener { _, checked -> settings.inspectorEnabled = checked }

        b.btnInspector.setOnClickListener { startActivity(Intent(this, InspectorActivity::class.java)) }
        b.btnRebind.setOnClickListener {
            IslandBridgeService.requestRebind(this)
            Toast.makeText(this, R.string.toast_rebind, Toast.LENGTH_SHORT).show()
            handler.postDelayed({ refreshStatus() }, 2500)
        }
    }

    private fun color(@AttrRes attr: Int): Int = MaterialColors.getColor(b.root, attr)
}

/** Read-only probes of the HyperOS focus-notification plumbing. */
object HyperOsChecks {
    fun hasFocusPermission(context: Context): Boolean = try {
        val extras = Bundle().apply { putString("package", context.packageName) }
        val result = context.contentResolver.call(
            "content://miui.statusbar.notification.public".toUri(), "canShowFocus", null, extras,
        )
        result?.getBoolean("canShowFocus", false) ?: false
    } catch (_: Exception) {
        false
    }

    @SuppressLint("PrivateApi")
    fun isIslandSupported(): Boolean = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        m.invoke(null, "persist.sys.feature.island", false) as Boolean
    } catch (_: Exception) {
        false
    }
}
