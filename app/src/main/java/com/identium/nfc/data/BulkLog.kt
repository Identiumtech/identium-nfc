package com.identium.nfc.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent log of tags written by the Bulk write & lock screen.
 *
 * Separate from [History] on purpose: History is a 200-entry mixed log of
 * every operation type, while a bulk run can burn through thousands of tags
 * and needs its own bulk-specific fields (URL written, lock state, sequence
 * number).
 *
 * Two distinct things are stored, and keeping them separate matters:
 *
 *  - **Lifetime counters** (total / written / locked / failed). These are
 *    plain persisted integers that only ever go up. They are NOT derived
 *    from the row list, because the row list is capped — deriving them
 *    meant the totals froze once the cap was hit, and could even count
 *    *down* as old successful rows were evicted.
 *  - **Recent rows** for the on-screen table, capped at [MAX] newest-first
 *    so the prefs file stays a sane size.
 *
 * All mutating calls happen from the main thread (the NFC result callback),
 * so no locking is required.
 */
object BulkLog {

    private const val PREFS = "identium_bulklog"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TOTAL = "lifetime_total"
    private const val KEY_OK = "lifetime_ok"
    private const val KEY_LOCKED = "lifetime_locked"
    private const val KEY_FAIL = "lifetime_fail"

    /** Newest-first row cap. Lifetime counters are unaffected by this. */
    const val MAX = 500

    /** Why a tag attempt did not result in a write. */
    enum class Outcome { WRITTEN, DUPLICATE, ALREADY_HAS_DATA, FAILED }

    data class Entry(
        /** Lifetime sequence number — keeps counting across sessions. */
        val seq: Int,
        val timestamp: Long,
        val uid: String,
        val url: String,
        val locked: Boolean,
        val success: Boolean,
        val error: String = "",
        val outcome: Outcome = if (success) Outcome.WRITTEN else Outcome.FAILED
    )

    data class Counts(
        val total: Int,
        val written: Int,
        val locked: Int,
        val failed: Int
    )

    private var cache: MutableList<Entry>? = null

    /** Newest first. Only the most recent [MAX] rows are retained. */
    fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val raw = prefs(ctx).getString(KEY_ENTRIES, "[]") ?: "[]"
        val list = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ok = o.optBoolean("ok", true)
                list += Entry(
                    seq = o.optInt("seq", arr.length() - i),
                    timestamp = o.optLong("ts"),
                    uid = o.optString("uid"),
                    url = o.optString("url"),
                    locked = o.optBoolean("locked", false),
                    success = ok,
                    error = o.optString("err"),
                    outcome = runCatching { Outcome.valueOf(o.optString("out")) }
                        .getOrDefault(if (ok) Outcome.WRITTEN else Outcome.FAILED)
                )
            }
        } catch (_: Exception) {
            // Corrupted store — start clean rather than crash the screen.
        }
        cache = list
        return list
    }

    /**
     * Look up a previously logged tag by UID. Only searches the retained
     * rows, so a UID written more than [MAX] tags ago won't be found —
     * enough to catch a re-tap during a production run.
     */
    fun findByUid(ctx: Context, uid: String): Entry? {
        if (uid.isBlank()) return null
        return load(ctx).firstOrNull { it.uid == uid }
    }

    /**
     * Append a result and persist. Lifetime counters advance here and are
     * never recomputed from the (capped) row list.
     */
    fun append(
        ctx: Context,
        uid: String,
        url: String,
        locked: Boolean,
        success: Boolean,
        error: String = "",
        outcome: Outcome = if (success) Outcome.WRITTEN else Outcome.FAILED
    ): Entry {
        val list = load(ctx)
        val p = prefs(ctx)

        val nextSeq = p.getInt(KEY_TOTAL, 0) + 1
        val newOk = p.getInt(KEY_OK, 0) + if (success) 1 else 0
        val newLocked = p.getInt(KEY_LOCKED, 0) + if (success && locked) 1 else 0
        val newFail = p.getInt(KEY_FAIL, 0) + if (success) 0 else 1

        val entry = Entry(
            seq = nextSeq,
            timestamp = System.currentTimeMillis(),
            uid = uid,
            url = url,
            locked = locked,
            success = success,
            error = error,
            outcome = outcome
        )
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.size - 1)

        p.edit()
            .putString(KEY_ENTRIES, serialize(list))
            .putInt(KEY_TOTAL, nextSeq)
            .putInt(KEY_OK, newOk)
            .putInt(KEY_LOCKED, newLocked)
            .putInt(KEY_FAIL, newFail)
            .apply()
        return entry
    }

    /** Lifetime totals — unaffected by the row cap, never decrease. */
    fun counts(ctx: Context): Counts {
        val p = prefs(ctx)
        return Counts(
            total = p.getInt(KEY_TOTAL, 0),
            written = p.getInt(KEY_OK, 0),
            locked = p.getInt(KEY_LOCKED, 0),
            failed = p.getInt(KEY_FAIL, 0)
        )
    }

    /** Clears the visible rows but keeps lifetime totals and numbering. */
    fun clear(ctx: Context) {
        cache = mutableListOf()
        prefs(ctx).edit().remove(KEY_ENTRIES).apply()
    }

    /** Wipes rows AND resets every lifetime counter back to zero. */
    fun resetAll(ctx: Context) {
        cache = mutableListOf()
        prefs(ctx).edit().clear().apply()
    }

    fun toCsv(entries: List<Entry>): String {
        val sb = StringBuilder("seq,timestamp_ms,iso_time,uid,url,locked,status,outcome,error\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        for (e in entries) {
            sb.append(e.seq).append(',')
            sb.append(e.timestamp).append(',')
            sb.append('"').append(fmt.format(java.util.Date(e.timestamp))).append('"').append(',')
            sb.append('"').append(csv(e.uid)).append('"').append(',')
            sb.append('"').append(csv(e.url)).append('"').append(',')
            sb.append(if (e.locked) "locked" else "unlocked").append(',')
            sb.append(if (e.success) "written" else "failed").append(',')
            sb.append(e.outcome.name).append(',')
            sb.append('"').append(csv(e.error)).append('"')
            sb.append('\n')
        }
        return sb.toString()
    }

    // ── internals ──

    private fun csv(s: String) = s.replace("\"", "\"\"").replace("\n", " ")

    private fun serialize(list: List<Entry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("seq", e.seq)
                put("ts", e.timestamp)
                put("uid", e.uid)
                put("url", e.url)
                put("locked", e.locked)
                put("ok", e.success)
                put("err", e.error)
                put("out", e.outcome.name)
            })
        }
        return arr.toString()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
