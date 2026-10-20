package xyz.samukis.hyperoscompat.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import xyz.samukis.hyperoscompat.BuildConfig
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.bridge.BridgeRegistry
import xyz.samukis.hyperoscompat.databinding.ActivityMainBinding
import xyz.samukis.hyperoscompat.databinding.ItemAppBinding
import xyz.samukis.hyperoscompat.databinding.ItemStatusBinding
import xyz.samukis.hyperoscompat.island.IslandPoster
import xyz.samukis.hyperoscompat.prefs.Settings
import xyz.samukis.hyperoscompat.service.IslandBridgeService

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        if (!hasPostPermission()) requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setupStatusRows()
        setupApps()
        setupTools()

        b.footer.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
        b.footerRepo.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://${getString(R.string.footer_repo)}".toUri()))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Opening the app is a good moment to drop islands whose call already ended.
        IslandBridgeService.instance?.resync()
    }

    private fun hasPostPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun setupStatusRows() {
        b.statusListener.label.setText(R.string.status_listener)
        b.statusListener.button.setText(R.string.action_grant)
        b.statusListener.button.setOnClickListener { openListenerSettings() }

        b.statusFocus.label.setText(R.string.status_focus)
        b.statusFocus.button.setText(R.string.action_open)
        b.statusFocus.button.setOnClickListener {
            startActivity(
                Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        b.statusIsland.label.setText(R.string.status_island)
        b.statusService.label.setText(R.string.status_service)
    }

    private fun refreshStatus() {
        val listenerOk = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        setStatus(b.statusListener, listenerOk, showButton = !listenerOk)

        val focusOk = HyperOsChecks.hasFocusPermission(this)
        setStatus(b.statusFocus, focusOk, showButton = true)
        b.focusHint.visibility = if (focusOk) View.GONE else View.VISIBLE

        setStatus(
            b.statusIsland, HyperOsChecks.isIslandSupported(), showButton = false,
            okText = R.string.status_yes, badText = R.string.status_no,
        )
        setStatus(
            b.statusService, IslandBridgeService.connected, showButton = false,
            okText = R.string.status_yes, badText = R.string.status_no,
        )
    }

    private fun setStatus(
        row: ItemStatusBinding,
        ok: Boolean,
        showButton: Boolean,
        @StringRes okText: Int = R.string.status_ok,
        @StringRes badText: Int = R.string.status_missing,
    ) {
        row.value.setText(if (ok) okText else badText)
        row.value.setTextColor(ContextCompat.getColor(this, if (ok) R.color.status_ok else R.color.status_bad))
        row.button.visibility = if (showButton) View.VISIBLE else View.GONE
    }

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

    private fun setupApps() {
        val inflater = LayoutInflater.from(this)
        b.appsContainer.removeAllViews()
        for (bridge in BridgeRegistry.all(this)) {
            val row = ItemAppBinding.inflate(inflater, b.appsContainer, true)
            row.name.setText(bridge.nameRes)
            val installed = try {
                row.icon.setImageDrawable(packageManager.getApplicationIcon(bridge.packageName))
                true
            } catch (_: PackageManager.NameNotFoundException) {
                row.icon.setImageResource(R.mipmap.ic_launcher)
                false
            }
            row.detail.text = if (installed) bridge.packageName else getString(R.string.app_not_installed)
            row.toggle.isEnabled = installed
            row.toggle.isChecked = settings.isAppEnabled(bridge.packageName)
            row.toggle.setOnCheckedChangeListener { _, checked ->
                settings.setAppEnabled(bridge.packageName, checked)
            }
        }
    }

    private fun setupTools() {
        b.switchShade.isChecked = settings.showInShade
        b.switchShade.setOnCheckedChangeListener { _, checked -> settings.showInShade = checked }

        b.switchInspector.isChecked = settings.inspectorEnabled
        b.switchInspector.setOnCheckedChangeListener { _, checked -> settings.inspectorEnabled = checked }

        b.btnTest.setOnClickListener {
            if (!hasPostPermission()) {
                Toast.makeText(this, R.string.toast_need_post_permission, Toast.LENGTH_SHORT).show()
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }
            IslandPoster(this, settings).postDemo()
            Toast.makeText(this, R.string.toast_test_posted, Toast.LENGTH_SHORT).show()
        }
        b.btnInspector.setOnClickListener { startActivity(Intent(this, InspectorActivity::class.java)) }
        b.btnRebind.setOnClickListener {
            IslandBridgeService.requestRebind(this)
            Toast.makeText(this, R.string.toast_rebind, Toast.LENGTH_SHORT).show()
        }
    }
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
