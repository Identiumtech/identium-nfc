package com.identium.nfc.data

import android.content.Context
import com.identium.nfc.nfc.WriteRecord

/**
 * Sequential counter for production-line tag writing.
 *
 * When enabled, every Url / Text / CustomMime record's payload has the
 * substring "{n}" replaced with the current counter value before write.
 * After a successful write, [bumpAfterWrite] advances the counter.
 *
 * This lets a user write `https://identium.io/tag/{n}` and tap 1000 tags
 * in a row, getting `/tag/1`, `/tag/2`, ... automatically.
 */
object Counter {

    private const val PREFS = "identium_counter"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VALUE = "value"
    private const val KEY_PAD = "padding"

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun current(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VALUE, 1)

    fun setCurrent(ctx: Context, value: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VALUE, value.coerceAtLeast(0)).apply()
    }

    fun padding(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PAD, 0)

    fun setPadding(ctx: Context, pad: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PAD, pad.coerceIn(0, 8)).apply()
    }

    fun bumpAfterWrite(ctx: Context) {
        setCurrent(ctx, current(ctx) + 1)
    }

    fun render(value: Int, padding: Int): String {
        if (padding <= 0) return value.toString()
        return value.toString().padStart(padding, '0')
    }

    /**
     * Returns a copy of [records] with `{n}` replaced by the formatted
     * counter value. Anything that doesn't contain `{n}` is returned
     * unchanged.
     */
    fun applyTo(records: List<WriteRecord>, value: Int, padding: Int): List<WriteRecord> {
        val token = "{n}"
        val rendered = render(value, padding)
        return records.map { r ->
            when (r) {
                is WriteRecord.Url -> if (r.url.contains(token)) r.copy(url = r.url.replace(token, rendered)) else r
                is WriteRecord.Text -> if (r.text.contains(token)) r.copy(text = r.text.replace(token, rendered)) else r
                is WriteRecord.Email -> r.copy(
                    to = r.to.replace(token, rendered),
                    subject = r.subject.replace(token, rendered),
                    body = r.body.replace(token, rendered)
                )
                is WriteRecord.Sms -> r.copy(body = r.body.replace(token, rendered))
                is WriteRecord.CustomMime -> if (r.payloadAscii.contains(token))
                    r.copy(payloadAscii = r.payloadAscii.replace(token, rendered)) else r
                is WriteRecord.AddressEntry -> if (r.address.contains(token))
                    r.copy(address = r.address.replace(token, rendered)) else r
                is WriteRecord.Vcard -> r.copy(
                    fullName = r.fullName.replace(token, rendered),
                    note = r.note.replace(token, rendered)
                )
                else -> r
            }
        }
    }
}
