package com.identium.nfc.ui

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.R
import com.identium.nfc.data.CsvImporter
import com.identium.nfc.data.History
import com.identium.nfc.data.ImportedRow
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.nfc.toNdef
import com.identium.nfc.util.SuccessDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bulk import + sequential write screen.
 *
 * Flow:
 *   1. user picks CSV / XLSX (OpenDocument)
 *   2. parse on a background thread, preview rows
 *   3. on Start, drive a row-at-a-time write loop using BaseNfcActivity's
 *      runOnNextTap — each successful tap auto-queues the next row
 *
 * If the parse fails we show the actual error from [CsvImporter] so the
 * user can tell whether the file is malformed, has no recognised columns,
 * or just isn't accessible.
 */
class ImportExcelActivity : BaseNfcActivity() {

    private lateinit var fileLabel: TextView
    private lateinit var summary: TextView
    private lateinit var preview: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var progressLabel: TextView

    private var rows: List<ImportedRow> = emptyList()
    private var currentRowIndex = 0
    private var failures = 0
    private var successes = 0

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Import & write sequentially"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val explainer = TextView(this).apply {
            text = "Pick a CSV or XLSX file. Each row becomes one or more NDEF records. " +
                    "After import, tap one tag per row — the app confirms each write and queues the next."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(explainer, lp().apply { bottomMargin = dp(16) })

        val pickBtn = MaterialButton(this).apply { text = "Pick file" }
        root.addView(pickBtn, lp())

        fileLabel = TextView(this).apply {
            text = "No file selected"
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(fileLabel)

        summary = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(8), 0, 0)
            textSize = 14f
        }
        root.addView(summary)

        preview = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(preview)

        startBtn = MaterialButton(this).apply {
            text = "Start writing"
            isEnabled = false
        }
        root.addView(startBtn, lp().apply { topMargin = dp(20) })

        progressLabel = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(progressLabel)

        val helpBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Show expected columns"
        }
        root.addView(helpBtn, lp().apply { topMargin = dp(20) })

        val sampleBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Copy sample CSV header"
        }
        root.addView(sampleBtn, lp().apply { topMargin = dp(8) })

        pickBtn.setOnClickListener {
            // OpenDocument with "*/*" lets the user pick anything; we sniff the
            // bytes to decide CSV vs XLSX, so the mime hint doesn't have to be
            // accurate.
            pickFile.launch(arrayOf("*/*"))
        }
        startBtn.setOnClickListener { startSequentialWrite() }
        helpBtn.setOnClickListener { showColumnHelp() }
        sampleBtn.setOnClickListener { copySampleHeader() }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun onFilePicked(uri: Uri) {
        fileLabel.text = uri.lastPathSegment ?: uri.toString()
        summary.text = "Parsing…"
        preview.text = ""
        startBtn.isEnabled = false
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { CsvImporter.importFromUri(this@ImportExcelActivity, uri) }
            }
            parsed.onSuccess { result ->
                rows = result
                summary.text = "Parsed ${result.size} row(s) → ${result.sumOf { it.records.size }} record(s) total"
                preview.text = result.take(20).joinToString("\n") { row ->
                    "#${row.lineNumber}: " + row.records.joinToString(" | ") { "${it.title}=${it.summary}" }
                } + if (result.size > 20) "\n…and ${result.size - 20} more" else ""
                startBtn.isEnabled = result.isNotEmpty()
            }.onFailure { err ->
                rows = emptyList()
                summary.text = "Could not parse this file"
                preview.text = ""
                SuccessDialog.showError(
                    this@ImportExcelActivity,
                    "Could not parse file",
                    err.message ?: err.javaClass.simpleName
                )
            }
        }
    }

    private fun startSequentialWrite() {
        if (rows.isEmpty()) return
        currentRowIndex = 0
        successes = 0
        failures = 0
        updateProgress()
        queueNext()
    }

    private fun queueNext() {
        if (currentRowIndex >= rows.size) {
            SuccessDialog.show(this, "Bulk write complete",
                "Wrote $successes tag(s) successfully" +
                        (if (failures > 0) " — $failures failed" else "."))
            return
        }
        val row = rows[currentRowIndex]
        val recordsForRow = row.records
        runOnNextTap(
            title = "Tap tag ${currentRowIndex + 1} of ${rows.size}",
            subtitle = "Row ${row.lineNumber}: ${row.source}",
            work = { tag ->
                val msg = android.nfc.NdefMessage(recordsForRow.map { it.toNdef() }.toTypedArray())
                TagOperations.writeNdef(tag, msg, makeReadOnly = false)
            },
            onResult = { result ->
                History.record(
                    this, History.Action.WRITE,
                    uid = "", tagType = "",
                    summary = "Bulk row ${row.lineNumber}: " + recordsForRow.joinToString(" + ") { it.title } +
                            (if (result.success) " (${result.bytesWritten}B)" else " — ${result.message}"),
                    success = result.success
                )
                if (result.success) successes++ else failures++
                currentRowIndex++
                updateProgress()
                if (result.success) {
                    // For sequential writes we keep going automatically, but
                    // still flash a quick confirmation so the user knows they
                    // can pull the tag away.
                    progressLabel.text = "✓ Row ${row.lineNumber} written (${result.bytesWritten} bytes)  •  $successes / ${rows.size}"
                    queueNext()
                } else {
                    SuccessDialog.showError(this, "Row ${row.lineNumber} failed", result.message)
                    // Offer to retry vs skip.
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Continue with the rest?")
                        .setMessage("Row ${row.lineNumber} failed. Continue with the next row?")
                        .setPositiveButton("Continue") { _, _ -> queueNext() }
                        .setNegativeButton("Stop", null)
                        .show()
                }
            }
        )
    }

    private fun updateProgress() {
        progressLabel.text = "Progress: $currentRowIndex / ${rows.size}  •  ✓ $successes  •  ✗ $failures"
    }

    private fun showColumnHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supported columns")
            .setMessage(
                "URL: url\n" +
                "Text: text\n" +
                "Email: email, email_subject, email_body\n" +
                "Phone: phone\n" +
                "SMS: sms_number, sms_body\n" +
                "Wi-Fi: ssid, wifi_password, wifi_auth (e.g. WPA2_PSK), wifi_enc (e.g. AES), wifi_hidden\n" +
                "vCard: vcard_name, vcard_company, vcard_title, vcard_phone, vcard_email, vcard_website, vcard_address, vcard_note\n" +
                "Geo: lat, lon, label\n" +
                "App: app_package\n" +
                "Custom: mime, mime_payload\n\n" +
                "First row must be the header. Empty cells are ignored. Each row produces one tag's worth of records."
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun copySampleHeader() {
        val sample = "url,text,phone,email,ssid,wifi_password,wifi_auth,wifi_enc,vcard_name,vcard_company,vcard_phone\n" +
                "https://identium.io,,,,Office,supersecret,WPA2_PSK,AES,,,\n" +
                ",,+15551234567,,,,,,,,\n" +
                ",,,,,,,,Jane Doe,Identium,+15559876543\n"
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Identium NFC sample", sample))
        android.widget.Toast.makeText(this, "Sample CSV copied to clipboard", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
