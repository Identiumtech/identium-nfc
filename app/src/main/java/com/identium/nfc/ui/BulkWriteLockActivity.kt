package com.identium.nfc.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.R
import com.identium.nfc.data.BulkLog
import com.identium.nfc.data.Counter
import com.identium.nfc.data.History
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.NdefBuilder
import com.identium.nfc.nfc.TagOperations
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bulk write + lock — production-line mode.
 *
 * Set a URL once, then tap tag after tag. Each tap writes the URL, optionally
 * locks the tag permanently, flashes a 1-second confirmation and immediately
 * re-arms for the next tag. No dialogs, no confirmation taps between tags.
 *
 * Every result is appended to a persistent [BulkLog] and shown in a live
 * table with the newest row on top, so the operator can audit the run and
 * pick it back up after the app is closed.
 *
 * The layout is deliberately NOT wrapped in a NestedScrollView: the results
 * table is a RecyclerView that must own its own scrolling (and recycle rows)
 * while the status panel and counters stay pinned above it.
 */
class BulkWriteLockActivity : BaseNfcActivity() {

    // setup
    private lateinit var setupScroll: View
    private lateinit var urlField: TextInputEditText
    private lateinit var urlLayout: TextInputLayout
    private lateinit var lockSwitch: MaterialSwitch

    // running
    private lateinit var runPanel: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusIcon: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView

    // counters
    private lateinit var statTotal: TextView
    private lateinit var statOk: TextView
    private lateinit var statFail: TextView
    private lateinit var sessionLine: TextView

    // table
    private lateinit var tableSection: LinearLayout
    private lateinit var tableTitle: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyTable: TextView
    private lateinit var adapter: BulkAdapter

    // buttons
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton

    private var running = false
    private var sessionOk = 0
    private var sessionFail = 0
    private var baseUrl = ""

