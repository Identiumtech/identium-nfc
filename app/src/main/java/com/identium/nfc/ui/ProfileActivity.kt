package com.identium.nfc.ui

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.Profile

/**
 * One-time-fill business card editor. Stored values are picked up by
 * RecordEditorActivity (vCard / Email / Phone) and Quick Recipes so
 * users don't type the same details onto every tag.
 */
class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Your business card"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val card = Profile.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        val explanation = TextView(this).apply {
            text = "These details auto-fill every vCard, email, and phone-tag you create. " +
                    "Update once — every recipe and shortcut uses them."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(explanation, lp().apply { bottomMargin = dp(16) })

        val name = field("Full name", card.fullName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val company = field("Company", card.company, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val ttl = field("Title / role", card.title, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val phone = field("Phone", card.phone, InputType.TYPE_CLASS_PHONE)
        val email = field("Email", card.email, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val website = field("Website", card.website, InputType.TYPE_TEXT_VARIATION_URI)
        val address = field("Address", card.address, InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val note = field("Note", card.note, InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        listOf(name, company, ttl, phone, email, website, address, note).forEach { root.addView(it) }

        val saveBtn = MaterialButton(this).apply { text = "Save" }
        root.addView(saveBtn, lp().apply { topMargin = dp(20) })

        saveBtn.setOnClickListener {
            Profile.save(
                this,
                Profile.Card(
                    fullName = (name.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    company = (company.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    title = (ttl.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    phone = (phone.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    email = (email.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    website = (website.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    address = (address.editText as TextInputEditText).text?.toString().orEmpty().trim(),
                    note = (note.editText as TextInputEditText).text?.toString().orEmpty().trim()
                )
            )
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun field(hint: String, initial: String, inputType: Int): TextInputLayout {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(til.context).apply {
            this.inputType = inputType or InputType.TYPE_CLASS_TEXT
            setText(initial)
            if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) minLines = 2
        }
        til.addView(edit)
        til.layoutParams = lp().apply { topMargin = dp(8) }
        return til
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
