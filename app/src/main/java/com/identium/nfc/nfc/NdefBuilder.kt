package com.identium.nfc.nfc

import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.charset.StandardCharsets

/**
 * Builds NDEF messages for every record type the app supports.
 *
 * Most types ride on top of the Forum well-known URI/Text record specs.
 * Wi-Fi uses the legacy WSC carrier mime type accepted by Android.
 */
object NdefBuilder {

    private const val MIME_TEXT = "text/plain"
    private const val MIME_VCARD = "text/x-vCard"

    fun message(record: NdefRecord) = NdefMessage(arrayOf(record))
    fun message(records: List<NdefRecord>) = NdefMessage(records.toTypedArray())

    fun url(url: String): NdefRecord = NdefRecord.createUri(normalizeUrl(url))

    fun text(text: String, langTag: String = "en"): NdefRecord {
        val langBytes = langTag.toByteArray(StandardCharsets.US_ASCII)
        require(langBytes.size < 64)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = langBytes.size.toByte()
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return NdefRecord(
            NdefRecord.TNF_WELL_KNOWN,
            NdefRecord.RTD_TEXT,
            ByteArray(0),
            payload
        )
    }

    fun email(to: String, subject: String?, body: String?): NdefRecord {
        val sb = StringBuilder("mailto:").append(Uri.encode(to))
        val params = mutableListOf<String>()
        if (!subject.isNullOrBlank()) params += "subject=" + Uri.encode(subject)
        if (!body.isNullOrBlank()) params += "body=" + Uri.encode(body)
        if (params.isNotEmpty()) sb.append('?').append(params.joinToString("&"))
        return NdefRecord.createUri(sb.toString())
    }

    fun phone(number: String): NdefRecord =
        NdefRecord.createUri("tel:" + number.trim())

    fun sms(number: String, body: String?): NdefRecord {
        val sb = StringBuilder("smsto:").append(number.trim())
        if (!body.isNullOrBlank()) sb.append('?').append("body=").append(Uri.encode(body))
        return NdefRecord.createUri(sb.toString())
    }

    fun geo(lat: Double, lon: Double, label: String? = null): NdefRecord {
        val base = "geo:%f,%f".format(lat, lon)
        val full = if (label.isNullOrBlank()) base else "$base?q=" + Uri.encode(label)
        return NdefRecord.createUri(full)
    }

    fun address(query: String): NdefRecord =
        NdefRecord.createUri("geo:0,0?q=" + Uri.encode(query))

    fun androidApp(packageName: String): NdefRecord =
        NdefRecord.createApplicationRecord(packageName)

    fun mime(mime: String, payload: ByteArray): NdefRecord =
        NdefRecord.createMime(mime, payload)