    private val handler = Handler(Looper.getMainLooper())
    private val resetStatusRunnable = Runnable { showArmedState() }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bulk write & lock"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = true
        }

        // The app theme is NoActionBar, so without an explicit toolbar there is
        // no title, no up arrow and — critically — no overflow menu, which would
        // leave Export CSV / Clear log unreachable.
        root.addView(buildToolbar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Setup is a NestedScrollView with a weight so it scrolls internally on
        // short screens instead of clipping; the run panel wraps its (compact,
        // fixed) content; the table takes the remaining height. The table is
        // always on screen — during setup it shows past runs, during a session
        // it fills with live rows.
        root.addView(buildSetupPanel(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
        root.addView(buildRunPanel(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(buildTableSection(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildButtonBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        loadLog()
    }

    // ── view construction ──

    private fun buildToolbar(): View {
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            setBackgroundResource(R.drawable.bg_brand_header)
            title = "Bulk write & lock"
            setTitleTextColor(getColor(R.color.white))
            navigationIcon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                ?.also { it.setTint(getColor(R.color.white)) }
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            overflowIcon?.setTint(getColor(R.color.white))
        }
        setSupportActionBar(toolbar)
        // Re-tint after the menu inflates — the overflow drawable is created lazily.
        toolbar.post { toolbar.overflowIcon?.setTint(getColor(R.color.white)) }
        return toolbar
    }

    private fun buildSetupPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
        }

        panel.addView(TextView(this).apply {
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
        panel.addView(urlLayout, lp())

        lockSwitch = MaterialSwitch(this).apply {
            text = "Lock each tag after writing (permanent)"
            isChecked = true
        }
        panel.addView(lockSwitch, lp().apply { topMargin = dp(12) })

        panel.addView(TextView(this).apply {
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
            setOnClickListener { confirmStart() }
        }
        panel.addView(startBtn, lp().apply { topMargin = dp(16) })

        setupScroll = androidx.core.widget.NestedScrollView(this).apply { addView(panel) }
        return setupScroll
    }

    private fun buildRunPanel(): View {
        runPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            visibility = View.GONE
        }

        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        statusIcon = TextView(this).apply {
            textSize = 46f
            gravity = Gravity.CENTER
        }
        statusTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        statusDetail = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        statusPanel.addView(statusIcon)
        statusPanel.addView(statusTitle)
        statusPanel.addView(statusDetail)
        runPanel.addView(statusPanel, lp())

        // Stat cards: TOTAL / WRITTEN / FAILED
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statTotal = statValue(getColor(R.color.brand_blue))
        statOk = statValue(getColor(R.color.success))
        statFail = statValue(getColor(R.color.error))
        stats.addView(statCard(statTotal, "TOTAL"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(statCard(statOk, "WRITTEN"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) })
        stats.addView(statCard(statFail, "FAILED"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) })
        runPanel.addView(stats, lp().apply { topMargin = dp(12) })

        sessionLine = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        }
        runPanel.addView(sessionLine, lp())

        return runPanel
    }

    private fun buildTableSection(): View {
        tableSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        tableTitle = TextView(this).apply {
            text = "RESULTS"
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.brand_blue))
            setPadding(0, 0, 0, dp(6))
        }
        tableSection.addView(tableTitle, lp())

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.bg_brand_chip_blue)
        }
        headerRow.addView(headerCell("#", 42))
        headerRow.addView(headerCell("TAG UID / DETAILS", 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(6) }
        })
        headerRow.addView(headerCell("STATUS", -2))
        tableSection.addView(headerRow, lp())

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BulkWriteLockActivity)
            setHasFixedSize(true)
            // Keep the newest row visible when it's inserted at the top.
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
        }
        adapter = BulkAdapter()
        recycler.adapter = adapter
        tableSection.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyTable = TextView(this).apply {
            text = "No tags written yet.\nStart a session and tap your first tag."
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(dp(12), dp(28), dp(12), dp(28))
        }
        tableSection.addView(emptyTable, lp())

        return tableSection
    }

    private fun buildButtonBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        stopBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Finish session"
            visibility = View.GONE
            setOnClickListener { stopSession() }
        }
        bar.addView(stopBtn, lp())
        return bar
    }

    private fun statValue(color: Int) = TextView(this).apply {
        textSize = 26f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color)
        text = "0"
    }

    private fun statCard(value: TextView, label: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        card.addView(value)
        card.addView(TextView(this).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_secondary))
        })
        return card
    }

    private fun headerCell(text: String, widthDp: Int) = TextView(this).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.brand_blue))
        if (widthDp > 0) {
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // ── menu ──

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EXPORT, 0, "Export CSV")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_CLEAR, 1, "Clear log")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_EXPORT -> { exportCsv(); true }
        MENU_CLEAR -> { confirmClear(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun exportCsv() {
        val entries = BulkLog.load(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show(); return
        }
        val csv = BulkLog.toCsv(entries)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Identium NFC — bulk write log (${entries.size} tags)")
            putExtra(Intent.EXTRA_TEXT, csv)
        }, "Export bulk log"))
    }

    private fun confirmClear() {
        val entries = BulkLog.load(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Log is already empty", Toast.LENGTH_SHORT).show(); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear the log?")
            .setMessage("Removes all ${entries.size} recorded row(s). Tag numbering continues " +
                    "from where it left off. This doesn't affect the tags themselves.")
            .setPositiveButton("Clear") { _, _ ->
                BulkLog.clear(this)
                loadLog()
            }
            .setNeutralButton("Clear & reset numbering") { _, _ ->
                BulkLog.resetAll(this)
                loadLog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── data ──

    private fun loadLog() {
        val entries = BulkLog.load(this)
        adapter.replaceAll(entries)
        refreshCounters()
    }

    private fun refreshCounters() {
        val (total, ok, fail) = BulkLog.counts(this)
        statTotal.text = total.toString()
        statOk.text = ok.toString()
        statFail.text = fail.toString()
        sessionLine.text = if (running)
            "This session: $sessionOk written" + (if (sessionFail > 0) " · $sessionFail failed" else "")
        else "Showing all-time log"
        // The table is always on screen — only its contents swap between the
        // empty-state hint and the rows.
        val hasRows = adapter.itemCount > 0
        emptyTable.visibility = if (hasRows) View.GONE else View.VISIBLE
        recycler.visibility = if (hasRows) View.VISIBLE else View.GONE
        tableTitle.text = if (hasRows) "RESULTS  ($total)" else "RESULTS"
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
        sessionOk = 0
        sessionFail = 0
        setupScroll.visibility = View.GONE
        runPanel.visibility = View.VISIBLE
        stopBtn.visibility = View.VISIBLE
        // Keep the screen on for the whole batch — the operator's hands are busy.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshCounters()
        armNextTag()
    }

    private fun stopSession() {
        running = false
        cancelPending()
        handler.removeCallbacks(resetStatusRunnable)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshCounters()
        MaterialAlertDialogBuilder(this)
            .setTitle("Session finished")
            .setMessage("Wrote $sessionOk tag(s) this session" +
                    (if (sessionFail > 0) ", $sessionFail failed." else ".") +
                    "\n\nThe full log stays saved on this device.")
            .setPositiveButton("Back to setup") { _, _ ->
                setupScroll.visibility = View.VISIBLE
                runPanel.visibility = View.GONE
                stopBtn.visibility = View.GONE
                refreshCounters()
            }
            .setNegativeButton("Close screen") { _, _ -> finish() }
            .show()
    }

    /**
     * Arm the reader for the next tag. Uses the silent variant so no modal
     * dialog appears between tags — the status panel is the prompt.
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
                val locked = lockSwitch.isChecked
                if (res.success) {
                    sessionOk++
                    if (Counter.isEnabled(this) && baseUrl.contains("{n}")) Counter.bumpAfterWrite(this)
                    buzz(true)
                    showSuccessFlash(url)
                } else {
                    sessionFail++
                    buzz(false)
                    showFailureFlash(res.message)
                }

                // Persist + push the new row to the top of the table.
                val entry = BulkLog.append(
                    ctx = this,
                    uid = uidHex,
                    url = url,
                    locked = locked && res.success,
                    success = res.success,
                    error = if (res.success) "" else res.message
                )
                adapter.insertAtTop(entry)
                recycler.scrollToPosition(0)
                refreshCounters()

                History.record(
                    this, History.Action.WRITE,
                    uid = uidHex,
                    tagType = if (locked) "Bulk write+lock" else "Bulk write",
                    summary = url + (if (res.success) "" else " — ${res.message}"),
                    success = res.success
                )

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
        statusTitle.text = if (lockSwitch.isChecked) "Written & locked" else "Written"
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        handler.removeCallbacks(resetStatusRunnable)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── table adapter ──

    private inner class BulkAdapter : RecyclerView.Adapter<BulkAdapter.VH>() {
        private val items = mutableListOf<BulkLog.Entry>()

        fun replaceAll(newItems: List<BulkLog.Entry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        /** Newest row goes to index 0 so the latest tag is always on top. */
        fun insertAtTop(entry: BulkLog.Entry) {
            items.add(0, entry)
            notifyItemInserted(0)
            // Mirror BulkLog's cap so the two stay in sync.
            if (items.size > BulkLog.MAX) {
                val removed = items.size - BulkLog.MAX
                repeat(removed) { items.removeAt(items.size - 1) }
                notifyItemRangeRemoved(BulkLog.MAX, removed)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bulk_row, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.seq.text = "#${e.seq}"
            holder.uid.text = e.uid.ifBlank { "(unknown UID)" }

            val time = timeFmt.format(Date(e.timestamp))
            holder.detail.text = if (e.success) "$time · ${e.url}"
                                 else "$time · ${e.error}"

            when {
                !e.success -> {
                    holder.status.text = "✗ FAILED"
                    holder.status.setBackgroundResource(R.drawable.bg_badge_error)
                    holder.status.setTextColor(getColor(R.color.error))
                }
                e.duplicate -> {
                    // Same UID written earlier — flag it so a double-tap or a
                    // re-used tag doesn't go unnoticed in a long run.
                    holder.status.text = if (e.locked) "✓ DUP·LOCK" else "✓ DUPLICATE"
                    holder.status.setBackgroundResource(R.drawable.bg_badge_warn)
                    holder.status.setTextColor(getColor(R.color.warning))
                }
                e.locked -> {
                    holder.status.text = "✓ LOCKED"
                    holder.status.setBackgroundResource(R.drawable.bg_badge_success)
                    holder.status.setTextColor(getColor(R.color.success))
                }
                else -> {
                    holder.status.text = "✓ WRITTEN"
                    holder.status.setBackgroundResource(R.drawable.bg_badge_success)
                    holder.status.setTextColor(getColor(R.color.success))
                }
            }

            holder.itemView.setOnClickListener { showRowDetail(e) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val seq: TextView = v.findViewById(R.id.bulk_seq)
            val uid: TextView = v.findViewById(R.id.bulk_uid)
            val detail: TextView = v.findViewById(R.id.bulk_detail)
            val status: TextView = v.findViewById(R.id.bulk_status)
        }
    }

    private fun showRowDetail(e: BulkLog.Entry) {
        val full = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(e.timestamp))
        val sb = StringBuilder()
        sb.append("Tag #${e.seq}\n\n")
        sb.append("UID: ${e.uid.ifBlank { "unknown" }}\n")
        sb.append("Time: $full\n")
        sb.append("Status: ${if (e.success) "Written" else "Failed"}")
        if (e.success && e.locked) sb.append(" & locked")
        sb.append("\n")
        if (e.duplicate) sb.append("⚠ This UID was already written earlier.\n")
        sb.append("\nURL:\n${e.url}")
        if (!e.success) sb.append("\n\nError:\n${e.error}")

        MaterialAlertDialogBuilder(this)
            .setTitle("Row details")
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy UID") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tag UID", e.uid))
                Toast.makeText(this, "UID copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        private const val MENU_EXPORT = 1
        private const val MENU_CLEAR = 2
    }
}
