package com.identium.nfc.data

import android.content.Context
import android.util.Base64
import com.identium.nfc.nfc.WriteRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-saved write-queue templates. Each template is a (name, list of
 * [WriteRecord]) tuple. We persist via Java Serialization → Base64 because
 * WriteRecord is sealed-with-Serializable already, which avoids us having
 * to write per-type JSON encoders.
 *
 * The risk with Java Serialization is renaming a record class breaks old
 * templates. Acceptable trade-off for this volume — worst case the user
 * re-saves the template.
 */
object Templates {

    private const val PREFS = "identium_templates"
    private const val KEY = "items"

    data class Template(
        val name: String,
        val createdAt: Long,
        val records: List<WriteRecord>
    )

    fun list(ctx: Context): List<Template> {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val obj = arr.getJSONObject(i)
                Template(
                    name = obj.getString("name"),
                    createdAt = obj.optLong("ts"),
                    records = decodeRecords(obj.getString("rec"))
                )
            } catch (_: Exception) { null }
        }
    }

    fun save(ctx: Context, name: String, records: List<WriteRecord>) {
        if (name.isBlank() || records.isEmpty()) return
        val current = list(ctx).filter { it.name != name }.toMutableList()
        current.add(0, Template(name, System.currentTimeMillis(), records))
        write(ctx, current)
    }

    fun delete(ctx: Context, name: String) {
        write(ctx, list(ctx).filter { it.name != name })
    }

    private fun write(ctx: Context, templates: List<Template>) {
        val arr = JSONArray()
        for (t in templates) {
            val obj = JSONObject().apply {
                put("name", t.name)
                put("ts", t.createdAt)
                put("rec", encodeRecords(t.records))
            }
            arr.put(obj)
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun encodeRecords(records: List<WriteRecord>): String {
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(ArrayList(records)) }
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeRecords(encoded: String): List<WriteRecord> {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        return ObjectInputStream(ByteArrayInputStream(bytes)).use {
            (it.readObject() as? ArrayList<WriteRecord>) ?: emptyList()
        }
    }
}
