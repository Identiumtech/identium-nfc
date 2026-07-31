package com.identium.nfc.ui

import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.NdefBuilder
import com.identium.nfc.nfc.TagOperations

/**
 * Bulk write + lock — production-line mode.
 *
 * Set a URL once, then tap tag after tag. Each tap writes the URL, locks the
 * tag permanently, flashes a 1-second confirmation and immediately re-arms
 * for the next tag. No dialogs, no confirmation taps between tags.
 *
 * Deliberately different from the Write tab: that flow shows a modal scan
 * dialog and a blocking success popup, which is fine for one tag but far too
 * slow when programming hundreds. Here the whole screen *is* the status
 * indicator and the NFC reader stays armed continuously.
 *
 * Locking is irreversible, so the feature is gated behind an explicit
 * "Start" confirmation and an always-visible warning banner.
 */
class BulkWriteLockActivity : BaseNfcActivity() {

    private lateinit var urlField: TextInputEditText
    private lateinit var urlLayout: TextInputLayout
    private lateinit var lockSwitch: MaterialSwitch
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusIcon: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var counterView: TextView
    private lateinit var startBtn: MaterialButton
    private lateinit var setupPanel: LinearLayout

    private var running = false
    private var okCount = 0
    private var failCount = 0
    private var baseUrl = ""

    private val handler = Handler(Looper.getMainLooper())
    private val resetStatusRunnable = Runnable { showArmedState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bulk write & lock"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        // ─── Setup panel (hidden once running) ───
        setupPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        setupPanel.addView(TextView(this).apply {
            text = "Write the same URL to tag after tag. Each tap writes, locks and " +
                    "re-arms instantly — no dialogs in between."
            setTextColor(getColor(R.color.text_secondary))
        }, lp().apply { bottomMargin = dp(14) })

        urlLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "URL to write to every tag"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            helperText = "Use {n} to insert an auto-incrementing number per tag."
        }
        urlField = TextInputEditText(urlLayout.context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setText("https://")
            setSelection(text?.length ?: 0)
        }
        urlLayout.addView(urlField)
        setupPanel.addView(urlLayout, lp())

        lockSwitch = MaterialSwitch(this).apply {
            text = "Lock each tag after writing (permanent)"
            isChecked = true
        }
        setupPanel.addView(lockSwitch, lp().apply { topMargin = dp(12) })

