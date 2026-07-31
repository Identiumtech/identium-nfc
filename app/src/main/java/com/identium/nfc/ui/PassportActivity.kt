package com.identium.nfc.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.Counter
import com.identium.nfc.data.History
import com.identium.nfc.data.Passports
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.NdefBuilder
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.util.SuccessDialog
import java.util.UUID

/**
 * Digital Passport / Authenticity verification screen.
 *
 * Two flows in one screen:
 *
 *  1. **Issue passport** — write a tag that carries the customer's verification
 *     URL with a product ID and the tag's hardware UID baked in. The URL is
 *     opened automatically when any phone taps the tag.
 *
 *  2. **Verify passport** — read a tag and check whether its URL was issued by
 *     one of the customer's saved identities. If yes, show the product ID and
 *     UID and offer to open the verification URL in the browser. (The
 *     authenticity decision itself happens on the customer's backend — the
 *     app just routes the data there.)
 */
class PassportActivity : BaseNfcActivity() {

    private lateinit var identityChip: TextView
    private lateinit var identityUrl: TextView
    private lateinit var productIdField: TextInputEditText
    private lateinit var previewView: TextView
    private lateinit var btnIssue: MaterialButton
    private lateinit var btnVerify: MaterialButton

    private var activeIdentity: Passports.Identity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Digital Passport"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        val intro = TextView(this).apply {
            text = "Turn any NFC tag into a tap-to-verify passport for your products. " +
                    "Each tag carries your verification URL with a unique product ID and " +
                    "the chip's hardware UID — your backend confirms authenticity when a " +
                    "customer taps the tag."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(intro, lp().apply { bottomMargin = dp(16) })

