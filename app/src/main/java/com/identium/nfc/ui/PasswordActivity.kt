package com.identium.nfc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.History
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.util.SuccessDialog

class PasswordActivity : BaseNfcActivity() {

    private var mode: String = MODE_SET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SET
        title = if (mode == MODE_SET) "Set password" else "Remove password"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val explanation = TextView(this).apply {
            text = if (mode == MODE_SET)
                "Sets the 4-byte password (PWD) and PACK on NTAG213/215/216 chips. " +
                        "Once enabled, readers must authenticate before reading or writing memory " +
                        "from the protected page onwards."
            else "Authenticates with the current password and clears protection. " +
                    "After this the tag will be writable by any reader."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(explanation, lp().apply { bottomMargin = dp(16) })

        val pwdField = textField("Password (8 characters max)")
        root.addView(pwdField)
        val pwdEdit = pwdField.editText as TextInputEditText
        pwdEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pwdField.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE

        var protectFromPage = 0x04
        if (mode == MODE_SET) {
            val pageLabel = TextView(this).apply {
                text = "Protect from page: 0x04 (full user memory)"
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(16), 0, 0)
            }
            root.addView(pageLabel)
            val seek = SeekBar(this).apply {
                max = 0xE5
                progress = 0x04
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        protectFromPage = progress.coerceAtLeast(4)
                        pageLabel.text = "Protect from page: 0x" + "%02X".format(protectFromPage)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            root.addView(seek)
            val helper = TextView(this).apply {
                text = "Lower page = more memory protected. 0x04 protects the full user area."
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 12f
            }
            root.addView(helper)
        }

        val btn = MaterialButton(this).apply {
            text = if (mode == MODE_SET) "Set password" else "Remove password"
        }
        root.addView(btn, lp().apply { topMargin = dp(28) })

        btn.setOnClickListener {
            val pwd = (pwdEdit.text?.toString() ?: "").take(8)
            if (pwd.isEmpty()) {
                pwdField.error = "Required"; return@setOnClickListener
            }
            pwdField.error = null
            val title = if (mode == MODE_SET) "Tap tag to set password" else "Tap tag to remove password"
            val subtitle = if (mode == MODE_SET)
                "Hold the tag still — protection from page 0x" + "%02X".format(protectFromPage) +
                        " will be applied after authentication."
            else "Hold the tag still while we authenticate and clear PWD/PACK."

            runOnNextTap(
                title = title,
                subtitle = subtitle,
                work = { tag ->
                    if (mode == MODE_SET)
                        TagOperations.setPassword(tag, pwd, protectFromPage)
                    else
                        TagOperations.removePassword(tag, pwd)
                },
                onResult = { result -> showResult(result) }
            )
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    private fun showResult(result: TagOperations.WriteResult) {
        History.record(
            this,
            if (mode == MODE_SET) History.Action.PASSWORD_SET else History.Action.PASSWORD_CLEAR,
            uid = "", tagType = "",
            summary = result.message,
            success = result.success
        )
        if (result.success) {
            SuccessDialog.show(
                this,
                title = if (mode == MODE_SET) "Password set" else "Password removed",
                body = result.message
            )
        } else {
            SuccessDialog.showError(this,
                title = if (mode == MODE_SET) "Could not set password" else "Could not remove password",
                body = result.message)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun textField(hint: String): TextInputLayout {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        til.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        val edit = TextInputEditText(til.context)
        edit.inputType = InputType.TYPE_CLASS_TEXT
        til.addView(edit)
        til.layoutParams = lp()
        return til
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SET = "set"
        const val MODE_REMOVE = "remove"

        fun intent(ctx: Context, mode: String) = Intent(ctx, PasswordActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode)
        }
    }
}
