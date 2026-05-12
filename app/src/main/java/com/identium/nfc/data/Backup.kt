package com.identium.nfc.data

import android.content.Context
import android.net.Uri
import com.identium.nfc.nfc.WriteRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot backup / restore.
 *
 * Bundles every customer-relevant pref into a single JSON payload that can
 * be written to a content URI and re-imported later. Useful for moving
 * between phones, sharing a fully-populated config with a coworker, or
 * reverting after experimenting with templates / counter values.
 *
 * Not signed or encrypted — backups can be read in any text editor.
 */
object Backup {

    private const val VERSION = 1

    fun export(ctx: Context): String {
        val obj = JSONObject().apply {
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("appVersionName", appVersionName(ctx))
            put("profile", profileToJson(Profile.load(ctx)))
            put("templates", templatesToJson(Templates.list(ctx)))
            put("history", historyToJson(History.load(ctx)))
            put("counter", JSONObject().apply {
                put("enabled", Counter.isEnabled(ctx))
                put("current", Counter.current(ctx))
                put("padding", Counter.padding(ctx))
            })
        }
        return obj.toString(2)
    }

    fun importFrom(ctx: Context, uri: Uri): ImportResult {
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return ImportResult(false, "Could not open file")
        return try {
            val obj = JSONObject(raw)
            val version = obj.optInt("version", -1)
            if (version > VERSION) return ImportResult(false, "Backup is from a newer app version ($version) — please update")

            // Profile
            obj.optJSONObject("profile")?.let { Profile.save(ctx, jsonToProfile(it)) }

            // Templates (additive — keeps existing ones not in backup, overrides matching names)
            obj.optJSONArray("templates")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    Templates.save(ctx, t.getString("name"), decodeRecords(t.getString("records")))
                }
            }

            // History (replace — backups are full snapshots)
            obj.optJSONArray("history")?.let { arr ->
                History.clear(ctx)
                for (i in arr.length() - 1 downTo 0) {
                    val e = arr.getJSONObject(i)
                    History.record(
                        ctx,
                        runCatching { History.Action.valueOf(e.getString("action")) }.getOrDefault(History.Action.READ),
                        uid = e.optString("uid"),
                        tagType = e.optString("type"),
                        summary = e.optString("summary"),
                        success = e.optBoolean("success", true)
                    )
                }
            }

            // Counter
            obj.optJSONObject("counter")?.let {
                Counter.setEnabled(ctx, it.optBoolean("enabled", false))
                Counter.setCurrent(ctx, it.optInt("current", 1))
                Counter.setPadding(ctx, it.optInt("padding", 0))
            }
            ImportResult(true, "Restored profile, templates, history, and counter")
        } catch (e: Exception) {
            ImportResult(false, "Backup file is corrupted or invalid: ${e.message}")
        }
    }

    data class ImportResult(val success: Boolean, val message: String)

    private fun appVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun profileToJson(c: Profile.Card): JSONObject = JSONObject().apply {
        put("fullName", c.fullName)
        put("company", c.company)
        put("title", c.title)
        put("phone", c.phone)
        put("email", c.email)
        put("website", c.website)
        put("address", c.address)
        put("note", c.note)
    }

    private fun jsonToProfile(o: JSONObject) = Profile.Card(
        fullName = o.optString("fullName"),
        company = o.optString("company"),
        title = o.optString("title"),
        phone = o.optString("phone"),
        email = o.optString("email"),
        website = o.optString("website"),
        address = o.optString("address"),
        note = o.optString("note")
    )

    private fun templatesToJson(items: List<Templates.Template>): JSONArray = JSONArray().apply {
        for (t in items) {
            put(JSONObject().apply {
                put("name", t.name)
                put("createdAt", t.createdAt)
                put("records", encodeRecords(t.records))
            })
        }
    }

    private fun historyToJson(items: List<History.Entry>): JSONArray = JSONArray().apply {
        for (e in items) {
            put(JSONObject().apply {
                put("ts", e.timestamp)
                put("action", e.action.name)
                put("uid", e.uid)
                put("type", e.tagType)
                put("summary", e.summary)
                put("success", e.success)
            })
        }
    }

    // Records ride through Java Serialization → Base64 (same encoding
    // Templates uses internally, just exposed here for cross-store reuse).
    private fun encodeRecords(records: List<WriteRecord>): String {
        val baos = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(baos).use { it.writeObject(ArrayList(records)) }
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeRecords(encoded: String): List<WriteRecord> {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        return java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).use {
            (it.readObject() as? ArrayList<WriteRecord>) ?: emptyList()
        }
    }
}
