package com.identium.nfc.ui

import android.nfc.tech.NfcA
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.R
import com.identium.nfc.data.History
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.Ntag21x
import com.identium.nfc.util.SuccessDialog

/**
 * Raw-memory clone for NTAG21x / Mifare Ultralight tags.
 *
 * Difference from CopyTagActivity:
 *   - CopyTag reads NDEF *records*, builds new ones for the target — works
 *     across mismatched chip types but loses anything that isn't NDEF.
 *   - CloneTag reads pages 4..lastUserPage byte-for-byte and writes the
 *     same bytes to the target — preserves NDEF, custom data, counter
 *     values, and anything else stored in user memory.
 *
 * UID cloning is NOT supported. Standard NFC chips ship with a factory-
 * permanent UID; only specialty 'magic' / Gen2 chips accept a UID rewrite
 * and require chip-specific unlock commands not implemented here.
 *
 * Page layout we touch:
 *   - Pages 0..1: UID — read-only, never written
 *   - Page  2:    Static lock bytes (OTP) — never written, would lock target
 *   - Page  3:    Capability Container — left alone (target's own CC is
 *                 already correct for its size)
 *   - Pages 4..lastUserPage: user memory — cloned
 *   - Past lastUserPage: dynamic lock + CFG + PWD/PACK — skipped (could
 *                 brick the target if mis-cloned)
 */
class CloneTagActivity : BaseNfcActivity() {

    private lateinit var statusView: TextView
    private lateinit var preview: TextView
    private lateinit var pasteBtn: MaterialButton

    private var captured: ClonePayload? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Clone tag"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val explainer = TextView(this).apply {
            text = "Mirrors the raw user memory (pages 4 to last) from one NTAG / Mifare Ultralight onto another. " +
                    "Source and target must be the same chip type. The UID itself is never copied — that's fixed at " +
                    "the factory on standard tags."
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
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(preview)

        pasteBtn = MaterialButton(this).apply {
            text = "Tap target & clone"
            isEnabled = false
        }
        root.addView(pasteBtn, lp().apply { topMargin = dp(20) })

        captureBtn.setOnClickListener { captureSource() }
        pasteBtn.setOnClickListener { writeToTarget() }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun captureSource() {
        runOnNextTap(
            title = "Tap source tag",
            subtitle = "Reading every user-memory page from the source.",
            work = { tag ->
                val nfcA = NfcA.get(tag) ?: throw IllegalStateException(
                    "Source is not an NfcA tag (NTAG / Mifare Ultralight required)."
                )
                nfcA.connect()
                try {
                    val variant = Ntag21x.detect(nfcA)
                        ?: detectByMemorySize(nfcA)
                        ?: throw IllegalStateException(
                            "Could not identify chip — only NTAG213 / 215 / 216 and Mifare Ultralight are supported."
                        )
                    val startPage = 4
                    val endPage = variant.lastUserPage
                    val pages = mutableListOf<ByteArray>()
                    for (p in startPage..endPage) {
                        val resp = Ntag21x.readBlock(nfcA, p)
                        if (resp.size < 4) throw java.io.IOException("Read returned ${resp.size} bytes at page $p")
                        pages += resp.copyOfRange(0, 4)
                    }
                    ClonePayload(
                        variant = variant,
                        startPage = startPage,
                        pages = pages,
                        sourceUid = HexUtil.toHex(tag.id, ":")
                    )
                } finally {
                    runCatching { nfcA.close() }
                }
            },
            onResult = { payload ->
                captured = payload
                val bytes = payload.pages.size * 4
                statusView.text = "✓ Captured ${payload.pages.size} pages ($bytes bytes) from " +
                        "${variantLabel(payload.variant)} • UID ${payload.sourceUid}"
                preview.text = HexUtil.hexDump(payload.flattenedBytes())
                pasteBtn.isEnabled = true
            }
        )
    }

    private fun writeToTarget() {
        val payload = captured ?: return
        runOnNextTap(
            title = "Tap target tag",
            subtitle = "Hold a blank ${variantLabel(payload.variant)}. Same chip type required — " +
                    "we'll abort if the target is different.",
            work = { tag ->
                val nfcA = NfcA.get(tag) ?: throw IllegalStateException(
                    "Target is not an NfcA tag."
                )
                nfcA.connect()
                try {
                    val targetVariant = Ntag21x.detect(nfcA) ?: detectByMemorySize(nfcA)
                    if (targetVariant == null) {
                        throw IllegalStateException("Could not identify target chip type.")
                    }
                    if (targetVariant != payload.variant) {
                        throw IllegalStateException(
                            "Mismatch: source was ${variantLabel(payload.variant)} " +
                                    "but target is ${variantLabel(targetVariant)}. " +
                                    "Use Copy tag instead for cross-type copies."
                        )
                    }
                    // Write each captured page back. We deliberately avoid pages
                    // 0–3 (UID, lock bytes, CC) and anything past lastUserPage
                    // (dynamic lock / CFG / PWD) so we can never brick a target.
                    var written = 0
                    payload.pages.forEachIndexed { i, data ->
                        val targetPage = payload.startPage + i
                        Ntag21x.writePage(nfcA, targetPage, data)
                        written++
                    }
                    val uid = HexUtil.toHex(tag.id, ":")
                    CloneResult(written, uid)
                } finally {
                    runCatching { nfcA.close() }
                }
            },
            onResult = { result ->
                History.record(
                    this, History.Action.CLONE,
                    uid = result.targetUid,
                    tagType = variantLabel(payload.variant),
                    summary = "${result.pagesWritten} pages (${result.pagesWritten * 4}B) from ${payload.sourceUid}",
                    success = true
                )
                SuccessDialog.show(
                    this,
                    title = "Tag cloned",
                    body = "${result.pagesWritten} pages (${result.pagesWritten * 4} bytes) cloned to target tag.\n" +
                            "Target UID: ${result.targetUid}\n\n" +
                            "Note: target keeps its own factory UID — only the data was cloned."
                )
            }
        )
    }

    /**
     * Fallback detection for chips that don't respond to GET_VERSION (some
     * older Mifare Ultralight C / clones). We try reading page 0x0F — if it
     * succeeds we treat it as Ultralight (16 pages); otherwise unknown.
     */
    private fun detectByMemorySize(nfcA: NfcA): Ntag21x.Variant? {
        return try {
            nfcA.transceive(byteArrayOf(Ntag21x.CMD_READ, 0x0F))
            Ntag21x.Variant.ULTRALIGHT
        } catch (_: Exception) { null }
    }

    private fun variantLabel(v: Ntag21x.Variant) = when (v) {
        Ntag21x.Variant.NTAG_213 -> "NTAG213 (180B user)"
        Ntag21x.Variant.NTAG_215 -> "NTAG215 (504B user)"
        Ntag21x.Variant.NTAG_216 -> "NTAG216 (888B user)"
        Ntag21x.Variant.ULTRALIGHT -> "Mifare Ultralight (48B user)"
        Ntag21x.Variant.ULTRALIGHT_C -> "Mifare Ultralight C (144B user)"
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class ClonePayload(
        val variant: Ntag21x.Variant,
        val startPage: Int,
        val pages: List<ByteArray>,
        val sourceUid: String
    ) {
        fun flattenedBytes(): ByteArray {
            val out = ByteArray(pages.size * 4)
            for ((i, p) in pages.withIndex()) System.arraycopy(p, 0, out, i * 4, 4)
            return out
        }
    }

    private data class CloneResult(val pagesWritten: Int, val targetUid: String)
}
