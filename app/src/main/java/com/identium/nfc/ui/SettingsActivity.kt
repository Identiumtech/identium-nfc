package com.identium.nfc.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.Backup
import com.identium.nfc.data.Counter
import com.identium.nfc.data.History
import com.identium.nfc.data.Profile
import com.identium.nfc.data.Templates

/**
 * App-wide preferences. Sections:
 *   - Your business card (Profile)
 *   - Auto-counter
 *   - Templates / History (data)
 *   - Backup / restore
 *   - Diagnostic / About
 */
class SettingsActivity : AppCompatActivity() {

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { restoreBackup(it) }
    }
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportBackup(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        // Profile -----------------------------------------------------
        root.addView(sectionHeader("Your business card"))
        val card = Profile.load(this)
        val profileLabel = if (Profile.isFilled(this))
            "${card.fullName.ifBlank { "Profile" }}${if (card.company.isNotBlank()) " · ${card.company}" else ""}"
        else "Set your name, contact, address — used as defaults"
        root.addView(caption(profileLabel))
        root.addView(outlinedButton("Edit business card") {
            startActivity(Intent(this, ProfileActivity::class.java))
        }.apply { setIcon(getDrawable(android.R.drawable.ic_menu_myplaces)) })

        // Counter -----------------------------------------------------
        root.addView(sectionHeader("Auto-counter").withTopMargin(dp(28)))
        root.addView(caption("Replace {n} in URL/text/MIME records with an auto-incrementing number on every successful write."))

        val counterSwitch = MaterialSwitch(this).apply {
            text = getString(R.string.auto_counter)
            isChecked = Counter.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Counter.setEnabled(this@SettingsActivity, on) }
        }
        root.addView(counterSwitch, lp().apply { topMargin = dp(8) })

        // Serial format — hex is common for cable-tie / asset serials.
        root.addView(caption("Serial format").withTopMargin(dp(12)))
        val formatSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Counter.Format.values().map { it.label }
            )
            setSelection(Counter.Format.values().indexOf(Counter.format(this@SettingsActivity)))
        }
        root.addView(formatSpinner, lp())

        val valueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val valueField = numField("Current value", Counter.current(this).toString())
        val padField = numField("Digits (0 = auto)", Counter.padding(this).toString())
        valueRow.addView(valueField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        valueRow.addView(padField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        root.addView(valueRow, lp().apply { topMargin = dp(12) })

        val applyBtn = MaterialButton(this).apply { text = "Save counter" }
        root.addView(applyBtn, lp().apply { topMargin = dp(12) })
        applyBtn.setOnClickListener {
            val v = (valueField.editText?.text?.toString().orEmpty()).toIntOrNull() ?: 1
            val p = (padField.editText?.text?.toString().orEmpty()).toIntOrNull() ?: 0
            val f = Counter.Format.values()
                .getOrElse(formatSpinner.selectedItemPosition) { Counter.Format.DECIMAL }
            Counter.setCurrent(this, v.coerceAtLeast(0))
            Counter.setPadding(this, p)
            Counter.setFormat(this, f)
            Toast.makeText(this, "Counter saved: ${Counter.render(v, p, f)}", Toast.LENGTH_SHORT).show()
        }

        // Data --------------------------------------------------------
        root.addView(sectionHeader("Data").withTopMargin(dp(28)))

        val templateCount = Templates.list(this).size
        root.addView(outlinedButton("Manage templates ($templateCount saved)") { manageTemplates() })

        val historyCount = History.load(this).size
        root.addView(outlinedButton("Open tag history ($historyCount entries)") {
            startActivity(Intent(this, HistoryActivity::class.java))
        })

        root.addView(outlinedButton("Statistics dashboard") {
            startActivity(Intent(this, StatsActivity::class.java))
        })

        // Backup ------------------------------------------------------
        root.addView(sectionHeader("Backup & restore").withTopMargin(dp(28)))
        root.addView(caption("Save profile, templates, history, and counter to a JSON file you can re-import on any device."))

        root.addView(outlinedButton("Export backup…") {
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            saveBackup.launch("identium-nfc-backup-$ts.json")
        })
        root.addView(outlinedButton("Restore from backup…") {
            pickBackup.launch(arrayOf("application/json", "*/*"))
        })

        val clearAll = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear history"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("Clear history?")
                    .setMessage("Removes all $historyCount log entries.")
                    .setPositiveButton("Clear") { _, _ -> History.clear(this@SettingsActivity); recreate() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        root.addView(clearAll, lp().apply { topMargin = dp(8) })

        // Tools / about ----------------------------------------------
        root.addView(sectionHeader("Tools").withTopMargin(dp(28)))

        root.addView(outlinedButton("Run NFC diagnostic") {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        })
        root.addView(outlinedButton("Open system NFC settings") {
            startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
        })
        root.addView(outlinedButton("Replay onboarding") {
            getSharedPreferences("identium_onboarding", MODE_PRIVATE).edit().clear().apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        })

        // About -------------------------------------------------------
        root.addView(sectionHeader("About").withTopMargin(dp(28)))
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "1.0" }
        root.addView(caption("Identium NFC v$versionName · free with every Identium NFC tag"))
        root.addView(outlinedButton("About Identium") {
            startActivity(Intent(this, AboutActivity::class.java))
        })
        root.addView(outlinedButton("Visit identium.in") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://identium.in")))
        })

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun manageTemplates() {
        val templates = Templates.list(this)
        if (templates.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No templates yet")
                .setMessage("Save a template from the Write tab — it will appear here for management.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = templates.map { "${it.name} (${it.records.size} record(s))" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Templates")
            .setItems(labels) { _, which ->
                val t = templates[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(t.name)
                    .setMessage(t.records.joinToString("\n") { "• ${it.title}: ${it.summary}" })
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("Delete") { _, _ ->
                        Templates.delete(this, t.name)
                        recreate()
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportBackup(uri: Uri) {
        try {
            val data = Backup.export(this).toByteArray(Charsets.UTF_8)
            contentResolver.openOutputStream(uri, "w")?.use { it.write(data) }
            Toast.makeText(this, "Backup exported", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreBackup(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore backup?")
            .setMessage("This replaces your history and merges templates / profile / counter from the file.")
            .setPositiveButton("Restore") { _, _ ->
                val res = Backup.importFrom(this, uri)
                if (res.success) {
                    Toast.makeText(this, "✓ ${res.message}", Toast.LENGTH_LONG).show()
                    recreate()
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Restore failed")
                        .setMessage(res.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- helpers ----------

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(getColor(R.color.brand_blue))
        textSize = 12f
        letterSpacing = 0.08f
        setPadding(0, dp(8), 0, dp(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun TextView.withTopMargin(px: Int): TextView {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = px }
        return this
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun outlinedButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun numField(hint: String, initial: String): TextInputLayout {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial)
        }
        til.addView(edit)
        return til
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
