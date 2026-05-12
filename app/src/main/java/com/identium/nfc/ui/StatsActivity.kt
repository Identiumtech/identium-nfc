package com.identium.nfc.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.identium.nfc.R
import com.identium.nfc.data.History
import java.util.concurrent.TimeUnit

/**
 * Read-only stats dashboard built off the History log.
 * No external charting lib — we draw simple bars with weighted Views,
 * which is good enough for a tag-counting overview.
 */
class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Statistics"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val entries = History.load(this)
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - TimeUnit.DAYS.toMillis(7)

        val totalReads = entries.count { it.action == History.Action.READ }
        val totalWrites = entries.count { it.action == History.Action.WRITE }
        val totalErases = entries.count { it.action == History.Action.ERASE }
        val totalLocks = entries.count { it.action == History.Action.LOCK }
        val totalPwds = entries.count { it.action == History.Action.PASSWORD_SET }
        val totalCopies = entries.count { it.action == History.Action.COPY || it.action == History.Action.CLONE }
        val totalVerifies = entries.count { it.action == History.Action.VERIFY }
        val successRate = entries.takeIf { it.isNotEmpty() }
            ?.let { it.count { e -> e.success } * 100.0 / it.size } ?: 0.0
        val last7d = entries.filter { it.timestamp >= sevenDaysAgo }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        if (entries.isEmpty()) {
            root.addView(emptyState())
        } else {
            // Big numbers row
            root.addView(headlineRow(
                Pair("$totalWrites", "Writes"),
                Pair("$totalReads", "Reads"),
                Pair("%.0f%%".format(successRate), "Success")
            ))

            root.addView(section("Operation breakdown"))
            val ops = listOf(
                "Writes" to totalWrites,
                "Reads" to totalReads,
                "Erases" to totalErases,
                "Locks" to totalLocks,
                "Password ops" to totalPwds,
                "Copy / Clone" to totalCopies,
                "Verify" to totalVerifies
            )
            val maxOp = ops.maxOf { it.second }.coerceAtLeast(1)
            ops.forEach { (label, count) ->
                root.addView(barRow(label, count, maxOp))
            }

            root.addView(section("Last 7 days"))
            root.addView(daysChart(last7d, sevenDaysAgo))

            root.addView(section("Top tag types written"))
            val typeCounts = entries
                .filter { it.action == History.Action.WRITE && it.summary.isNotBlank() }
                .flatMap { it.summary.split(" + ", " | ") }
                .map { it.substringBefore(' ').substringBefore('=') }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(8)
            if (typeCounts.isEmpty()) {
                root.addView(caption("No write data yet — write a few tags and come back."))
            } else {
                val maxType = typeCounts.first().second.coerceAtLeast(1)
                typeCounts.forEach { (label, n) -> root.addView(barRow(label, n, maxType)) }
            }
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun headlineRow(vararg pairs: Pair<String, String>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(20))
        }
        for ((value, label) in pairs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_outlined)
                setPadding(dp(14), dp(16), dp(14), dp(16))
                gravity = Gravity.CENTER
            }
            val v = TextView(this).apply {
                text = value
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.brand_blue))
            }
            val l = TextView(this).apply {
                text = label.uppercase()
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(getColor(R.color.text_secondary))
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(v); card.addView(l)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (row.childCount == 0) 0 else dp(8)
            }
            row.addView(card, lp)
        }
        return row
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.brand_blue))
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun barRow(label: String, value: Int, max: Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labelRow.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        labelRow.addView(TextView(this).apply {
            text = value.toString()
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })
        container.addView(labelRow)

        val barTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x1A1A3ADB)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(4) }
        }
        val pct = (value.toFloat() / max).coerceIn(0f, 1f)
        val fill = View(this).apply { setBackgroundColor(getColor(R.color.brand_blue)) }
        val fillW = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct)
        val rest = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - pct)
        barTrack.addView(fill, fillW)
        barTrack.addView(View(this), rest)
        container.addView(barTrack)
        return container
    }

    private fun daysChart(entries: List<History.Entry>, sinceMs: Long): View {
        val chart = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            gravity = Gravity.BOTTOM
        }
        val buckets = IntArray(7)
        val today = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)
        for (e in entries) {
            val day = e.timestamp / TimeUnit.DAYS.toMillis(1)
            val idx = (6 - (today - day).toInt()).coerceIn(0, 6)
            buckets[idx]++
        }
        val maxBucket = buckets.maxOrNull()?.coerceAtLeast(1) ?: 1
        for (i in 0..6) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            }
            val bar = View(this).apply {
                setBackgroundResource(R.drawable.bg_brand_chip_blue)
                layoutParams = LinearLayout.LayoutParams(
                    dp(20),
                    ((buckets[i].toFloat() / maxBucket) * dp(80)).toInt().coerceAtLeast(if (buckets[i] > 0) dp(4) else dp(2))
                )
            }
            val label = TextView(this).apply {
                text = if (i == 6) "Today" else "${6 - i}d"
                textSize = 10f
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(0, dp(4), 0, 0)
            }
            val count = TextView(this).apply {
                text = buckets[i].toString()
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.brand_blue))
                setPadding(0, 0, 0, dp(2))
            }
            column.addView(count)
            column.addView(bar)
            column.addView(label)
            chart.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return chart
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun emptyState(): View = TextView(this).apply {
        text = "No tag operations yet. Stats will appear here once you start reading or writing tags."
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(48), dp(24), dp(24))
        setTextColor(getColor(R.color.text_secondary))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
