package com.identium.nfc.data

import android.content.Context

/**
 * Stored once per device — used to pre-fill vCard / Email / Phone / Website
 * record editors and quick recipes. Lets the user tap "Business card"
 * and have their info ready instead of typing it every time.
 *
 * Defaults are empty by design — every field the user leaves blank stays
 * blank in any tag or QR they generate. The app itself does not inject
 * Identium URLs, addresses, or contact info into customer payloads.
 */
object Profile {

    private const val PREFS = "identium_profile"

    data class Card(
        val fullName: String = "",
        val company: String = "",
        val title: String = "",
        val phone: String = "",
        val email: String = "",
        val website: String = "",
        val address: String = "",
        val note: String = ""
    )

    fun load(ctx: Context): Card {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Card(
            fullName = p.getString("fullName", "") ?: "",
            company = p.getString("company", "") ?: "",
            title = p.getString("title", "") ?: "",
            phone = p.getString("phone", "") ?: "",
            email = p.getString("email", "") ?: "",
            website = p.getString("website", "") ?: "",
            address = p.getString("address", "") ?: "",
            note = p.getString("note", "") ?: ""
        )
    }

    fun save(ctx: Context, card: Card) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("fullName", card.fullName)
            putString("company", card.company)
            putString("title", card.title)
            putString("phone", card.phone)
            putString("email", card.email)
            putString("website", card.website)
            putString("address", card.address)
            putString("note", card.note)
        }.apply()
    }

    fun isFilled(ctx: Context): Boolean {
        val c = load(ctx)
        return c.fullName.isNotBlank() || c.email.isNotBlank() || c.phone.isNotBlank()
    }

    /** Helper for Quick Recipes — true when [website] is set. */
    fun hasWebsite(ctx: Context): Boolean = load(ctx).website.isNotBlank()
}
