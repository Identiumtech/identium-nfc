package com.identium.nfc.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent log of tags written by the Bulk write & lock screen.
 *
 * Separate from [History] on purpose: History is a 200-entry mixed log of
 * every operation type, while a bulk run can burn through hundreds of tags
 * in one sitting and needs its own bulk-specific fields (URL written, lock
 * state, sequence number).
 *
 * Backed by SharedPreferences JSON — same approach as the rest of the app,
 * no extra dependency. The entry list is cached in memory after [load] so a
 * 500-row log doesn't get re-parsed on every single tag; appends serialize
 * from the cache and write through with `apply()` (async, off the UI thread).
 *
 * All mutating calls happen from the main thread (the NFC result callback),
 * so no locking is required.
 */
object BulkLog {

    private const val PREFS = "identium_bulklog"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TOTAL = "lifetime_total"

    /** Newest-first cap. Older rows fall off the end. */
    const val MAX = 500

    data class Entry(
        /** Lifetime sequence number — keeps counting across sessions. */
        val seq: Int,
        val timestamp: Long,
        val uid: String,
        val url: String,
        val locked: Boolean,
        val success: Boolean,
        val error: String = "",
        /** True when this UID already appeared earlier in the log. */
        val duplicate: Boolean = false
    )

    private var cache: MutableList<Entry>? = null

    /** Newest first. */
    fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val raw = prefs(ctx).getString(KEY_ENTRIES, "[]") ?: "[]"
        val list = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Entry(
                    seq = o.optInt("seq", arr.length() - i),
                    timestamp = o.optLong("ts"),
                    uid = o.optString("uid"),
                    url = o.optString("url"),
                    locked = o.optBoolean("locked", false),
                    success = o.optBoolean("ok", true),
                    error = o.optString("err"),
                    duplicate = o.optBoolean("dup", false)
                )
            }
        } catch (_: Exception) {
            // Corrupted store — start clean rather than crash the screen.
        }
        cache = list
        return list
    }

    /**
     * Append a result and persist. Returns the created entry (already at
     * index 0 of the in-memory list) so the caller can insert it into the
     * table without reloading.
     */
    fun append(
        ctx: Context,
        uid: String,
        url: String,
        locked: Boolean,
        success: Boolean,
        error: String = ""
    ): Entry {
        val list = load(ctx)
        val isDuplicate = uid.isNotBlank() && list.any { it.uid == uid }
        val nextSeq = nextSeq(ctx)
        val entry = Entry(
            seq = nextSeq,
            timestamp = System.currentTimeMillis(),
            uid = uid,
            url = url,
            locked = locked,
            success = success,
            error = error,
            duplicate = isDuplicate
        )
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.size - 1)
        persist(ctx, list, nextSeq)
        return entry
    }

    fun clear(ctx: Context) {
        cache = mutableListOf()
        prefs(ctx).edit().remove(KEY_ENTRIES).apply()
        // Deliberately keeps KEY_TOTAL so sequence numbers stay unique and
        // monotonic even after the visible log is wiped.
    }

    /** Wipe the log AND reset numbering back to #1. */
    fun resetAll(ctx: Context) {
        cache = mutableListOf()
        prefs(ctx).edit().clear().apply()
    }

    fun counts(ctx: Context): Triple<Int, Int, Int> {
        val list = load(ctx)
        val ok = list.count { it.success }
        val fail = list.size - ok
        return Triple(list.size, ok, fail)
    }

    fun toCsv(entries: List<Entry>): String {
        val sb = StringBuilder("seq,timestamp_ms,iso_time,uid,url,locked,status,error\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        for (e in entries) {
            sb.append(e.seq).append(',')
            sb.append(e.timestamp).append(',')
            sb.append('"').append(fmt.format(java.util.Date(e.timestamp))).append('"').append(',')
            sb.append('"').append(csv(e.uid)).append('"').append(',')
            sb.append('"').append(csv(e.url)).append('"').append(',')
            sb.append(if (e.locked) "locked" else "unlocked").append(',')
            sb.append(if (e.success) "written" else "failed").append(',')
            sb.append('"').append(csv(e.error)).append('"')
            sb.append('\n')
        }
        return sb.toString()
    }

    // ── internals ──

    private fun csv(s: String) = s.replace("\"", "\"\"").replace("\n", " ")

    private fun nextSeq(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TOTAL, 0) + 1

    private fun persist(ctx: Context, list: List<Entry>, lifetimeTotal: Int) {
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
                put("dup", e.duplicate)
            })
        }
        prefs(ctx).edit()
            .putString(KEY_ENTRIES, arr.toString())
            .putInt(KEY_TOTAL, lifetimeTotal)
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
