package com.lingo.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var packsLabel: TextView

    private val languages by lazy { LanguagePacks.allLanguages() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        packsLabel = findViewById(R.id.packs_label)

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

        setUpTargetLanguage()
        setUpLanguagePacks()
        setUpBubbleTheme()
        setUpTextScale()

        findViewById<SwitchCompat>(R.id.switch_wifi_only).apply {
            isChecked = prefs.wifiOnlyDownloads
            setOnCheckedChangeListener { _, checked -> prefs.wifiOnlyDownloads = checked }
        }

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
        status.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFE53935.toInt())
        refreshPacks()
    }

    // ------------------------------------------------------------- target language

    private fun setUpTargetLanguage() {
        val spinner = findViewById<Spinner>(R.id.spinner_target)
        spinner.adapter = languageAdapter()

        val current = prefs.targetLanguage
        spinner.setSelection(languages.indexOfFirst { it.code == current }.coerceAtLeast(0), false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val picked = languages[position].code
                if (picked == prefs.targetLanguage) return
                prefs.targetLanguage = picked
                refreshPacks()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    // -------------------------------------------------------------- language packs

    private fun setUpLanguagePacks() {
        val spinner = findViewById<Spinner>(R.id.spinner_download)
        spinner.adapter = languageAdapter()
        spinner.setSelection(
            languages.indexOfFirst { it.code == prefs.targetLanguage }.coerceAtLeast(0),
            false
        )

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            val picked = languages.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
            toast(getString(R.string.pack_downloading, picked.label))
            LanguagePacks.download(picked.code, prefs.wifiOnlyDownloads) { ok ->
                if (isFinishing || isDestroyed) return@download
                toast(
                    getString(
                        if (ok) R.string.pack_ready else R.string.pack_failed,
                        picked.label
                    )
                )
                refreshPacks()
            }
        }

        findViewById<Button>(R.id.btn_delete_pack).setOnClickListener {
            val picked = languages.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
            LanguagePacks.delete(picked.code) { ok ->
                if (isFinishing || isDestroyed) return@delete
                toast(
                    if (ok) getString(R.string.pack_deleted, picked.label)
                    else getString(R.string.pack_delete_failed, picked.label)
                )
                refreshPacks()
            }
        }
    }

    private fun refreshPacks() {
        LanguagePacks.downloaded { codes ->
            if (isFinishing || isDestroyed) return@downloaded
            val names = codes.map { LanguagePacks.displayName(it) }.sorted()
            packsLabel.text = if (names.isEmpty()) {
                getString(R.string.packs_none)
            } else {
                getString(R.string.packs_on_device, names.joinToString(", "))
            }
        }
    }

    private fun languageAdapter(): ArrayAdapter<LanguageOption> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, languages).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ------------------------------------------------------------------ appearance

    private fun setUpBubbleTheme() {
        val group = findViewById<RadioGroup>(R.id.bubble_group)
        group.check(
            when (prefs.bubbleTheme) {
                BubbleTheme.AUTO -> R.id.bubble_auto
                BubbleTheme.DARK -> R.id.bubble_dark
                BubbleTheme.LIGHT -> R.id.bubble_light
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.bubbleTheme = when (checkedId) {
                R.id.bubble_dark -> BubbleTheme.DARK
                R.id.bubble_light -> BubbleTheme.LIGHT
                else -> BubbleTheme.AUTO
            }
        }
    }

    private fun setUpTextScale() {
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
    }

    private fun scaleText(v: Float): String =
        getString(R.string.scale_label) + "  ${(v * 100).toInt()}%"

    // ----------------------------------------------------------------- diagnostics

    private fun shareDump() {
        val file = File(File(filesDir, "dumps"), LingoService.DUMP_FILE)
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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
            val expected = "${context.packageName}/${LingoService::class.java.name}"

            // The AccessibilityManager list is the reliable source; the Settings
            // string is only a fallback for OEMs that report an empty list.
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.forEach { info ->
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