    fun vcard(card: VCard): NdefRecord {
        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\r\n")
        sb.append("VERSION:3.0\r\n")
        if (card.fullName.isNotBlank()) {
            sb.append("FN:").append(card.fullName).append("\r\n")
            val parts = card.fullName.split(" ", limit = 2)
            val last = parts.getOrNull(1) ?: ""
            val first = parts[0]
            sb.append("N:").append(last).append(';').append(first).append(";;;\r\n")
        }
        if (card.organization.isNotBlank())
            sb.append("ORG:").append(card.organization).append("\r\n")
        if (card.title.isNotBlank())
            sb.append("TITLE:").append(card.title).append("\r\n")
        if (card.phone.isNotBlank())
            sb.append("TEL;TYPE=CELL:").append(card.phone).append("\r\n")
        if (card.email.isNotBlank())
            sb.append("EMAIL:").append(card.email).append("\r\n")
        if (card.website.isNotBlank())
            sb.append("URL:").append(card.website).append("\r\n")
        if (card.address.isNotBlank())
            sb.append("ADR:;;").append(card.address).append(";;;;\r\n")
        if (card.note.isNotBlank())
            sb.append("NOTE:").append(card.note).append("\r\n")
        sb.append("END:VCARD\r\n")
        return mime(MIME_VCARD, sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Wi-Fi config record using the WSC credential mime type understood by Android.
     * Encodes SSID, network key and authentication / encryption type using TLV
     * fields per the Wi-Fi Alliance Simple Configuration spec.
     */
    fun wifi(ssid: String, password: String, auth: WifiAuth, enc: WifiEnc, hidden: Boolean = false): NdefRecord {
        val ssidBytes = ssid.toByteArray(StandardCharsets.UTF_8)
        val pwdBytes = password.toByteArray(StandardCharsets.UTF_8)

        // Credential TLV
        val credentialBody = mutableListOf<ByteArray>().apply {
            add(tlv(0x1045, ssidBytes)) // SSID
            add(tlv(0x1003, shortBytes(auth.code)))
            add(tlv(0x100F, shortBytes(enc.code)))
            add(tlv(0x1027, pwdBytes))
            // Network index, MAC etc are optional.
        }
        val credBytes = concat(credentialBody)
        val payload = tlv(0x100E, credBytes)
        val recordType = "application/vnd.wfa.wsc".toByteArray(StandardCharsets.US_ASCII)
        return NdefRecord(NdefRecord.TNF_MIME_MEDIA, recordType, ByteArray(0), payload)
    }

    /**
     * Bluetooth Out-of-Band record (handover).
     * Uses the Forum well-known carrier configuration type defined by the BT SSP spec.
     */
    fun bluetooth(macAddress: String, deviceName: String? = null): NdefRecord {
        // OOB payload: 2 byte length, 6 byte MAC, optional EIR fields.
        val mac = macAddress.split(":", "-").map { it.toInt(16).toByte() }.reversed().toByteArray()
        require(mac.size == 6) { "Bluetooth MAC must be 6 bytes" }

        val eir = mutableListOf<ByteArray>()
        if (!deviceName.isNullOrBlank()) {
            val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
            // EIR: length, type 0x09 (Complete Local Name)
            val ext = ByteArray(nameBytes.size + 2)
            ext[0] = (nameBytes.size + 1).toByte()
            ext[1] = 0x09
            System.arraycopy(nameBytes, 0, ext, 2, nameBytes.size)
            eir += ext
        }
        val eirBytes = concat(eir)
        val total = 2 + mac.size + eirBytes.size
        val payload = ByteArray(total)
        payload[0] = (total and 0xFF).toByte()
        payload[1] = (total ushr 8).toByte()
        System.arraycopy(mac, 0, payload, 2, mac.size)
        System.arraycopy(eirBytes, 0, payload, 2 + mac.size, eirBytes.size)
        val recordType = "application/vnd.bluetooth.ep.oob".toByteArray(StandardCharsets.US_ASCII)
        return NdefRecord(NdefRecord.TNF_MIME_MEDIA, recordType, ByteArray(0), payload)
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) ||
            trimmed.startsWith("mailto:") || trimmed.startsWith("tel:") ||
            trimmed.startsWith("smsto:") || trimmed.startsWith("geo:")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray {
        val out = ByteArray(4 + value.size)
        out[0] = (type ushr 8 and 0xFF).toByte()
        out[1] = (type and 0xFF).toByte()
        out[2] = (value.size ushr 8 and 0xFF).toByte()
        out[3] = (value.size and 0xFF).toByte()
        System.arraycopy(value, 0, out, 4, value.size)
        return out
    }

    private fun shortBytes(value: Int): ByteArray =
        byteArrayOf((value ushr 8 and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) { System.arraycopy(p, 0, out, off, p.size); off += p.size }
        return out
    }
}

data class VCard(
    val fullName: String,
    val organization: String = "",
    val title: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val address: String = "",
    val note: String = ""
)

enum class WifiAuth(val code: Int, val label: String) {
    OPEN(0x0001, "Open"),
    WPA_PSK(0x0002, "WPA Personal"),
    WPA2_PSK(0x0020, "WPA2 Personal"),
    WPA_WPA2_PSK(0x0022, "WPA / WPA2 Personal"),
    WPA2_EAP(0x0010, "WPA2 Enterprise");
}

enum class WifiEnc(val code: Int, val label: String) {
    NONE(0x0001, "None"),
    WEP(0x0002, "WEP"),
    TKIP(0x0004, "TKIP"),
    AES(0x0008, "AES"),
    AES_TKIP(0x000C, "AES / TKIP")
}
