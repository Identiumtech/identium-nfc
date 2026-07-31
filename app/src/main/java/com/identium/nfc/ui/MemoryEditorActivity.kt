package com.identium.nfc.ui

import android.nfc.tech.NfcA
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.History
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.Ntag21x
import com.identium.nfc.util.SuccessDialog

/**
 * Raw user-memory hex editor for NTAG21x / Mifare Ultralight.
 *
 * Step 1: tap a tag → read every user page (4 … lastUserPage) into an
 * editable text area, one page per line as `PP: HHHHHHHH`.
 * Step 2: edit the hex.
 * Step 3: tap the tag again → only the pages whose 4 bytes changed are
 * written back.
 *
 * Scope is deliberately limited to user memory. Pages 0–3 (UID / lock /
 * capability container) and the config / PWD pages past user memory are
 * never touched, so the editor can't brick a tag or trip OTP lock bits.
 */
class MemoryEditorActivity : BaseNfcActivity() {

    private lateinit var dumpField: TextInputEditText
    private lateinit var statusView: TextView
    private lateinit var btnWrite: MaterialButton

    // Snapshot of the last read so we only write pages that actually changed.
    private var lastRead: LinkedHashMap<Int, ByteArray> = linkedMapOf()
    private var userPageRange: IntRange = 4..39

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Memory editor"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        root.addView(TextView(this).apply {
            text = "Power-user tool: read the raw user-memory pages, edit the hex, write back. " +
                    "Each line is one page: PP: HHHHHHHH (4 bytes). Only changed pages are written. " +
                    "UID, lock and config pages are protected and never touched."
            setTextColor(getColor(R.color.text_secondary))
        }, lp().apply { bottomMargin = dp(16) })

        val readBtn = MaterialButton(this).apply {
            text = "Tap tag to read memory"
            setIconResource(R.drawable.ic_read)
        }
        root.addView(readBtn, lp())
        readBtn.setOnClickListener { readMemory() }

        statusView = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(12), 0, dp(8))
            text = "No tag read yet."
        }
        root.addView(statusView, lp())

        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Page dump (PP: HHHHHHHH per line)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        dumpField = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = false
            setHorizontallyScrolling(false)
            minLines = 8
            textSize = 14f
        }
        til.addView(dumpField)
        root.addView(til, lp().apply { topMargin = dp(4) })

        btnWrite = MaterialButton(this).apply {
            text = "Tap tag to write changes"
            setIconResource(R.drawable.ic_write)
            isEnabled = false
        }
        root.addView(btnWrite, lp().apply { topMargin = dp(12) })
        btnWrite.setOnClickListener { confirmWrite() }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun readMemory() {
        runOnNextTap(
            title = "Tap tag to read memory",
            subtitle = "Reading user-memory pages…",
            work = { tag ->
                val nfcA = NfcA.get(tag) ?: throw IllegalStateException("Not an NfcA tag.")
                nfcA.connect()
                try {
                    val variant = Ntag21x.detect(nfcA) ?: Ntag21x.Variant.ULTRALIGHT
                    val start = 4
                    val end = variant.lastUserPage
                    val pages = linkedMapOf<Int, ByteArray>()
                    var p = start
                    while (p <= end) {
                        val resp = Ntag21x.readBlock(nfcA, p) // 16 bytes = 4 pages
                        for (i in 0 until 4) {
                            val pg = p + i
                            if (pg <= end && (i * 4 + 4) <= resp.size) {
                                pages[pg] = resp.copyOfRange(i * 4, i * 4 + 4)
                            }
                        }
                        p += 4
                    }
                    Pair(variant, pages)
                } finally {
                    runCatching { nfcA.close() }
                }
            },
            onResult = { (variant, pages) ->
                lastRead = LinkedHashMap(pages)
                userPageRange = 4..variant.lastUserPage
                dumpField.setText(pages.entries.joinToString("\n") { (pg, bytes) ->
                    "%02X: %s".format(pg, HexUtil.toHex(bytes))
                })
                statusView.text = "Read ${pages.size} user pages from ${variant.name} (pages " +
                        "0x%02X–0x%02X). Edit the hex, then tap to write.".format(4, variant.lastUserPage)
                btnWrite.isEnabled = true
            }
        )
    }

    private fun confirmWrite() {
        val parsed = parseDump() ?: return
        if (parsed.isEmpty()) {
            Toast.makeText(this, "Nothing to write", Toast.LENGTH_SHORT).show()
            return
        }
        val changed = parsed.filter { (pg, bytes) ->
            val orig = lastRead[pg]
            orig == null || !orig.contentEquals(bytes)
        }
        if (changed.isEmpty()) {
            Toast.makeText(this, "No changes detected vs the last read", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Write ${changed.size} page(s)?")
            .setMessage("Pages to write: " + changed.keys.joinToString(", ") { "0x%02X".format(it) } +
                    "\n\nTap the tag to apply. Writing wrong values to a tag can corrupt its data.")
            .setPositiveButton("Tap & write") { _, _ -> writeMemory(changed) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeMemory(changed: Map<Int, ByteArray>) {
        runOnNextTap(
            title = "Tap tag to write ${changed.size} page(s)",
            subtitle = "Hold still until writing completes.",
            work = { tag ->
                val nfcA = NfcA.get(tag) ?: throw IllegalStateException("Not an NfcA tag.")
                nfcA.connect()
                try {
                    var written = 0
                    val errors = mutableListOf<String>()
                    for ((pg, bytes) in changed.toSortedMap()) {
                        if (pg !in userPageRange) {
                            errors += "0x%02X out of range".format(pg); continue
                        }
                        try { Ntag21x.writePage(nfcA, pg, bytes); written++ }
                        catch (e: Exception) { errors += "0x%02X: ${e.message}".format(pg) }
                    }
                    Pair(written, errors)
                } finally {
                    runCatching { nfcA.close() }
                }
            },
            onResult = { (written, errors) ->
                History.record(
                    this, History.Action.WRITE,
                    uid = "", tagType = "Memory edit",
                    summary = "Wrote $written page(s)" + if (errors.isNotEmpty()) ", ${errors.size} failed" else "",
                    success = errors.isEmpty()
                )
                if (errors.isEmpty()) {
                    SuccessDialog.show(this, "Memory updated", "Wrote $written page(s) successfully.")
                } else {
                    SuccessDialog.showError(this, "Wrote $written, ${errors.size} failed",
                        errors.joinToString("\n"))
                }
            }
        )
    }

    /** Parse the editable dump into page→4-byte map, validating each line. */
    private fun parseDump(): Map<Int, ByteArray>? {
        val out = linkedMapOf<Int, ByteArray>()
        val lines = dumpField.text?.toString().orEmpty().lines()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) {
                error("Line ${i + 1}: expected  PP: HHHHHHHH"); return null
            }
            val page = parts[0].trim().toIntOrNull(16)
            if (page == null) { error("Line ${i + 1}: bad page number"); return null }
            val hex = parts[1].replace(" ", "").trim()
            if (hex.length != 8 || !hex.matches(Regex("[0-9A-Fa-f]{8}"))) {
                error("Line ${i + 1}: each page needs exactly 4 hex bytes (8 chars)"); return null
            }
            out[page] = HexUtil.fromHex(hex)
        }
        return out
    }

    private fun error(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
