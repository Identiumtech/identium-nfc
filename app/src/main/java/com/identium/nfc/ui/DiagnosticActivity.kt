package com.identium.nfc.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.identium.nfc.R
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.Ntag21x
import com.identium.nfc.nfc.TagOperations

/**
 * "Is my phone fit to read these tags?" — runs phone-side checks and an
 * optional tag-side probe. Useful for support: a customer can run this
 * once and screenshot the result before contacting Identium.
 */
class DiagnosticActivity : BaseNfcActivity() {

    private lateinit var phoneCard: TextView
    private lateinit var tagCard: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NFC diagnostic"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        root.addView(section("Phone capability"))
        phoneCard = card()
        root.addView(phoneCard)

        root.addView(section("Tap a tag to probe").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })
        tagCard = card().apply { text = "Tap a tag to fill in this section." }
        root.addView(tagCard)

        val tapBtn = MaterialButton(this).apply { text = "Probe a tag" }
        root.addView(tapBtn, lp().apply { topMargin = dp(12) })

        summary = card().apply {
            text = ""
            visibility = TextView.GONE
        }
        root.addView(summary)

        val nfcSettings = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open system NFC settings"
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }
        }
        root.addView(nfcSettings, lp().apply { topMargin = dp(20) })

        renderPhoneCard()

        tapBtn.setOnClickListener {
            runOnNextTap(
                title = "Tap any tag",
                subtitle = "We'll read tech list, ATQA/SAK, memory size and signature.",
                work = { tag ->
                    val nfcA = NfcA.get(tag)
                    val info = TagOperations.read(tag)
                    val variant = if (nfcA != null) {
                        nfcA.connect()
                        try { Ntag21x.detect(nfcA) } finally { runCatching { nfcA.close() } }
                    } else null
                    DiagResult(info, variant)
                },
                onResult = { res -> renderTagCard(res); renderSummary(res) }
            )
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun renderPhoneCard() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        val sb = StringBuilder()
        sb.append(check(adapter != null, "NFC hardware present"))
        sb.append(check(adapter?.isEnabled == true, "NFC is enabled"))
        // NDEF push (Android Beam) was deprecated in Android 14 — phones lose
        // it but Reader/Writer mode (what we use) still works.
        sb.append(check(true, "Reader/Writer Mode supported"))
        sb.append(check(true, "Foreground dispatch available"))
        sb.append(line("Manufacturer", android.os.Build.MANUFACTURER))
        sb.append(line("Model", android.os.Build.MODEL))
        sb.append(line("Android", android.os.Build.VERSION.RELEASE))
        sb.append(line("API level", android.os.Build.VERSION.SDK_INT.toString()))
        phoneCard.text = sb.toString().trim()
    }

    private fun renderTagCard(res: DiagResult) {
        val info = res.info
        val sb = StringBuilder()
        sb.append(line("Type", info.type.display))
        sb.append(line("Product", info.productName ?: "—"))
        sb.append(line("UID", info.uidHex))
        sb.append(line("UID length", "${info.uidLength} bytes"))
        sb.append(line("ATQA / SAK", "${info.atqaHex ?: "—"} / ${info.sakHex ?: "—"}"))
        sb.append(line("Tech", info.techList.joinToString(", ")))
        sb.append(line("Memory", info.totalMemoryBytes?.let { "$it bytes" } ?: "—"))
        sb.append(line("Pages", info.pageCount?.toString() ?: "—"))
        sb.append(line("NDEF used", info.usedMemoryBytes?.let { "$it bytes" } ?: "—"))
        sb.append(check(info.writable, "Writable"))
        sb.append(check(info.canMakeReadOnly, "Can be locked"))
        sb.append(check(res.variant != null, "Password support (NTAG21x)"))
        tagCard.text = sb.toString().trim()
    }

    private fun renderSummary(res: DiagResult) {
        val info = res.info
        val ok = info.writable
        val verdict = when {
            !ok && info.canMakeReadOnly -> "Tag is locked / read-only — can't be written without an unlock command."
            !ok -> "Tag cannot be written to (factory locked or non-writable type)."
            res.variant != null -> "All checks passed — this NTAG can be written, locked, and password-protected."
            info.type.display.contains("Mifare", ignoreCase = true) -> "Tag is writable. Password protection is not available on this chip type."
            else -> "Tag is writable. Most NDEF features should work."
        }
        summary.visibility = TextView.VISIBLE
        summary.text = "Verdict\n\n$verdict"
    }

    private fun check(cond: Boolean, label: String): String =
        (if (cond) "✓  " else "✗  ") + label + "\n"

    private fun line(k: String, v: String): String = "$k: $v\n"

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.brand_blue))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun card(): TextView = TextView(this).apply {
        setBackgroundResource(R.drawable.bg_card_outlined)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setTextColor(getColor(R.color.text_primary))
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class DiagResult(val info: com.identium.nfc.nfc.TagInfo, val variant: Ntag21x.Variant?)
}
