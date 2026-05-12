package com.identium.nfc.nfc

import java.io.Serializable

/**
 * A request the UI hands to MainActivity. Each one represents an action that
 * needs the next tap of an NFC tag to be applied.
 */
sealed class PendingOperation : Serializable {
    data class Read(val nonce: Long = System.currentTimeMillis()) : PendingOperation()

    data class Write(
        val records: List<WriteRecord>,
        val lockAfter: Boolean = false
    ) : PendingOperation()

    data class Erase(val nonce: Long = System.currentTimeMillis()) : PendingOperation()

    data class Format(val nonce: Long = System.currentTimeMillis()) : PendingOperation()

    data class MakeReadOnly(val nonce: Long = System.currentTimeMillis()) : PendingOperation()

    data class SetPassword(
        val passwordAscii: String,
        val protectFromPage: Int = 0x04
    ) : PendingOperation()

    data class RemovePassword(
        val currentPasswordAscii: String
    ) : PendingOperation()

    data class CopyTagCapture(val nonce: Long = System.currentTimeMillis()) : PendingOperation()

    data class CopyTagApply(
        val records: List<WriteRecord>,
        val source: String
    ) : PendingOperation()

    data class WriteSequential(
        val records: List<WriteRecord>,
        val index: Int,
        val total: Int,
        val sourceLine: String
    ) : PendingOperation()
}