        // ─── Identity card ───
        root.addView(section("Issuer identity"))
        val identityBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        identityChip = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }
        identityUrl = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
            setTextIsSelectable(true)
        }
        identityBox.addView(identityChip)
        identityBox.addView(identityUrl)

        val identityActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        identityActions.addView(textButton("Switch") { showIdentityPicker() })
        identityActions.addView(textButton("Add new") { showAddIdentityDialog() })
        identityActions.addView(textButton("Manage") { showManageDialog() })
        identityBox.addView(identityActions)
        root.addView(identityBox, lp())

        // ─── Issue passport ───
        root.addView(section("Issue passport").withTopMargin(dp(24)))

        val pidTil = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Product ID / SKU / serial"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            helperText = "Use {n} to insert the auto-counter value on each write."
        }
        productIdField = TextInputEditText(pidTil.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(simpleWatcher { updatePreview() })
        }
        pidTil.addView(productIdField)
        root.addView(pidTil, lp().apply { topMargin = dp(8) })

        val pidRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        pidRow.addView(textButton("⟳ Generate UUID") {
            productIdField.setText(UUID.randomUUID().toString().take(8).uppercase())
        })
        pidRow.addView(textButton("Use {n} counter") {
            val cur = productIdField.text?.toString().orEmpty()
            productIdField.setText(if (cur.contains("{n}")) cur else (cur.ifBlank { "ITEM-" } + "{n}"))
            productIdField.setSelection(productIdField.text?.length ?: 0)
        })
        root.addView(pidRow, lp())

        previewView = TextView(this).apply {
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.brand_blue))
            setTextIsSelectable(true)
        }
        root.addView(previewView, lp().apply { topMargin = dp(12) })

        btnIssue = MaterialButton(this).apply {
            text = "Tap tag to issue passport"
            setIconResource(R.drawable.ic_write)
        }
        root.addView(btnIssue, lp().apply { topMargin = dp(12) })
        btnIssue.setOnClickListener { issuePassport() }

        // ─── Verify passport ───
        root.addView(section("Verify a passport").withTopMargin(dp(24)))
        val verifyHint = TextView(this).apply {
            text = "Tap any tag — we'll check whether the URL on it was issued by one of " +
                    "your saved identities, show the product ID and UID, and offer to open " +
                    "the verification page in your browser."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        }
        root.addView(verifyHint, lp().apply { topMargin = dp(4) })

        btnVerify = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Tap tag to verify"
            setIconResource(R.drawable.ic_read)
        }
        root.addView(btnVerify, lp().apply { topMargin = dp(10) })
        btnVerify.setOnClickListener { verifyPassport() }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })

        renderIdentity()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ---- identity management ----

    private fun renderIdentity() {
        activeIdentity = Passports.active(this)
        val id = activeIdentity
        if (id == null) {
            identityChip.text = "No identity set up yet"
            identityUrl.text = "Tap “Add new” to register your verification URL."
            btnIssue.isEnabled = false
        } else {
            identityChip.text = id.name.ifBlank { id.baseUrl }
            identityUrl.text = id.baseUrl
            btnIssue.isEnabled = id.baseUrl.isNotBlank()
        }
        updatePreview()
    }

    private fun showAddIdentityDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameTil = outlinedField("Identity name (e.g. Acme Brand)")
        val urlTil = outlinedField("Verification URL (e.g. https://acme.com/verify)").apply {
            (editText as TextInputEditText).inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val pathSwitch = MaterialSwitch(this).apply {
            text = "Append product ID as URL path (default is query string)"
            isChecked = false
        }
        val uidSwitch = MaterialSwitch(this).apply {
            text = "Include tag hardware UID in the URL (recommended)"
            isChecked = true
        }
        container.addView(nameTil)
        container.addView(urlTil, lp().apply { topMargin = dp(8) })
        container.addView(pathSwitch, lp().apply { topMargin = dp(12) })
        container.addView(uidSwitch, lp().apply { topMargin = dp(4) })

        MaterialAlertDialogBuilder(this)
            .setTitle("Add identity")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = (nameTil.editText?.text?.toString() ?: "").trim()
                val url = (urlTil.editText?.text?.toString() ?: "").trim()
                if (url.isBlank()) {
                    Toast.makeText(this, "Verification URL is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val id = Passports.newIdentity(
                    name = name.ifBlank { "Identity" },
                    baseUrl = normaliseUrl(url),
                    join = if (pathSwitch.isChecked) Passports.IdJoin.PATH else Passports.IdJoin.QUERY,
                    includeUid = uidSwitch.isChecked
                )
                Passports.save(this, id)
                Passports.setActiveId(this, id.id)
                renderIdentity()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIdentityPicker() {
        val all = Passports.list(this)
        if (all.isEmpty()) { showAddIdentityDialog(); return }
        val labels = all.map { "${it.name}\n${it.baseUrl}" }.toTypedArray()
        val currentIdx = all.indexOfFirst { it.id == Passports.activeId(this) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Active identity")
            .setSingleChoiceItems(labels, currentIdx) { d, which ->
                Passports.setActiveId(this, all[which].id)
                renderIdentity()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManageDialog() {
        val all = Passports.list(this)
        if (all.isEmpty()) { showAddIdentityDialog(); return }
        val labels = all.map { "${it.name} — ${it.baseUrl}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Manage identities")
            .setItems(labels) { _, which ->
                val target = all[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(target.name)
                    .setMessage(target.baseUrl + "\n\nJoin: " + target.join.name +
                            (if (target.includeUid) " · UID included" else ""))
                    .setNeutralButton("Delete") { _, _ ->
                        Passports.delete(this, target.id); renderIdentity()
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- create / verify ----

    private fun updatePreview() {
        val id = activeIdentity ?: run {
            previewView.text = "Add an identity to see a URL preview."
            return
        }
        val pid = productIdField.text?.toString().orEmpty()
        val applied = if (Counter.isEnabled(this) && pid.contains("{n}"))
            pid.replace("{n}", Counter.render(Counter.current(this), Counter.padding(this)))
        else pid
        previewView.text = "Will write:\n" + id.buildUrl(applied, "<TAG-UID>")
    }

    private fun issuePassport() {
        val id = activeIdentity ?: run {
            Toast.makeText(this, "Add an identity first", Toast.LENGTH_SHORT).show(); return
        }
        val rawPid = productIdField.text?.toString().orEmpty()
        runOnNextTap(
            title = "Tap tag to issue passport",
            subtitle = "We'll read the chip's UID, build your verification URL and write it.",
            work = { tag ->
                val uidHex = HexUtil.toHex(tag.id, separator = "")
                val pid = if (Counter.isEnabled(this) && rawPid.contains("{n}"))
                    rawPid.replace("{n}", Counter.render(Counter.current(this), Counter.padding(this)))
                else rawPid
                val finalUrl = id.buildUrl(pid, uidHex)
                val msg = android.nfc.NdefMessage(arrayOf(NdefBuilder.url(finalUrl)))
                val res = TagOperations.writeNdef(tag, msg, makeReadOnly = false)
                Triple(uidHex, finalUrl, res)
            },
            onResult = { (uidHex, url, res) ->
                History.record(
                    this, History.Action.WRITE,
                    uid = HexUtil.toHex(uidHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), separator = ":"),
                    tagType = "Passport",
                    summary = "Issued by ${id.name} → $url",
                    success = res.success
                )
                if (res.success) {
                    // Bump auto-counter when {n} was applied.
                    if (Counter.isEnabled(this) && rawPid.contains("{n}")) Counter.bumpAfterWrite(this)
                    SuccessDialog.show(this, "Passport issued",
                        "Tag UID: $uidHex\nURL: $url\n\nAnyone tapping this tag will open your verification page.")
                } else {
                    SuccessDialog.showError(this, "Could not write tag", res.message)
                }
            }
        )
    }

    private fun verifyPassport() {
        runOnNextTap(
            title = "Tap tag to verify",
            subtitle = "Reading the URL and checking it against your saved identities.",
            work = { tag ->
                val info = TagOperations.read(tag)
                val firstUrl = info.ndefMessage?.records?.firstOrNull { rec ->
                    rec.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                            rec.type.contentEquals(android.nfc.NdefRecord.RTD_URI)
                }?.toUri()?.toString()
                val match = firstUrl?.let { Passports.matchUrl(this, it) }
                VerifyResult(info.uidHex, firstUrl, match)
            },
            onResult = { r -> showVerifyResult(r) }
        )
    }

    private fun showVerifyResult(r: VerifyResult) {
        val titleText: String
        val body = StringBuilder()
        when {
            r.url == null -> {
                titleText = "No URL on tag"
                body.append("Tag UID: ${r.uid}\n\n")
                body.append("This tag has no URL record. Passports always carry a URL — this looks like a different kind of tag.")
            }
            r.matchedIdentity != null -> {
                titleText = "✓ Passport recognised"
                body.append("Issued by: ${r.matchedIdentity.name}\n")
                body.append("Tag UID: ${r.uid}\n")
                body.append("Carried URL:\n${r.url}\n\n")
                body.append("Your backend will perform the actual authenticity check when you open the URL.")
            }
            else -> {
                titleText = "⚠ Unknown source"
                body.append("Tag UID: ${r.uid}\n")
                body.append("URL on tag:\n${r.url}\n\n")
                body.append("This URL doesn't match any of your saved identities. The tag may have been issued elsewhere, or by a different brand.")
            }
        }
        History.record(
            this, History.Action.VERIFY,
            uid = r.uid, tagType = "Passport",
            summary = if (r.matchedIdentity != null) "Matched ${r.matchedIdentity.name}"
                      else if (r.url != null) "Unknown source: ${r.url.take(60)}"
                      else "No URL",
            success = r.matchedIdentity != null
        )
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(titleText)
            .setMessage(body.toString())
            .setPositiveButton(android.R.string.ok, null)
        if (r.url != null) {
            builder.setNeutralButton("Open URL in browser") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open URL: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        builder.show()
    }

    private data class VerifyResult(
        val uid: String,
        val url: String?,
        val matchedIdentity: Passports.Identity?
    )

    // ---- helpers ----

    private fun normaliseUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))
            trimmed
        else "https://$trimmed"
    }

    private fun outlinedField(hint: String): TextInputLayout {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        til.addView(edit)
        return til
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(getColor(R.color.brand_blue))
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun TextView.withTopMargin(px: Int): TextView {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = px }
        return this
    }

    private fun textButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            setTextColor(getColor(R.color.brand_blue))
            setOnClickListener { onClick() }
        }

    private fun simpleWatcher(after: () -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { after() }
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
