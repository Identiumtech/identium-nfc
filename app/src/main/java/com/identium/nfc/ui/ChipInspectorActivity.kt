package com.identium.nfc.ui

import android.nfc.tech.NfcA
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.identium.nfc.R
import com.identium.nfc.data.History
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.Ntag21x
import com.identium.nfc.nfc.OriginalitySignature
import com.identium.nfc.util.SuccessDialog

/**
 * Chip Inspector — offline anti-counterfeit + telemetry for NXP NTAG21x.
 *
 * On a tag tap we:
 *  1. Read the 32-byte ECC originality signature (READ_SIG) and verify it
 *     against NXP's public key with [OriginalitySignature] — fully offline,
 *     no network. A GENUINE result means the silicon is real NXP.
 *  2. Read the NFC scan counter (READ_CNT) if the chip has it enabled — a
 *     one-way counter that increments every time the tag is read, useful as
 *     a tamper / engagement signal.
 *  3. Show chip variant, UID and signature bytes.
 */
class ChipInspectorActivity : BaseNfcActivity() {

    private lateinit var resultCard: LinearLayout
    private lateinit var verdictView: TextView
    private lateinit var detailView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Chip authenticity"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        root.addView(TextView(this).apply {
            text = "Verify that a tag's chip is genuine NXP silicon — completely offline. " +
                    "NXP NTAG213/215/216 chips carry a factory ECC signature that clones can't " +
                    "forge. We also read the chip's built-in scan counter when available."
            setTextColor(getColor(R.color.text_secondary))
        }, lp().apply { bottomMargin = dp(16) })

        val btn = MaterialButton(this).apply {
            text = "Tap a tag to check"
            setIconResource(R.drawable.ic_read)
        }
        root.addView(btn, lp())
        btn.setOnClickListener { runCheck() }

        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            visibility = LinearLayout.GONE
        }
        verdictView = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        detailView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        resultCard.addView(verdictView)
        resultCard.addView(detailView)
        root.addView(resultCard, lp().apply { topMargin = dp(16) })

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun runCheck() {
        runOnNextTap(
            title = "Tap a tag to check",
            subtitle = "Reading originality signature and scan counter…",
            work = { tag ->
                val uid = tag.id
                val nfcA = NfcA.get(tag) ?: throw IllegalStateException("Not an NfcA tag — NTAG21x required.")
                nfcA.connect()
                try {
                    val variant = Ntag21x.detect(nfcA)
                    val signature = runCatching { Ntag21x.readSignature(nfcA) }.getOrNull()
                    val verdict = if (signature != null && signature.size >= 32)
                        OriginalitySignature.verify(uid, signature.copyOfRange(0, 32))
                    else OriginalitySignature.Result.BAD_INPUT
                    val counter = runCatching {
                        val c = Ntag21x.readCounter(nfcA)
                        if (c.size >= 3) (c[0].toInt() and 0xFF) or
                                ((c[1].toInt() and 0xFF) shl 8) or
                                ((c[2].toInt() and 0xFF) shl 16)
                        else null
                    }.getOrNull()
                    InspectResult(
                        uidHex = HexUtil.toHex(uid, ":"),
                        variant = variant?.name,
                        signature = signature,
                        verdict = verdict,
                        scanCount = counter
                    )
                } finally {
                    runCatching { nfcA.close() }
                }
            },
            onResult = { res -> render(res) }
        )
    }

    private fun render(res: InspectResult) {
        resultCard.visibility = LinearLayout.VISIBLE
        when (res.verdict) {
            OriginalitySignature.Result.GENUINE -> {
                verdictView.text = "✓ Genuine NXP chip"
                verdictView.setTextColor(getColor(R.color.success))
            }
            OriginalitySignature.Result.INVALID -> {
                verdictView.text = "✗ Signature does NOT match"
                verdictView.setTextColor(getColor(R.color.error))
            }
            OriginalitySignature.Result.BAD_INPUT -> {
                verdictView.text = "— No signature on this chip"
                verdictView.setTextColor(getColor(R.color.warning))
            }
        }

        val sb = StringBuilder()
        sb.append("UID: ${res.uidHex}\n")
        sb.append("Chip: ${res.variant ?: "Unknown / not NTAG21x"}\n")
        sb.append("Scan counter: ${res.scanCount?.let { "$it reads" } ?: "not enabled on this chip"}\n\n")
        if (res.signature != null) {
            sb.append("Originality signature (32 bytes):\n")
            sb.append(HexUtil.toHex(res.signature.copyOfRange(0, minOf(32, res.signature.size)), " "))
            sb.append("\n\n")
        }
        sb.append(when (res.verdict) {
            OriginalitySignature.Result.GENUINE ->
                "The ECC signature validates against NXP's public key for this UID — the silicon is authentic NXP."
            OriginalitySignature.Result.INVALID ->
                "The signature did not validate. The chip may be a clone, a non-NXP IC, or the signature was overwritten."
            OriginalitySignature.Result.BAD_INPUT ->
                "This chip didn't return a 32-byte signature. Only NXP NTAG21x (and a few related ICs) support the originality signature."
        })
        detailView.text = sb.toString()

        History.record(
            this, History.Action.VERIFY,
            uid = res.uidHex,
            tagType = res.variant ?: "chip",
            summary = "Authenticity: ${res.verdict.name}" + (res.scanCount?.let { " · $it reads" } ?: ""),
            success = res.verdict == OriginalitySignature.Result.GENUINE
        )

        if (res.verdict == OriginalitySignature.Result.GENUINE) {
            SuccessDialog.show(this, "Genuine NXP chip",
                "The originality signature is valid for UID ${res.uidHex}." +
                        (res.scanCount?.let { "\n\nScanned $it time(s) so far." } ?: ""))
        }
    }

    private data class InspectResult(
        val uidHex: String,
        val variant: String?,
        val signature: ByteArray?,
        val verdict: OriginalitySignature.Result,
        val scanCount: Int?
    )

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
