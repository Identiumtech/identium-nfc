package com.identium.nfc

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.identium.nfc.data.Counter
import com.identium.nfc.data.History
import com.identium.nfc.databinding.ActivityMainBinding
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.nfc.toNdef
import com.identium.nfc.ui.OnboardingActivity
import com.identium.nfc.ui.OtherFragment
import com.identium.nfc.ui.ReadFragment
import com.identium.nfc.ui.ScanDialogFragment
import com.identium.nfc.ui.SettingsActivity
import com.identium.nfc.ui.TasksFragment
import com.identium.nfc.ui.WriteFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the four primary fragments and owns the foreground dispatch for
 * every NFC tap that happens *inside* MainActivity.
 *
 * Sub-screens (PasswordActivity, CopyTagActivity, ImportExcelActivity)
 * extend BaseNfcActivity and own their own dispatch — when one of them is
 * foreground, our foreground dispatch is paused and theirs takes over.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: NfcViewModel by viewModels()

    private var nfcAdapter: NfcAdapter? = null
    private var pendingDispatch: PendingIntent? = null
    private var techFilters: Array<IntentFilter> = emptyArray()
    private var techLists: Array<Array<String>> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_IdentiumNfc)
        // Show the onboarding carousel on first launch — only when nothing
        // else is queued (i.e. the OS didn't open us with a tag intent).
        if (savedInstanceState == null && OnboardingActivity.shouldShow(this) && intent?.action == null) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.bannerNfcUnavailable.visibility = View.VISIBLE
            binding.bannerNfcUnavailable.text = getString(R.string.nfc_unsupported)
        } else if (!nfcAdapter!!.isEnabled) {
            binding.bannerNfcUnavailable.visibility = View.VISIBLE
            binding.bannerNfcUnavailable.text = getString(R.string.nfc_disabled)
            binding.bannerNfcUnavailable.setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
            }
        }

        val launchIntent = Intent(this, javaClass).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        pendingDispatch = PendingIntent.getActivity(this, 0, launchIntent, flags)

        techFilters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        )
        techLists = arrayOf(
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

        binding.bottomNav.setOnItemSelectedListener { item ->
            select(when (item.itemId) {
                R.id.tab_read -> ReadFragment()
                R.id.tab_write -> WriteFragment()
                R.id.tab_other -> OtherFragment()
                R.id.tab_tasks -> TasksFragment()
                else -> ReadFragment()
            })
            true
        }

        binding.btnSettingsTop.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (savedInstanceState == null) {
            val initialTab = intent.getIntExtra(EXTRA_OPEN_TAB, R.id.tab_read)
            select(when (initialTab) {
                R.id.tab_write -> WriteFragment()
                R.id.tab_other -> OtherFragment()
                R.id.tab_tasks -> TasksFragment()
                else -> ReadFragment()
            })
            binding.bottomNav.selectedItemId = initialTab
        }

        viewModel.pendingOperation.observe(this) { op ->
            if (op == null) dismissScanDialog() else showScanDialog(op)
        }

        handleNfcIntent(intent)
        handleRecipeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
        handleRecipeIntent(intent)
    }

    /**
     * Quick-recipe handoff. The QuickRecipesActivity packages its records
     * as a Serializable list extra; we read them, replace the write queue,
     * and snap to the Write tab so the user can review & tap.
     */
    private fun handleRecipeIntent(intent: Intent) {
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val records = intent.getSerializableExtra(EXTRA_LOAD_RECORDS) as? ArrayList<com.identium.nfc.nfc.WriteRecord>
            ?: return
        viewModel.clearWriteQueue()
        records.forEach { viewModel.appendWriteRecord(it) }
        binding.bottomNav.selectedItemId = R.id.tab_write
        intent.removeExtra(EXTRA_LOAD_RECORDS)
    }

    override fun onResume() {
        super.onResume()
        try {
            nfcAdapter?.enableForegroundDispatch(this, pendingDispatch, techFilters, techLists)
        } catch (_: Exception) { /* user toggled NFC off; banner already informs */ }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {}
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (tag == null) return

        val pending = viewModel.pendingOperation.value ?: PendingOperation.Read()
        runOperation(tag, pending)
    }

    private fun runOperation(tag: Tag, op: PendingOperation) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { dispatch(tag, op) }
            viewModel.clearPending()
            when (outcome) {
                is OpOutcome.TagInfoLoaded -> {
                    viewModel.publishTagInfo(outcome.info)
                    val firstRecord = outcome.info.ndefMessage?.records?.firstOrNull()
                    val summary = firstRecord?.let { describeRecord(it) } ?: ""
                    History.record(
                        this@MainActivity, History.Action.READ,
                        uid = outcome.info.uidHex,
                        tagType = outcome.info.type.display,
                        summary = summary,
                        success = true
                    )
                }
                is OpOutcome.WroteResult -> {
                    viewModel.publishResult(outcome.result)
                    val historyAction = when (op) {
                        is PendingOperation.Write -> History.Action.WRITE
                        is PendingOperation.Erase -> History.Action.ERASE
                        is PendingOperation.Format -> History.Action.FORMAT
                        is PendingOperation.MakeReadOnly -> History.Action.LOCK
                        else -> History.Action.WRITE
                    }
                    val summary = if (op is PendingOperation.Write)
                        op.records.joinToString(" + ") { it.title } +
                                if (outcome.result.success) " (${outcome.result.bytesWritten}B)" else ""
                    else outcome.result.message
                    History.record(
                        this@MainActivity, historyAction,
                        uid = "", tagType = "",
                        summary = summary,
                        success = outcome.result.success
                    )
                    // Bump auto-counter on a successful write so the next tag gets the next value.
                    if (outcome.result.success && op is PendingOperation.Write &&
                        Counter.isEnabled(this@MainActivity) &&
                        op.records.any { recordContainsCounterToken(it) }) {
                        Counter.bumpAfterWrite(this@MainActivity)
                    }
                }
            }
        }
    }

    private fun describeRecord(rec: android.nfc.NdefRecord): String = when (rec.tnf) {
        android.nfc.NdefRecord.TNF_WELL_KNOWN -> when {
            rec.type.contentEquals(android.nfc.NdefRecord.RTD_URI) -> rec.toUri()?.toString() ?: ""
            rec.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT) -> {
                val payload = rec.payload
                if (payload.isEmpty()) "" else {
                    val langLen = payload[0].toInt() and 0x3F
                    String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                }
            }
            else -> ""
        }
        android.nfc.NdefRecord.TNF_MIME_MEDIA -> String(rec.type, Charsets.US_ASCII)
        else -> ""
    }

    private fun recordContainsCounterToken(r: com.identium.nfc.nfc.WriteRecord): Boolean {
        val token = "{n}"
        return when (r) {
            is com.identium.nfc.nfc.WriteRecord.Url -> r.url.contains(token)
            is com.identium.nfc.nfc.WriteRecord.Text -> r.text.contains(token)
            is com.identium.nfc.nfc.WriteRecord.CustomMime -> r.payloadAscii.contains(token)
            else -> false
        }
    }

    /**
     * Dispatch only handles the operations the four hosted fragments can
     * queue: read, write (with optional lock), erase, format, and "make
     * read-only". Password / copy / import live in their own activities.
     */
    private fun dispatch(tag: Tag, op: PendingOperation): OpOutcome = when (op) {
        is PendingOperation.Read -> OpOutcome.TagInfoLoaded(TagOperations.read(tag))
        is PendingOperation.Write -> {
            // Apply the auto-counter to {n} placeholders before serializing.
            val recordsForWrite = if (Counter.isEnabled(this))
                Counter.applyTo(op.records, Counter.current(this), Counter.padding(this))
            else op.records
            val msg = android.nfc.NdefMessage(recordsForWrite.map { it.toNdef() }.toTypedArray())
            OpOutcome.WroteResult(TagOperations.writeNdef(tag, msg, op.lockAfter))
        }
        is PendingOperation.Erase -> OpOutcome.WroteResult(TagOperations.erase(tag))
        is PendingOperation.Format -> OpOutcome.WroteResult(TagOperations.format(tag))
        is PendingOperation.MakeReadOnly -> OpOutcome.WroteResult(TagOperations.makeReadOnly(tag))

        // The remaining cases are unreachable from MainActivity's fragments —
        // they're only queued by the self-contained sub-Activities. We keep
        // exhaustive when to surface compile errors if a fragment ever does
        // queue one of these incorrectly.
        is PendingOperation.SetPassword,
        is PendingOperation.RemovePassword,
        is PendingOperation.CopyTagCapture,
        is PendingOperation.CopyTagApply,
        is PendingOperation.WriteSequential ->
            OpOutcome.WroteResult(TagOperations.WriteResult.error(
                "This operation is handled in its own screen — open it from the Other tab."
            ))
    }

    private fun showScanDialog(op: PendingOperation) {
        val existing = supportFragmentManager.findFragmentByTag(ScanDialogFragment.TAG)
        if (existing == null) {
            ScanDialogFragment.forOperation(op).show(supportFragmentManager, ScanDialogFragment.TAG)
        }
    }

    private fun dismissScanDialog() {
        (supportFragmentManager.findFragmentByTag(ScanDialogFragment.TAG) as? ScanDialogFragment)
            ?.dismissAllowingStateLoss()
    }

    private fun select(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitNowAllowingStateLoss()
    }

    fun selectTab(menuId: Int) {
        binding.bottomNav.selectedItemId = menuId
    }

    private sealed class OpOutcome {
        data class TagInfoLoaded(val info: com.identium.nfc.nfc.TagInfo) : OpOutcome()
        data class WroteResult(val result: TagOperations.WriteResult) : OpOutcome()
    }

    companion object {
        const val EXTRA_LOAD_RECORDS = "load_records"
        const val EXTRA_OPEN_TAB = "open_tab"
    }
}
