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
 * This lets a user write `https://example.com/tag/{n}` and tap 1000 tags
 * in a row, getting `/tag/1`, `/tag/2`, ... automatically.
 *
 * The value can be rendered as decimal or as hex — cable-tie and asset tags
 * are commonly serialised in hex, often zero-padded to a fixed width so every
 * printed serial is the same length (e.g. 0000001A).
 */
object Counter {

    private const val PREFS = "identium_counter"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VALUE = "value"
    private const val KEY_PAD = "padding"
    private const val KEY_FORMAT = "format"

    /** Max zero-padding width — 16 covers a full 64-bit hex serial. */
    const val MAX_PADDING = 16

    enum class Format(val label: String) {
        DECIMAL("Decimal (1, 2, 3…)"),
        HEX_UPPER("Hex uppercase (1, 2, … A, B)"),
        HEX_LOWER("Hex lowercase (1, 2, … a, b)");
    }

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun current(ctx: Context): Int = prefs(ctx).getInt(KEY_VALUE, 1)

    fun setCurrent(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_VALUE, value.coerceAtLeast(0)).apply()
    }

    fun padding(ctx: Context): Int = prefs(ctx).getInt(KEY_PAD, 0)

    fun setPadding(ctx: Context, pad: Int) {
        prefs(ctx).edit().putInt(KEY_PAD, pad.coerceIn(0, MAX_PADDING)).apply()
    }

    fun format(ctx: Context): Format =
        runCatching { Format.valueOf(prefs(ctx).getString(KEY_FORMAT, Format.DECIMAL.name)!!) }
            .getOrDefault(Format.DECIMAL)

    fun setFormat(ctx: Context, f: Format) {
        prefs(ctx).edit().putString(KEY_FORMAT, f.name).apply()
    }

    fun bumpAfterWrite(ctx: Context) {
        setCurrent(ctx, current(ctx) + 1)
    }

    /** Render the *current* value using the saved padding + format. */
    fun renderCurrent(ctx: Context): String =
        render(current(ctx), padding(ctx), format(ctx))

    fun render(value: Int, padding: Int, format: Format = Format.DECIMAL): String {
        val base = when (format) {
            Format.DECIMAL -> value.toString()
            Format.HEX_UPPER -> Integer.toHexString(value).uppercase()
            Format.HEX_LOWER -> Integer.toHexString(value).lowercase()
        }
        return if (padding <= 0) base else base.padStart(padding, '0')
    }

    /**
     * Returns a copy of [records] with `{n}` replaced by the formatted
     * counter value. Anything that doesn't contain `{n}` is returned
     * unchanged.
     */
    fun applyTo(
        records: List<WriteRecord>,
        value: Int,
        padding: Int,
        format: Format = Format.DECIMAL
    ): List<WriteRecord> {
        val token = "{n}"
        val rendered = render(value, padding, format)
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

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
