package com.identium.nfc.ui

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.History
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.util.SuccessDialog

/**
 * Tag QA: tap a tag and check whether its first NDEF record matches an
 * expected URL or text. Useful for production-line verification — write a
 * batch of tags, then re-tap each one to confirm what's actually on it.
 */
class VerifyTagActivity : BaseNfcActivity() {

    private lateinit var expectedField: TextInputEditText
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.verify_tag)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val explainer = TextView(this).apply {
            text = "Type the URL or text you expect to find on the tag, then tap the tag. " +
                    "We'll compare every NDEF record on the tag against the expected value and tell you " +
                    "exactly what's there."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(explainer, lp().apply { bottomMargin = dp(16) })

        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Expected URL or text"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        expectedField = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        til.addView(expectedField)
        root.addView(til, lp())

        val verifyBtn = MaterialButton(this).apply { text = "Tap tag to verify" }
        root.addView(verifyBtn, lp().apply { topMargin = dp(20) })

        statusView = TextView(this).apply {
            setPadding(0, dp(20), 0, 0)
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
        }
        root.addView(statusView)

        detailView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(detailView)

        verifyBtn.setOnClickListener {
            val expected = expectedField.text?.toString().orEmpty().trim()
            if (expected.isEmpty()) {
                til.error = "Required"
                return@setOnClickListener
            }
            til.error = null

            runOnNextTap(
                title = "Tap tag to verify",
                subtitle = "Reading NDEF and comparing against your expected value.",
                work = { tag ->
                    val info = TagOperations.read(tag)
                    val records = info.ndefMessage?.records?.toList().orEmpty()
                    val readableValues = records.map { rec -> describe(rec) }
                    val match = readableValues.any { it.endsWith(expected) || it.contains(expected) }
                    VerifyResult(
                        match = match,
                        uid = info.uidHex,
                        type = info.type.display,
                        recordsFound = readableValues
                    )
                },
                onResult = { result ->
                    if (result.match) {
                        statusView.text = "✓ Match"
                        statusView.setTextColor(getColor(R.color.success))
                        SuccessDialog.show(this,
                            title = "Tag verified",
                            body = "The expected value is on the tag.\nUID: ${result.uid}\nType: ${result.type}")
                    } else {
                        statusView.text = "✗ No match"
                        statusView.setTextColor(getColor(R.color.error))
                        SuccessDialog.showError(this,
                            title = "Tag does not match",
                            body = "Expected was not found on the tag.\nFound ${result.recordsFound.size} record(s).")
                    }
                    detailView.text = "UID ${result.uid}  •  ${result.type}\n\n" +
                            (if (result.recordsFound.isEmpty()) "(tag has no NDEF records)"
                             else result.recordsFound.withIndex()
                                .joinToString("\n") { (i, v) -> "${i + 1}. $v" })
                    History.record(this, History.Action.VERIFY,
                        uid = result.uid, tagType = result.type,
                        summary = if (result.match) "matched: $expected" else "expected $expected, found ${result.recordsFound.firstOrNull() ?: "(empty)"}",
                        success = result.match)
                }
            )
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun describe(rec: android.nfc.NdefRecord): String = when (rec.tnf) {
        android.nfc.NdefRecord.TNF_WELL_KNOWN -> {
            if (rec.type.contentEquals(android.nfc.NdefRecord.RTD_URI))
                rec.toUri()?.toString() ?: ""
            else if (rec.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)) {
                val payload = rec.payload
                if (payload.isEmpty()) ""
                else {
                    val langLen = payload[0].toInt() and 0x3F
                    String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                }
            } else String(rec.type, Charsets.US_ASCII)
        }
        android.nfc.NdefRecord.TNF_MIME_MEDIA -> {
            String(rec.type, Charsets.US_ASCII) + " (${rec.payload.size}B)"
        }
        else -> "TNF " + rec.tnf
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class VerifyResult(
        val match: Boolean,
        val uid: String,
        val type: String,
        val recordsFound: List<String>
    )
}
