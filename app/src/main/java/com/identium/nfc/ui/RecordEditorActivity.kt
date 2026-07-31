package com.identium.nfc.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.Profile
import com.identium.nfc.nfc.WifiAuth
import com.identium.nfc.nfc.WifiEnc
import com.identium.nfc.nfc.WriteRecord

/**
 * Generic editor: builds a small form per record type and returns a
 * [WriteRecord] back to the caller via Activity result.
 *
 * The forms are constructed programmatically so the type-specific code
 * lives in one place rather than spread across a dozen XML layouts.
 */
class RecordEditorActivity : AppCompatActivity() {

    private lateinit var typeKey: String
    private lateinit var rootLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        typeKey = intent.getStringExtra(EXTRA_TYPE) ?: WriteRecord.TYPE_URL
        title = labelFor(typeKey)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroll = androidx.core.widget.NestedScrollView(this).apply { addView(rootLayout) }
        setContentView(scroll)

        when (typeKey) {
            WriteRecord.TYPE_URL -> buildUrlForm()
            WriteRecord.TYPE_TEXT -> buildTextForm()
            WriteRecord.TYPE_EMAIL -> buildEmailForm()
            WriteRecord.TYPE_PHONE -> buildPhoneForm()
            WriteRecord.TYPE_SMS -> buildSmsForm()
            WriteRecord.TYPE_GEO -> buildGeoForm()
            WriteRecord.TYPE_ADDR -> buildAddrForm()
            WriteRecord.TYPE_APP -> buildAppForm()
            WriteRecord.TYPE_VCARD -> buildVcardForm()
            WriteRecord.TYPE_WIFI -> buildWifiForm()
            WriteRecord.TYPE_BT -> buildBtForm()
            WriteRecord.TYPE_MIME -> buildMimeForm()
            else -> finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ---------- Form builders ----------

    private fun buildUrlForm() {
        // Pre-filled with just the scheme so customers replace it with their
        // own URL — we never bake an Identium URL into a customer payload.
        val url = textInput("URL", InputType.TYPE_TEXT_VARIATION_URI, "https://")
        addBuildButton {
            val v = trimmed(url)
            if (v.isEmpty()) error(url, "Required") else WriteRecord.Url(v)
        }
    }

    private fun buildTextForm() {
        val text = textInput("Text", InputType.TYPE_TEXT_FLAG_MULTI_LINE, "", multi = true)
        val lang = textInput("Language tag (e.g. en)", InputType.TYPE_CLASS_TEXT, "en")
        addBuildButton {
            val v = trimmed(text); val l = trimmed(lang).ifEmpty { "en" }
            if (v.isEmpty()) error(text, "Required") else WriteRecord.Text(v, l)
        }
    }

    private fun buildEmailForm() {
        val to = textInput("Recipient", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "")
        val subject = textInput("Subject (optional)", InputType.TYPE_CLASS_TEXT, "")
        val body = textInput("Body (optional)", InputType.TYPE_TEXT_FLAG_MULTI_LINE, "", multi = true)
        addBuildButton {
            val v = trimmed(to)
            if (v.isEmpty()) error(to, "Required")
            else WriteRecord.Email(v, trimmed(subject), trimmed(body))
        }
    }

    private fun buildPhoneForm() {
        val n = textInput("Phone number (with country code)", InputType.TYPE_CLASS_PHONE, "")
        addBuildButton {
            val v = trimmed(n)
            if (v.isEmpty()) error(n, "Required") else WriteRecord.Phone(v)
        }
    }

    private fun buildSmsForm() {
        val n = textInput("Recipient number", InputType.TYPE_CLASS_PHONE, "")
        val body = textInput("Message", InputType.TYPE_TEXT_FLAG_MULTI_LINE, "", multi = true)
        addBuildButton {
            val v = trimmed(n)
            if (v.isEmpty()) error(n, "Required") else WriteRecord.Sms(v, trimmed(body))
        }
    }

    private fun buildGeoForm() {
        val lat = textInput("Latitude (e.g. 52.5200)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, "")
        val lon = textInput("Longitude (e.g. 13.4050)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, "")
        val label = textInput("Label (optional)", InputType.TYPE_CLASS_TEXT, "")
        addBuildButton {
            try {
                val la = trimmed(lat).toDouble(); val lo = trimmed(lon).toDouble()
                val lbl = trimmed(label).ifEmpty { null }
                WriteRecord.Geo(la, lo, lbl)
            } catch (e: Exception) { error(lat, "Lat/Lon must be numeric") }
        }
    }

    private fun buildAddrForm() {
        val a = textInput("Address", InputType.TYPE_TEXT_FLAG_MULTI_LINE, "", multi = true)
        addBuildButton {
            val v = trimmed(a)
            if (v.isEmpty()) error(a, "Required") else WriteRecord.AddressEntry(v)
        }
    }

    private fun buildAppForm() {
        val pkg = textInput("Package name (e.g. com.example.app)", InputType.TYPE_CLASS_TEXT, "")
        addBuildButton {
            val v = trimmed(pkg)
            if (v.isEmpty()) error(pkg, "Required") else WriteRecord.App(v)
        }
    }

    private fun buildVcardForm() {
        // Pre-fill from the saved Profile so a sales rep can author a vCard
        // tag in two taps. Anything in the profile is editable here per-tag.
        // If the profile is empty, all fields start empty — no placeholder
        // names get accidentally written to a tag.
        val p = Profile.load(this)
        val name = textInput("Full name", InputType.TYPE_CLASS_TEXT, p.fullName)
        val org = textInput("Company", InputType.TYPE_CLASS_TEXT, p.company)
        val ttl = textInput("Title", InputType.TYPE_CLASS_TEXT, p.title)
        val phone = textInput("Phone", InputType.TYPE_CLASS_PHONE, p.phone)
        val email = textInput("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, p.email)
        val web = textInput("Website", InputType.TYPE_TEXT_VARIATION_URI, p.website)
        val addr = textInput("Address", InputType.TYPE_CLASS_TEXT, p.address)
        val note = textInput("Note", InputType.TYPE_TEXT_FLAG_MULTI_LINE, p.note, multi = true)

        addBuildButton {
            val v = trimmed(name)
            if (v.isEmpty()) error(name, "Required") else WriteRecord.Vcard(
                v, trimmed(org), trimmed(ttl), trimmed(phone), trimmed(email),
                trimmed(web), trimmed(addr), trimmed(note)
            )
        }
    }

    private fun buildWifiForm() {
        val ssid = textInput("SSID (network name)", InputType.TYPE_CLASS_TEXT, "")
        val pwd = textInput("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD, "")

        rootLayout.addView(label("Authentication"))
        val authSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@RecordEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                WifiAuth.values().map { it.label })
            setSelection(WifiAuth.values().indexOf(WifiAuth.WPA2_PSK))
        }
        rootLayout.addView(authSpinner)

        rootLayout.addView(label("Encryption"))
        val encSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(this@RecordEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                WifiEnc.values().map { it.label })
            setSelection(WifiEnc.values().indexOf(WifiEnc.AES))
        }
        rootLayout.addView(encSpinner)

        val hidden = androidx.appcompat.widget.AppCompatCheckBox(this).apply {
            text = "Hidden network"
        }
        val cbParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        rootLayout.addView(hidden, cbParams)

        addBuildButton {
            val s = trimmed(ssid)
            if (s.isEmpty()) error(ssid, "Required")
            else {
                val auth = WifiAuth.values()[authSpinner.selectedItemPosition]
                val enc = WifiEnc.values()[encSpinner.selectedItemPosition]
                WriteRecord.Wifi(s, trimmed(pwd), auth.name, enc.name, hidden.isChecked)
            }
        }
    }

    private fun buildBtForm() {
        val mac = textInput("MAC address (e.g. AA:BB:CC:DD:EE:FF)", InputType.TYPE_CLASS_TEXT, "")
        val name = textInput("Device name (optional)", InputType.TYPE_CLASS_TEXT, "")
        addBuildButton {
            val v = trimmed(mac)
            if (!v.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")))
                error(mac, "Invalid MAC")
            else WriteRecord.Bluetooth(v, trimmed(name).ifEmpty { null })
        }
    }

    private fun buildMimeForm() {
        val mime = textInput("MIME type", InputType.TYPE_CLASS_TEXT, "application/octet-stream")
        val payload = textInput("Payload (text/UTF-8)", InputType.TYPE_TEXT_FLAG_MULTI_LINE, "", multi = true)
        addBuildButton {
            val m = trimmed(mime)
            if (m.isEmpty()) error(mime, "Required")
            else WriteRecord.CustomMime(m, payload.text?.toString().orEmpty())
        }
    }

    // ---------- helpers ----------

    private fun label(text: String): View {
        val tv = androidx.appcompat.widget.AppCompatTextView(this)
        tv.text = text
        tv.setTextColor(getColor(R.color.text_secondary))
        tv.textSize = 12f
        tv.setPadding(0, dp(12), 0, dp(2))
        return tv
    }

    private fun textInput(hint: String, inputType: Int, initial: String = "", multi: Boolean = false): TextInputEditText {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        til.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        val edit = TextInputEditText(til.context)
        edit.inputType = if (multi)
            inputType or InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else inputType
        if (multi) edit.minLines = 3
        edit.setText(initial)
        til.addView(edit)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        rootLayout.addView(til, params)
        return edit
    }

    private fun addBuildButton(builder: () -> WriteRecord?) {
        val btn = MaterialButton(this).apply { text = "Save record" }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) }
        rootLayout.addView(btn, params)
        btn.setOnClickListener {
            val rec = builder() ?: return@setOnClickListener
            val data = Intent().apply { putExtra(EXTRA_RECORD, rec as java.io.Serializable) }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun error(view: View, msg: String): WriteRecord? {
        if (view is TextInputEditText) (view.parent.parent as? TextInputLayout)?.error = msg
        return null
    }

    private fun trimmed(t: TextInputEditText): String = (t.text?.toString() ?: "").trim()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_RECORD = "record"

        fun intent(ctx: Context, type: String) = Intent(ctx, RecordEditorActivity::class.java).apply {
            putExtra(EXTRA_TYPE, type)
        }

        private fun labelFor(type: String): String = when (type) {
            WriteRecord.TYPE_URL -> "URL / URI"
            WriteRecord.TYPE_TEXT -> "Plain text"
            WriteRecord.TYPE_EMAIL -> "Email"
            WriteRecord.TYPE_PHONE -> "Phone number"
            WriteRecord.TYPE_SMS -> "SMS"
            WriteRecord.TYPE_GEO -> "Geolocation"
            WriteRecord.TYPE_ADDR -> "Address"
            WriteRecord.TYPE_APP -> "Android Application"
            WriteRecord.TYPE_VCARD -> "Business card"
            WriteRecord.TYPE_WIFI -> "Wi-Fi network"
            WriteRecord.TYPE_BT -> "Bluetooth"
            WriteRecord.TYPE_MIME -> "Custom MIME / Data"
            else -> "New record"
        }
    }
}
