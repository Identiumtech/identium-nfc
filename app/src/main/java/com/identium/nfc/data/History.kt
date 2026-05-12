package com.identium.nfc.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Append-only log of tag operations, persisted to SharedPreferences as a
 * single JSON array. Bounded to MAX entries (FIFO eviction) so the prefs
 * file can't grow unbounded.
 *
 * SharedPreferences is plenty for ~200 short rows; pulling in Room would
 * be overkill for this volume.
 */
object History {

    private const val PREFS = "identium_history"
    private const val KEY = "entries"
    private const val MAX = 200

    enum class Action(val display: String) {
        READ("Read"),
        WRITE("Write"),
        ERASE("Erase"),
        FORMAT("Format"),
        LOCK("Make read-only"),
        PASSWORD_SET("Set password"),
        PASSWORD_CLEAR("Remove password"),
        COPY("Copy"),
        CLONE("Clone"),
        VERIFY("Verify");
    }

    data class Entry(
        val timestamp: Long,
        val action: Action,
        val uid: String,
        val tagType: String,
        val summary: String,
        val success: Boolean
    )

    fun record(
        ctx: Context,
        action: Action,
        uid: String,
        tagType: String = "",
        summary: String = "",
        success: Boolean = true
    ) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = parse(prefs.getString(KEY, "[]"))
        val obj = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("act", action.name)
            put("uid", uid)
            put("type", tagType)
            put("summary", summary)
            put("ok", success)
        }
        // Most recent first.
        val newArr = JSONArray()
        newArr.put(obj)
        for (i in 0 until arr.length().coerceAtMost(MAX - 1)) newArr.put(arr.get(i))
        prefs.edit().putString(KEY, newArr.toString()).apply()
    }

    fun load(ctx: Context): List<Entry> {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = parse(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Entry(
                timestamp = obj.optLong("ts"),
                action = runCatching { Action.valueOf(obj.getString("act")) }.getOrDefault(Action.READ),
                uid = obj.optString("uid"),
                tagType = obj.optString("type"),
                summary = obj.optString("summary"),
                success = obj.optBoolean("ok", true)
            )
        }
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** CSV export for the share button. */
    fun toCsv(entries: List<Entry>): String {
        val sb = StringBuilder("timestamp,action,uid,type,success,summary\n")
        for (e in entries) {
            sb.append(e.timestamp).append(',')
            sb.append(e.action.name).append(',')
            sb.append('"').append(e.uid.replace("\"", "\"\"")).append('"').append(',')
            sb.append('"').append(e.tagType.replace("\"", "\"\"")).append('"').append(',')
            sb.append(if (e.success) "ok" else "fail").append(',')
            sb.append('"').append(e.summary.replace("\"", "\"\"").replace("\n", " ")).append('"')
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parse(s: String?): JSONArray = try {
        if (s.isNullOrBlank()) JSONArray() else JSONArray(s)
    } catch (_: Exception) { JSONArray() }
}
