package com.identium.nfc.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Digital Passport / Authenticity Verification store.
 *
 * Concept: the customer (brand owner) sets up one or more Identities. Each
 * Identity is just a base verification URL — e.g. `https://acme.com/verify`.
 * Every passport tag the app issues carries a unique URL of the form
 *   `<baseUrl>?id=<productId>&uid=<tagHardwareUid>`
 *
 * When anyone taps the tag with any phone, the OS opens that URL in a
 * browser. The customer's backend can then look up the product ID and
 * cross-check the tag's hardware UID — if both match what they issued,
 * the product is authentic. Cloned tags carry a different hardware UID
 * so the backend can flag them.
 *
 * No backend code is shipped with the app — the customer plugs in their
 * own endpoint. The app handles writing + reading the URL only.
 */
object Passports {

    private const val PREFS = "identium_passports"
    private const val KEY_LIST = "identities"
    private const val KEY_ACTIVE = "active"

    enum class IdJoin { PATH, QUERY }

    data class Identity(
        val id: String,
        val name: String,
        val baseUrl: String,
        val join: IdJoin,
        val includeUid: Boolean,
        val createdAt: Long
    ) {
        /**
         * Build the final URL for [productId] on a tag with [uidHex].
         * QUERY join produces `?id=foo&uid=04...`, PATH join produces
         * `/foo` (UID appended as `?uid=04...` if includeUid).
         */
        fun buildUrl(productId: String, uidHex: String): String {
            val base = baseUrl.trim().removeSuffix("/")
            val pid = Uri.encode(productId.trim())
            val uidQuery = if (includeUid && uidHex.isNotBlank()) "&uid=${Uri.encode(uidHex)}" else ""
            return when (join) {
                IdJoin.PATH -> {
                    val onlyUid = if (includeUid && uidHex.isNotBlank()) "?uid=${Uri.encode(uidHex)}" else ""
                    if (pid.isBlank()) "$base$onlyUid" else "$base/$pid$onlyUid"
                }
                IdJoin.QUERY -> {
                    val idQuery = if (pid.isBlank()) "" else "?id=$pid"
                    val joiner = if (idQuery.isEmpty()) "?" else "&"
                    val uidPart = if (includeUid && uidHex.isNotBlank())
                        "${joiner}uid=${Uri.encode(uidHex)}" else ""
                    "$base$idQuery$uidPart"
                }
            }
        }

        /** True when this identity issued [url] (prefix match on base URL). */
        fun matches(url: String): Boolean {
            val u = url.trim()
            val b = baseUrl.trim().removeSuffix("/")
            return u.startsWith(b)
        }
    }

    fun list(ctx: Context): List<Identity> {
        val prefs = prefs(ctx)
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) { emptyList() }
    }

    fun save(ctx: Context, identity: Identity) {
        val all = list(ctx).filter { it.id != identity.id }.toMutableList()
        all.add(0, identity)
        write(ctx, all)
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, list(ctx).filter { it.id != id })
        if (activeId(ctx) == id) setActiveId(ctx, list(ctx).firstOrNull()?.id ?: "")
    }

    fun active(ctx: Context): Identity? {
        val id = activeId(ctx)
        return list(ctx).firstOrNull { it.id == id } ?: list(ctx).firstOrNull()
    }

    fun activeId(ctx: Context): String =
        prefs(ctx).getString(KEY_ACTIVE, "") ?: ""

    fun setActiveId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun newIdentity(name: String, baseUrl: String,
                    join: IdJoin = IdJoin.QUERY, includeUid: Boolean = true): Identity =
        Identity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            join = join,
            includeUid = includeUid,
            createdAt = System.currentTimeMillis()
        )

    /** Find a saved identity that issued [url], if any. */
    fun matchUrl(ctx: Context, url: String): Identity? =
        list(ctx).firstOrNull { it.matches(url) }

    // ---- internals ----

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun write(ctx: Context, identities: List<Identity>) {
        val arr = JSONArray()
        identities.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun toJson(i: Identity): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("name", i.name)
        put("baseUrl", i.baseUrl)
        put("join", i.join.name)
        put("includeUid", i.includeUid)
        put("createdAt", i.createdAt)
    }

    private fun fromJson(o: JSONObject): Identity = Identity(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "Untitled"),
        baseUrl = o.optString("baseUrl", ""),
        join = runCatching { IdJoin.valueOf(o.optString("join", "QUERY")) }.getOrDefault(IdJoin.QUERY),
        includeUid = o.optBoolean("includeUid", true),
        createdAt = o.optLong("createdAt", 0L)
    )
}
