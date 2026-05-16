package com.identium.nfc.util

import com.identium.nfc.nfc.WifiAuth
import com.identium.nfc.nfc.WriteRecord

/**
 * Convert a [WriteRecord] into the string a QR scanner expects for the
 * same intent. We use the universally-recognised formats:
 *   - URL    → raw URL
 *   - Phone  → tel: URI
 *   - SMS    → smsto: URI
 *   - Email  → mailto: with subject/body params
 *   - Geo    → geo: URI
 *   - Wi-Fi  → WIFI:T:…;S:…;P:…;H:…;; (Android / iOS native)
 *   - vCard  → MECARD: (compact, scanned by every camera)
 *   - others → fallback to the user-facing summary
 *
 * Some NDEF record types (Bluetooth OOB, custom MIME) don't have a
 * standard QR analogue; for those we serialise the payload as plain text
 * so at least the data survives the round-trip.
 */
fun WriteRecord.toQrText(): String = when (this) {
    is WriteRecord.Url -> url
    is WriteRecord.Text -> text
    is WriteRecord.Phone -> "tel:${number.trim()}"
    is WriteRecord.Sms -> if (body.isBlank()) "smsto:${number.trim()}"
                          else "smsto:${number.trim()}:$body"
    is WriteRecord.Email -> {
        val sb = StringBuilder("mailto:").append(to.trim())
        val params = buildList {
            if (subject.isNotBlank()) add("subject=" + android.net.Uri.encode(subject))
            if (body.isNotBlank())    add("body=" + android.net.Uri.encode(body))
        }
        if (params.isNotEmpty()) sb.append('?').append(params.joinToString("&"))
        sb.toString()
    }
    is WriteRecord.Geo -> {
        val base = "geo:%.6f,%.6f".format(latitude, longitude)
        if (label.isNullOrBlank()) base
        else "$base?q=" + android.net.Uri.encode(label)
    }
    is WriteRecord.AddressEntry -> "geo:0,0?q=" + android.net.Uri.encode(address)
    is WriteRecord.App -> "market://details?id=${packageName.trim()}"
    is WriteRecord.Wifi -> {
        // WIFI: format — Android and iOS camera both recognise it natively.
        val t = when (WifiAuth.valueOf(auth)) {
            WifiAuth.OPEN -> "nopass"
            else -> "WPA"   // WPA/WPA2 share the same QR token
        }
        fun esc(s: String) = s.replace("\\", "\\\\")
            .replace(";", "\\;").replace(",", "\\,")
            .replace(":", "\\:").replace("\"", "\\\"")
        val parts = mutableListOf("T:$t", "S:${esc(ssid)}")
        if (t != "nopass") parts += "P:${esc(password)}"
        if (hidden) parts += "H:true"
        "WIFI:" + parts.joinToString(";") + ";;"
    }
    is WriteRecord.Vcard -> {
        // MECARD format — compact, scanned natively by phone cameras
        // into "Add to contacts" prompts.
        val parts = mutableListOf("MECARD:")
        if (fullName.isNotBlank()) parts += "N:${escapeMecard(fullName)};"
        if (organization.isNotBlank()) parts += "ORG:${escapeMecard(organization)};"
        if (titleField.isNotBlank()) parts += "TITLE:${escapeMecard(titleField)};"
        if (phone.isNotBlank()) parts += "TEL:${escapeMecard(phone)};"
        if (email.isNotBlank()) parts += "EMAIL:${escapeMecard(email)};"
        if (website.isNotBlank()) parts += "URL:${escapeMecard(website)};"
        if (address.isNotBlank()) parts += "ADR:${escapeMecard(address)};"
        if (note.isNotBlank()) parts += "NOTE:${escapeMecard(note)};"
        parts.joinToString("") + ";"
    }
    is WriteRecord.Bluetooth -> deviceName?.let { "$mac ($it)" } ?: mac
    is WriteRecord.CustomMime -> payloadAscii
}

private fun escapeMecard(s: String): String =
    s.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(":", "\\:")
        .replace(",", "\\,")
