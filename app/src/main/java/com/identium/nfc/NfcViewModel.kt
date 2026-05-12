package com.identium.nfc

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.nfc.TagInfo
import com.identium.nfc.nfc.TagOperations
import com.identium.nfc.nfc.WriteRecord
import com.identium.nfc.util.Event

/**
 * Source of truth shared between MainActivity (which catches tag intents
 * via foreground dispatch) and the four fragments hosted inside it
 * (Read / Write / Other / Tasks).
 *
 * Sub-Activities (Password / Copy / Import) do NOT use this VM — they
 * extend BaseNfcActivity and own their NFC dispatch. That separation is
 * deliberate: a sub-Activity's foreground dispatch wins over MainActivity's
 * manifest filter, so taps land where the user is looking.
 */
class NfcViewModel : ViewModel() {

    private val _pendingOperation = MutableLiveData<PendingOperation?>()
    val pendingOperation: LiveData<PendingOperation?> = _pendingOperation

    private val _lastReadTag = MutableLiveData<TagInfo?>()
    val lastReadTag: LiveData<TagInfo?> = _lastReadTag

    /** Wrapped in [Event] so a tab switch doesn't re-fire the last toast. */
    private val _lastResult = MutableLiveData<Event<TagOperations.WriteResult>?>()
    val lastResult: LiveData<Event<TagOperations.WriteResult>?> = _lastResult

    private val _writeQueue = MutableLiveData<List<WriteRecord>>(emptyList())
    val writeQueue: LiveData<List<WriteRecord>> = _writeQueue

    fun queueOperation(op: PendingOperation) { _pendingOperation.value = op }
    fun clearPending() { _pendingOperation.value = null }

    fun publishTagInfo(info: TagInfo) { _lastReadTag.value = info }

    /** Used after Erase to fall the Read screen back to its empty state. */
    fun publishTagInfoOrNull(info: TagInfo?) { _lastReadTag.value = info }

    fun publishResult(result: TagOperations.WriteResult) {
        _lastResult.value = Event(result)
    }

    fun appendWriteRecord(record: WriteRecord) {
        _writeQueue.value = (_writeQueue.value.orEmpty()) + record
    }

    fun removeWriteRecordAt(index: Int) {
        val cur = _writeQueue.value.orEmpty()
        if (index in cur.indices) {
            _writeQueue.value = cur.toMutableList().also { it.removeAt(index) }
        }
    }

    fun clearWriteQueue() { _writeQueue.value = emptyList() }
}
