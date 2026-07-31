package com.identium.nfc.ui

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.animation.AnimatorSet
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.R
import com.identium.nfc.util.ScanDialogAnimations
import com.identium.nfc.util.SuccessDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-contained NFC activity. Each screen that wants to drive its own
 * "tap a tag, run X" flow extends this — the dispatcher class lives here
 * once instead of being copy-pasted across activities.
 *
 * Each subclass calls [runOnNextTap] with a label, an IO block that does the
 * tag work, and a UI block that consumes the result. The base class shows
 * the scan dialog, captures the next tag the OS routes to us via foreground
 * dispatch, and runs the work off the main thread.
 *
 * NFC dispatch ownership matters: when this activity is foreground, we
 * register foreground dispatch so taps come straight to onNewIntent rather
 * than launching MainActivity from the manifest filter. That's the bug
 * fix for password / copy / import — they each own their own NFC now.
 */
abstract class BaseNfcActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private var pending: PendingTagAction<*>? = null
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null

    private val techFilters = arrayOf(
        IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
    )
    private val techLists = arrayOf(
        arrayOf("android.nfc.tech.IsoDep"),
        arrayOf("android.nfc.tech.NfcA"),
        arrayOf("android.nfc.tech.NfcB"),
        arrayOf("android.nfc.tech.NfcF"),
        arrayOf("android.nfc.tech.NfcV"),
        arrayOf("android.nfc.tech.Ndef"),
        arrayOf("android.nfc.tech.NdefFormatable"),
        arrayOf("android.nfc.tech.MifareClassic"),
        arrayOf("android.nfc.tech.MifareUltralight")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val launchIntent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    override fun onResume() {
        super.onResume()
        try {
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, techFilters, techLists)
        } catch (_: Exception) { /* ignored — adapter may be off, dialog informs user */ }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) { /* already disabled */ }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = extractTag(intent) ?: return
        val action = pending ?: return
        pending = null
        dismissScanDialog()
        @Suppress("UNCHECKED_CAST")
        runAction(tag, action as PendingTagAction<Any?>)
    }

    private fun <R> runAction(tag: Tag, action: PendingTagAction<R>) {
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { action.work(tag) }
            } catch (e: Exception) {
                SuccessDialog.showError(this@BaseNfcActivity, "Operation failed", e.message ?: e.javaClass.simpleName)
                return@launch
            }
            action.onResult(result)
        }
    }

    /**
     * Shows the scan dialog and waits for the next tag tap. [work] runs on
     * a background thread; [onResult] runs on the main thread with the value
     * [work] returned. Re-throwing in [work] dispatches to the error dialog.
     */
    protected fun <R> runOnNextTap(
        title: String,
        subtitle: String? = null,
        work: (Tag) -> R,
        onResult: (R) -> Unit
    ) {
        if (nfcAdapter == null) {
            SuccessDialog.showError(this, "NFC unavailable",
                "This device does not support NFC.")
            return
        }
        if (nfcAdapter?.isEnabled == false) {
            MaterialAlertDialogBuilder(this)
                .setTitle("NFC is off")
                .setMessage("Please enable NFC in system settings to continue.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        pending = PendingTagAction(work, onResult)
        showScanDialog(title, subtitle)
    }

    /**
     * Same as [runOnNextTap] but without the modal scan dialog. Used by the
     * bulk write screen, where the whole screen is already the prompt and a
     * dialog would have to be dismissed between every tag.
     */
    protected fun <R> runOnNextTapSilently(
        work: (Tag) -> R,
        onResult: (R) -> Unit
    ) {
        if (nfcAdapter == null) {
            SuccessDialog.showError(this, "NFC unavailable", "This device does not support NFC.")
            return
        }
        if (nfcAdapter?.isEnabled == false) {
            MaterialAlertDialogBuilder(this)
                .setTitle("NFC is off")
                .setMessage("Please enable NFC in system settings to continue.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        pending = PendingTagAction(work, onResult)
    }

    protected fun cancelPending() {
        pending = null
        dismissScanDialog()
    }

    private var scanAnimators: List<AnimatorSet> = emptyList()

    private fun showScanDialog(title: String, subtitle: String?) {
        scanDialog?.dismiss()
        ScanDialogAnimations.stop(scanAnimators)
        val view = layoutInflater.inflate(R.layout.dialog_scan, null, false)
        view.findViewById<TextView>(R.id.scan_title).text = title
        view.findViewById<TextView>(R.id.scan_subtitle).text = subtitle ?: "Hold the tag near the back of your phone."
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { cancelPending() }
        scanDialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .create()
            .also {
                it.setOnShowListener {
                    scanAnimators = ScanDialogAnimations.startOn(view)
                }
                it.show()
            }
    }

    protected fun dismissScanDialog() {
        ScanDialogAnimations.stop(scanAnimators)
        scanAnimators = emptyList()
        scanDialog?.dismiss()
        scanDialog = null
    }

    private fun extractTag(intent: Intent): Tag? {
        val action = intent.action ?: return null
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }

    private data class PendingTagAction<R>(
        val work: (Tag) -> R,
        val onResult: (R) -> Unit
    )
}
