package com.cyrfix.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivitySafely(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        val group = findViewById<RadioGroup>(R.id.backdrop_group)
        group.check(
            when (prefs.backdrop) {
                Backdrop.AUTO -> R.id.backdrop_auto
                Backdrop.DARK -> R.id.backdrop_dark
                Backdrop.LIGHT -> R.id.backdrop_light
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.backdrop = when (checkedId) {
                R.id.backdrop_dark -> Backdrop.DARK
                R.id.backdrop_light -> Backdrop.LIGHT
                else -> Backdrop.AUTO
            }
        }

        val scaleLabel = findViewById<TextView>(R.id.scale_label)
        val seek = findViewById<SeekBar>(R.id.scale_seek)
        // 0..50 maps onto 0.75..1.25
        seek.progress = ((prefs.textScale - 0.75f) * 100f).toInt().coerceIn(0, 50)
        scaleLabel.text = scaleText(prefs.textScale)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.75f + progress / 100f
                prefs.textScale = v
                scaleLabel.text = scaleText(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        findViewById<SwitchCompat>(R.id.switch_whole_screen).apply {
            isChecked = prefs.scanWholeScreen
            setOnCheckedChangeListener { _, checked -> prefs.scanWholeScreen = checked }
        }

        findViewById<SwitchCompat>(R.id.switch_diagnostics).apply {
            isChecked = prefs.diagnostics
            setOnCheckedChangeListener { _, checked -> prefs.diagnostics = checked }
        }

        findViewById<Button>(R.id.btn_share_dump).setOnClickListener { shareDump() }
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled(this)
        status.text = getString(if (on) R.string.status_on else R.string.status_off)
        status.setTextColor(if (on) 0xFF1DB954.toInt() else 0xFFFE2C55.toInt())
    }

    private fun scaleText(v: Float): String =
        getString(R.string.scale_label) + "  ${(v * 100).toInt()}%"

    private fun shareDump() {
        val file = File(File(filesDir, "dumps"), CyrFixService.DUMP_FILE)
        if (!file.exists()) {
            Toast.makeText(this, R.string.no_dump, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(Intent.createChooser(share, getString(R.string.share_dump)))
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not open that screen on this device", Toast.LENGTH_LONG)
                .show()
        }
    }

    companion object {
        fun isServiceEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${CyrFixService::class.java.name}"

            // The AccessibilityManager list is the reliable source; the Settings
            // string is only a fallback for OEMs that report an empty list.
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.forEach { info ->
                    if (info.id?.contains(context.packageName) == true) return true
                }

            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (entry in splitter) {
                if (entry.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
