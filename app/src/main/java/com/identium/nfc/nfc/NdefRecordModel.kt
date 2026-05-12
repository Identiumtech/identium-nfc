package com.identium.nfc.nfc

/**
 * Sealed model for record types the user can author in the Write screen.
 *
 * Persisted via JSON-style serialization — kept simple by sticking to flat
 * fields per record so the Write list can survive process death and be
 * shipped as parameters between Activities.
 */
sealed class WriteRecord : java.io.Serializable {
    abstract val title: String
    abstract val typeKey: String
    abstract val summary: String

    data class Url(val url: String) : WriteRecord() {
        override val title get() = "URL / URI"
        override val typeKey get() = TYPE_URL
        override val summary get() = url
    }

    data class Text(val text: String, val lang: String = "en") : WriteRecord() {
        override val title get() = "Plain text"
        override val typeKey get() = TYPE_TEXT
        override val summary get() = text.take(80)
    }

    data class Email(val to: String, val subject: String, val body: String) : WriteRecord() {
        override val title get() = "Email"
        override val typeKey get() = TYPE_EMAIL
        override val summary get() = to
    }

    data class Phone(val number: String) : WriteRecord() {
        override val title get() = "Phone number"
        override val typeKey get() = TYPE_PHONE
        override val summary get() = number
    }

    data class Sms(val number: String, val body: String) : WriteRecord() {
        override val title get() = "SMS"
        override val typeKey get() = TYPE_SMS
        override val summary get() = number
    }

    data class Geo(val latitude: Double, val longitude: Double, val label: String?) : WriteRecord() {
        override val title get() = "Geolocation"
        override val typeKey get() = TYPE_GEO
        override val summary get() = "%.5f, %.5f".format(latitude, longitude)
    }

    data class AddressEntry(val address: String) : WriteRecord() {
        override val title get() = "Address"
        override val typeKey get() = TYPE_ADDR
        override val summary get() = address
    }

    data class App(val packageName: String) : WriteRecord() {
        override val title get() = "Android Application"
        override val typeKey get() = TYPE_APP
        override val summary get() = packageName
    }

    data class Wifi(
        val ssid: String,
        val password: String,
        val auth: String, // WifiAuth.name()
        val enc: String,  // WifiEnc.name()
        val hidden: Boolean = false
    ) : WriteRecord() {
        override val title get() = "Wi-Fi"
        override val typeKey get() = TYPE_WIFI
        override val summary get() = ssid
    }

    data class Bluetooth(val mac: String, val deviceName: String?) : WriteRecord() {
        override val title get() = "Bluetooth"
        override val typeKey get() = TYPE_BT
        override val summary get() = mac
    }

    data class Vcard(
        val fullName: String, val organization: String, val titleField: String,
        val phone: String, val email: String, val website: String,
        val address: String, val note: String
    ) : WriteRecord() {
        override val title get() = "Business card (vCard)"
        override val typeKey get() = TYPE_VCARD
        override val summary get() = fullName
    }

    data class CustomMime(val mimeType: String, val payloadAscii: String) : WriteRecord() {
        override val title get() = "Custom MIME / Data"
        override val typeKey get() = TYPE_MIME
        override val summary get() = mimeType
    }

    companion object {
        const val TYPE_URL = "url"
        const val TYPE_TEXT = "text"
        const val TYPE_EMAIL = "email"
        const val TYPE_PHONE = "phone"
        const val TYPE_SMS = "sms"
        const val TYPE_GEO = "geo"
        const val TYPE_ADDR = "address"
        const val TYPE_APP = "app"
        const val TYPE_WIFI = "wifi"
        const val TYPE_BT = "bluetooth"
        const val TYPE_VCARD = "vcard"
        const val TYPE_MIME = "mime"
    }
}

/**
 * Convert a [WriteRecord] into a real [android.nfc.NdefRecord] that we can
 * actually push to the chip.
 */
fun WriteRecord.toNdef(): android.nfc.NdefRecord = when (this) {
    is WriteRecord.Url -> NdefBuilder.url(url)
    is WriteRecord.Text -> NdefBuilder.text(text, lang)
    is WriteRecord.Email -> NdefBuilder.email(to, subject, body)
    is WriteRecord.Phone -> NdefBuilder.phone(number)
    is WriteRecord.Sms -> NdefBuilder.sms(number, body)
    is WriteRecord.Geo -> NdefBuilder.geo(latitude, longitude, label)
    is WriteRecord.AddressEntry -> NdefBuilder.address(address)
    is WriteRecord.App -> NdefBuilder.androidApp(packageName)
    is WriteRecord.Wifi -> NdefBuilder.wifi(
        ssid, password,
        WifiAuth.valueOf(auth), WifiEnc.valueOf(enc), hidden
    )
    is WriteRecord.Bluetooth -> NdefBuilder.bluetooth(mac, deviceName)
    is WriteRecord.Vcard -> NdefBuilder.vcard(
        VCard(fullName, organization, titleField, phone, email, website, address, note)
    )
    is WriteRecord.CustomMime -> NdefBuilder.mime(mimeType, payloadAscii.toByteArray(Charsets.UTF_8))
}
