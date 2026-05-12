package com.identium.nfc.data

import android.content.Context

/**
 * Stored once per device — used to pre-fill vCard / Email / Phone / Website
 * record editors and quick recipes. Lets a Sales rep tap "Business card"
 * and have their info ready instead of typing it every time.
 */
object Profile {

    private const val PREFS = "identium_profile"

    data class Card(
        val fullName: String = "",
        val company: String = "Identium Tech Solutions Pvt Ltd",
        val title: String = "",
        val phone: String = "",
        val email: String = "",
        val website: String = "https://identium.in",
        val address: String = "Plot No. 5, First Floor, Santnagar, East of Kailash, New Delhi – 110065",
        val note: String = ""
    )

    fun load(ctx: Context): Card {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Card(
            fullName = p.getString("fullName", "") ?: "",
            company = p.getString("company", "Identium Tech Solutions Pvt Ltd") ?: "",
            title = p.getString("title", "") ?: "",
            phone = p.getString("phone", "") ?: "",
            email = p.getString("email", "") ?: "",
            website = p.getString("website", "https://identium.in") ?: "",
            address = p.getString("address", "Plot No. 5, First Floor, Santnagar, East of Kailash, New Delhi – 110065") ?: "",
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
}
