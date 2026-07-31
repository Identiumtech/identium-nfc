package com.identium.nfc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.R
import com.identium.nfc.data.History
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browsable log of every tag operation. Read-only — taps copy the UID,
 * long-press exports the row as text. Top-bar menu has Clear + Export CSV.
 *
 * Useful for production-line writing where you want to confirm each tag
 * actually got the write you queued.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private val df = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        // The app theme is NoActionBar — without an explicit toolbar the
        // Export CSV / Clear overflow items would never be reachable.
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            setBackgroundResource(R.drawable.bg_brand_header)
            title = getString(R.string.history)
            setTitleTextColor(getColor(R.color.white))
            navigationIcon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                ?.also { it.setTint(getColor(R.color.white)) }
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        setSupportActionBar(toolbar)
        toolbar.post { toolbar.overflowIcon?.setTint(getColor(R.color.white)) }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        emptyView = TextView(this).apply {
            text = "No tag operations recorded yet.\nStart reading or writing tags — entries will appear here."
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(64), dp(24), dp(24))
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(emptyView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
        }
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = History.load(this)
        if (entries.isEmpty()) {
            emptyView.visibility = TextView.VISIBLE
            recycler.visibility = RecyclerView.GONE
        } else {
            emptyView.visibility = TextView.GONE
            recycler.visibility = RecyclerView.VISIBLE
            recycler.adapter = HistoryAdapter(entries) { entry ->
                copyToClipboard("UID", entry.uid)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EXPORT, 0, "Export CSV")
            .setIcon(android.R.drawable.ic_menu_save)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_CLEAR, 1, "Clear")
            .setIcon(android.R.drawable.ic_menu_delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_EXPORT -> { exportCsv(); true }
        MENU_CLEAR -> {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear history?")
                .setMessage("Removes all ${History.load(this).size} entries permanently.")
                .setPositiveButton("Clear") { _, _ ->
                    History.clear(this); refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun exportCsv() {
        val csv = History.toCsv(History.load(this))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Identium NFC history")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(send, "Export history"))
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class HistoryAdapter(
        private val items: List<History.Entry>,
        private val onUidClick: (History.Entry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.action.text = e.action.display
            holder.timestamp.text = if (System.currentTimeMillis() - e.timestamp < DateUtils.DAY_IN_MILLIS)
                DateUtils.getRelativeTimeSpanString(e.timestamp).toString()
            else df.format(Date(e.timestamp))
            holder.uid.text = e.uid.ifBlank { "(unknown UID)" }
            holder.summary.text = listOfNotNull(
                e.tagType.takeIf { it.isNotBlank() },
                e.summary.takeIf { it.isNotBlank() }
            ).joinToString(" • ").ifBlank { "—" }
            holder.statusDot.text = if (e.success) "✓" else "✗"
            holder.statusDot.setTextColor(getColor(if (e.success) R.color.success else R.color.error))
            holder.itemView.setOnClickListener { onUidClick(e) }
        }

        inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val action: TextView = v.findViewById(R.id.history_action)
            val timestamp: TextView = v.findViewById(R.id.history_timestamp)
            val uid: TextView = v.findViewById(R.id.history_uid)
            val summary: TextView = v.findViewById(R.id.history_summary)
            val statusDot: TextView = v.findViewById(R.id.history_status)
        }
    }

    companion object {
        private const val MENU_EXPORT = 1
        private const val MENU_CLEAR = 2
    }
}
