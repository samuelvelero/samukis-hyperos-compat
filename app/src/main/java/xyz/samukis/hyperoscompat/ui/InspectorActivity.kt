package xyz.samukis.hyperoscompat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import xyz.samukis.hyperoscompat.R
import xyz.samukis.hyperoscompat.databinding.ActivityInspectorBinding
import xyz.samukis.hyperoscompat.inspector.NotificationLog

class InspectorActivity : AppCompatActivity() {
    private lateinit var b: ActivityInspectorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInspectorBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        b.btnRefresh.setOnClickListener { load() }
        b.btnCopy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("inspector", b.log.text))
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
        b.btnClear.setOnClickListener { NotificationLog.clear(this); load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val text = NotificationLog.read(this)
        b.log.text = text.ifEmpty { getString(R.string.inspector_empty) }
    }
}
