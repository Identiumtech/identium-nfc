package com.identium.nfc.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.identium.nfc.R
import com.identium.nfc.data.History
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.nfc.WriteRecord
import com.identium.nfc.nfc.toNdef
import com.identium.nfc.util.SuccessDialog

/**
 * Two-step copy:
 *   1. tap source tag — capture its NDEF records
 *   2. tap target tag — write those records back
 *
 * Each tap is handled inside this activity via foreground dispatch (see
 * [BaseNfcActivity]) so the OS doesn't bounce us back to MainActivity.
 */
class CopyTagActivity : BaseNfcActivity() {

    private lateinit var statusView: TextView
    private lateinit var preview: TextView
    private lateinit var pasteBtn: MaterialButton

    private var captured: List<WriteRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Copy tag"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val explainer = TextView(this).apply {
            text = "Tap the source tag to capture its NDEF contents. Then tap a blank target tag to copy them over. " +
                    "Standard tag UIDs are factory-set and cannot be overwritten — only the data is copied."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(explainer, lp().apply { bottomMargin = dp(16) })

        val captureBtn = MaterialButton(this).apply { text = "Capture source" }
        root.addView(captureBtn, lp())

        statusView = TextView(this).apply {
            text = "No source captured yet"
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(statusView)

        preview = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(preview)

        pasteBtn = MaterialButton(this).apply {
            text = "Tap target & paste"
            isEnabled = false
        }
        root.addView(pasteBtn, lp().apply { topMargin = dp(20) })

        captureBtn.setOnClickListener {
            runOnNextTap(
                title = "Tap source tag",
                subtitle = "We'll read its NDEF records and stage them for the next tag.",
                work = { tag ->
                    val info = TagOperations.read(tag)
                    val msg = info.ndefMessage
                    val records: List<WriteRecord> = msg?.records?.mapNotNull(::recordFromNdef) ?: emptyList()
                    Pair(info, records)
                },
                onResult = { (info, records) ->
                    captured = records
                    if (records.isEmpty()) {
                        statusView.text = "Source tag has no NDEF records — captured UID ${info.uidHex}"
                        preview.text = "(no NDEF content to copy)"
                        pasteBtn.isEnabled = false
                    } else {
                        statusView.text = "✓ Captured ${records.size} record(s) from UID ${info.uidHex}"
                        preview.text = records.joinToString("\n") { "• ${it.title}: ${it.summary}" }
                        pasteBtn.isEnabled = true
                    }
                }
            )
        }

        pasteBtn.setOnClickListener {
            if (captured.isEmpty()) return@setOnClickListener
            runOnNextTap(
                title = "Tap target tag",
                subtitle = "Hold a blank target tag — we'll write the ${captured.size} record(s) we captured.",
                work = { tag ->
                    val msg = android.nfc.NdefMessage(captured.map { it.toNdef() }.toTypedArray())
                    TagOperations.writeNdef(tag, msg, makeReadOnly = false)
                },
                onResult = { result ->
                    History.record(
                        this, History.Action.COPY,
                        uid = "", tagType = "",
                        summary = "${captured.size} record(s), ${result.bytesWritten}B",
                        success = result.success
                    )
                    if (result.success) SuccessDialog.show(this, "Tag copied", "${result.bytesWritten} bytes written.")
                    else SuccessDialog.showError(this, "Copy failed", result.message)
                }
            )
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    /**
     * Best-effort reverse of [WriteRecord.toNdef]: handles URI/Text wellknown,
     * Wi-Fi WSC mime, vCard mime, BT OOB mime, External Android App, and
     * falls through to CustomMime for anything else so the user can re-write.
     */
    private fun recordFromNdef(record: android.nfc.NdefRecord): WriteRecord? = when (record.tnf) {
        android.nfc.NdefRecord.TNF_WELL_KNOWN -> {
            if (record.type.contentEquals(android.nfc.NdefRecord.RTD_URI)) {
                record.toUri()?.toString()?.let { WriteRecord.Url(it) }
            } else if (record.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)) {
                val payload = record.payload
                if (payload.isEmpty()) WriteRecord.Text("")
                else {
                    val langLen = payload[0].toInt() and 0x3F
                    val text = String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                    val lang = String(payload, 1, langLen, Charsets.US_ASCII)
                    WriteRecord.Text(text, lang)
                }
            } else null
        }
        android.nfc.NdefRecord.TNF_MIME_MEDIA -> {
            val mime = String(record.type, Charsets.US_ASCII)
            WriteRecord.CustomMime(mime, record.payload.toString(Charsets.UTF_8))
        }
        android.nfc.NdefRecord.TNF_EXTERNAL_TYPE -> {
            val type = String(record.type, Charsets.US_ASCII)
            if (type == "android.com:pkg")
                WriteRecord.App(String(record.payload, Charsets.UTF_8))
            else WriteRecord.CustomMime(type, record.payload.toString(Charsets.UTF_8))
        }
        else -> null
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
