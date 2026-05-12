package com.identium.nfc.nfc

import android.nfc.NdefMessage

/**
 * Snapshot of everything we know about a tag at the moment it was tapped.
 * Built once in [TagReader] and rendered by the UI.
 */
data class TagInfo(
    val uidHex: String,
    val uidLength: Int,
    val techList: List<String>,
    val type: TagType,
    val atqaHex: String? = null,
    val sakHex: String? = null,
    val historicalBytesHex: String? = null,
    val maxTransceiveLength: Int? = null,
    val totalMemoryBytes: Int? = null,
    val usedMemoryBytes: Int? = null,
    val writable: Boolean = false,
    val canMakeReadOnly: Boolean = false,
    val ndefMessage: NdefMessage? = null,
    val rawDump: ByteArray? = null,
    val pageCount: Int? = null,
    val productName: String? = null
) {
    val freeMemoryBytes: Int?
        get() = if (totalMemoryBytes != null && usedMemoryBytes != null)
            (totalMemoryBytes - usedMemoryBytes).coerceAtLeast(0)
        else null
}

enum class TagType(val display: String) {
    NTAG_213("NTAG213"),
    NTAG_215("NTAG215"),
    NTAG_216("NTAG216"),
    MIFARE_ULTRALIGHT("MIFARE Ultralight"),
    MIFARE_ULTRALIGHT_C("MIFARE Ultralight C"),
    MIFARE_CLASSIC_1K("MIFARE Classic 1K"),
    MIFARE_CLASSIC_4K("MIFARE Classic 4K"),
    NFC_FORUM_TYPE_2("NFC Forum Type 2"),
    NFC_FORUM_TYPE_4("NFC Forum Type 4"),
    NFC_FORUM_TYPE_5("NFC Forum Type 5"),
    GENERIC("Unknown / generic")
}