        setupPanel.addView(TextView(this).apply {
            text = "⚠  Locking is irreversible. A locked tag can never be rewritten. " +
                    "Test on one tag before running a batch."
            setTextColor(getColor(R.color.brand_red))
            textSize = 13f
            setBackgroundResource(R.drawable.bg_brand_chip_red)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, lp().apply { topMargin = dp(12) })

        startBtn = MaterialButton(this).apply {
            text = "Start bulk session"
            setIconResource(R.drawable.ic_write)
        }
        setupPanel.addView(startBtn, lp().apply { topMargin = dp(16) })
        startBtn.setOnClickListener { confirmStart() }

        root.addView(setupPanel, lp())

        // ─── Status panel (shown while running) ───
        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(20), dp(36), dp(20), dp(36))
            visibility = View.GONE
        }
        statusIcon = TextView(this).apply {
            textSize = 64f
            gravity = Gravity.CENTER
        }
        statusTitle = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        statusDetail = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(8), dp(6), dp(8), 0)
        }
        statusPanel.addView(statusIcon)
        statusPanel.addView(statusTitle)
        statusPanel.addView(statusDetail)
        root.addView(statusPanel, lp().apply { topMargin = dp(8) })

        counterView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(counterView, lp())

        val stopBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Finish session"
            visibility = View.GONE
            setOnClickListener { stopSession() }
        }
        root.addView(stopBtn, lp().apply { topMargin = dp(16) })
        this.stopButton = stopBtn

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    private var stopButton: MaterialButton? = null

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        handler.removeCallbacks(resetStatusRunnable)
        super.onDestroy()
    }

    // ── session control ──

    private fun confirmStart() {
        val url = urlField.text?.toString().orEmpty().trim()
        if (url.isBlank() || url == "https://" || url == "http://") {
            urlLayout.error = "Enter the URL to write"
            return
        }
        urlLayout.error = null
        baseUrl = normalise(url)

        val willLock = lockSwitch.isChecked
        MaterialAlertDialogBuilder(this)
            .setTitle(if (willLock) "Start write & lock session?" else "Start bulk write session?")
            .setMessage(
                "URL: $baseUrl\n\n" +
                (if (willLock)
                    "Every tag you tap will be written AND permanently locked. This cannot be undone."
                 else
                    "Every tag you tap will be written with this URL.") +
                "\n\nTap tags one after another — the app re-arms automatically."
            )
            .setPositiveButton(if (willLock) "Start & lock" else "Start") { _, _ -> startSession() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startSession() {
        running = true
        okCount = 0
        failCount = 0
        setupPanel.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        counterView.visibility = View.VISIBLE
        stopButton?.visibility = View.VISIBLE
        // Keep the screen on for the whole batch — the operator's hands are busy.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateCounter()
        armNextTag()
    }

    private fun stopSession() {
        running = false
        cancelPending()
        handler.removeCallbacks(resetStatusRunnable)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MaterialAlertDialogBuilder(this)
            .setTitle("Session finished")
            .setMessage("Wrote $okCount tag(s) successfully" +
                    (if (failCount > 0) ", $failCount failed." else "."))
            .setPositiveButton("Done") { _, _ -> finish() }
            .setNegativeButton("Back to setup") { _, _ ->
                setupPanel.visibility = View.VISIBLE
                statusPanel.visibility = View.GONE
                counterView.visibility = View.GONE
                stopButton?.visibility = View.GONE
            }
            .show()
    }

    /**
     * Arm the reader for the next tag. Uses BaseNfcActivity's runOnNextTap but
     * suppresses its modal scan dialog — in bulk mode the screen itself is the
     * prompt, and a dialog would need dismissing between every tag.
     */
    private fun armNextTag() {
        if (!running) return
        showArmedState()
        runOnNextTapSilently(
            work = { tag ->
                val uidHex = HexUtil.toHex(tag.id, ":")
                val url = if (Counter.isEnabled(this) && baseUrl.contains("{n}"))
                    baseUrl.replace("{n}", Counter.render(Counter.current(this), Counter.padding(this)))
                else baseUrl
                val msg = android.nfc.NdefMessage(arrayOf(NdefBuilder.url(url)))
                val res = TagOperations.writeNdef(tag, msg, makeReadOnly = lockSwitch.isChecked)
                Triple(uidHex, url, res)
            },
            onResult = { (uidHex, url, res) ->
                if (res.success) {
                    okCount++
                    if (Counter.isEnabled(this) && baseUrl.contains("{n}")) Counter.bumpAfterWrite(this)
                    buzz(true)
                    showSuccessFlash(url)
                } else {
                    failCount++
                    buzz(false)
                    showFailureFlash(res.message)
                }
                History.record(
                    this, History.Action.WRITE,
                    uid = uidHex,
                    tagType = if (lockSwitch.isChecked) "Bulk write+lock" else "Bulk write",
                    summary = url + (if (res.success) "" else " — ${res.message}"),
                    success = res.success
                )
                updateCounter()
                // Re-arm immediately so the next tag can be tapped right away.
                armNextTag()
            }
        )
    }

    // ── status rendering ──

    private fun showArmedState() {
        statusIcon.text = "📲"
        statusTitle.text = "Tap a tag"
        statusTitle.setTextColor(getColor(R.color.brand_blue))
        statusDetail.text = if (lockSwitch.isChecked)
            "Writes and locks instantly, then re-arms."
        else "Writes instantly, then re-arms."
        statusPanel.alpha = 1f
    }

    private fun showSuccessFlash(url: String) {
        statusIcon.text = "✓"
        statusTitle.text = "Written & locked"
        if (!lockSwitch.isChecked) statusTitle.text = "Written"
        statusTitle.setTextColor(getColor(R.color.success))
        statusDetail.text = url
        flash()
        // Confirmation disappears after 1s and the screen returns to "Tap a tag".
        handler.removeCallbacks(resetStatusRunnable)
        handler.postDelayed(resetStatusRunnable, 1000L)
    }

    private fun showFailureFlash(message: String) {
        statusIcon.text = "✗"
        statusTitle.text = "Failed"
        statusTitle.setTextColor(getColor(R.color.error))
        statusDetail.text = message
        flash()
        // Failures linger a little longer so the operator can read them.
        handler.removeCallbacks(resetStatusRunnable)
        handler.postDelayed(resetStatusRunnable, 2000L)
    }

    private fun flash() {
        ObjectAnimator.ofFloat(statusPanel, View.ALPHA, 0.35f, 1f).apply {
            duration = 220
            start()
        }
    }

    private fun updateCounter() {
        counterView.text = "✓ $okCount written" + if (failCount > 0) "   ·   ✗ $failCount failed" else ""
    }

    private fun buzz(success: Boolean) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (success)
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                else
                    VibrationEffect.createWaveform(longArrayOf(0, 80, 90, 80), -1)
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                if (success) vib.vibrate(60) else vib.vibrate(longArrayOf(0, 80, 90, 80), -1)
            }
        } catch (_: Exception) { /* no haptics on this device */ }
    }

    private fun normalise(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t
        else "https://$t"
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
