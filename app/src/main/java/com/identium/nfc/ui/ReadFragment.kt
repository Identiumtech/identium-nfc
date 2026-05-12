package com.identium.nfc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.identium.nfc.NfcViewModel
import com.identium.nfc.databinding.FragmentReadBinding
import com.identium.nfc.databinding.RowKvBinding
import com.identium.nfc.nfc.HexUtil
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.nfc.TagInfo

class ReadFragment : Fragment() {

    private var _binding: FragmentReadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NfcViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnForceScan.setOnClickListener {
            viewModel.queueOperation(PendingOperation.Read())
        }

        // "Clear" is a UI-only reset — drops the displayed tag info so the
        // empty state comes back. It does NOT touch the physical tag.
        // Use Other → Erase tag to actually wipe a tag.
        binding.btnClearScreen.setOnClickListener {
            viewModel.publishTagInfoOrNull(null)
            Toast.makeText(requireContext(), "Cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyDump.setOnClickListener {
            val text = binding.memoryDumpText.text?.toString().orEmpty()
            if (text.isNotBlank()) copyToClipboard("Memory dump", text)
        }

        binding.btnShareReport.setOnClickListener {
            val info = viewModel.lastReadTag.value ?: return@setOnClickListener
            shareReport(info)
        }

        viewModel.lastReadTag.observe(viewLifecycleOwner) { info ->
            if (info == null) {
                binding.emptyState.visibility = View.VISIBLE
                binding.resultContainer.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.resultContainer.visibility = View.VISIBLE
                renderTag(info)
            }
        }
    }

    private fun renderTag(info: TagInfo) {
        binding.tagType.text = info.type.display
        binding.tagProduct.text = info.productName ?: info.techList.joinToString(", ")

        bindRow(binding.rowUid, "UID", info.uidHex)
        bindRow(binding.rowUidLen, "UID length", "${info.uidLength} bytes")
        bindRow(binding.rowTech, "Technologies", info.techList.joinToString(", "))
        bindRow(binding.rowAtqa, "ATQA", info.atqaHex ?: "—")
        bindRow(binding.rowSak, "SAK", info.sakHex ?: "—")
        bindRow(
            binding.rowTotalMem, "Memory size",
            info.totalMemoryBytes?.let { "$it bytes" + (info.pageCount?.let { p -> " ($p pages)" } ?: "") } ?: "—"
        )
        bindRow(
            binding.rowUsedMem, "Used by NDEF",
            info.usedMemoryBytes?.let { "$it bytes" } ?: "0 bytes"
        )
        bindRow(binding.rowWritable, "Writable", if (info.writable) "Yes" else "No")
        bindRow(binding.rowCanLock, "Can be locked", if (info.canMakeReadOnly) "Yes" else "No")

        binding.ndefRecordsContainer.removeAllViews()
        val msg = info.ndefMessage
        if (msg == null || msg.records.isEmpty()) {
            binding.ndefEmpty.visibility = View.VISIBLE
        } else {
            binding.ndefEmpty.visibility = View.GONE
            msg.records.forEachIndexed { idx, rec ->
                val rowBinding = RowKvBinding.inflate(layoutInflater, binding.ndefRecordsContainer, false)
                rowBinding.rowLabel.text = "Record ${idx + 1}"
                rowBinding.rowValue.text = describeRecord(rec)
                binding.ndefRecordsContainer.addView(rowBinding.root)
            }
        }

        val dump = info.rawDump
        if (dump == null || dump.isEmpty()) {
            binding.memoryDumpText.text = ""
            binding.memoryDumpText.visibility = View.GONE
            binding.memoryDumpEmpty.visibility = View.VISIBLE
        } else {
            binding.memoryDumpEmpty.visibility = View.GONE
            binding.memoryDumpText.visibility = View.VISIBLE
            binding.memoryDumpText.text = HexUtil.hexDump(dump)
        }
    }

    private fun describeRecord(rec: android.nfc.NdefRecord): String {
        return when (rec.tnf) {
            android.nfc.NdefRecord.TNF_WELL_KNOWN -> {
                if (rec.type.contentEquals(android.nfc.NdefRecord.RTD_URI))
                    "URI: ${rec.toUri()}"
                else if (rec.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)) {
                    val payload = rec.payload
                    val langLen = payload[0].toInt() and 0x3F
                    val text = String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                    "Text: $text"
                } else "Well-known: ${String(rec.type, Charsets.US_ASCII)}"
            }
            android.nfc.NdefRecord.TNF_MIME_MEDIA -> "MIME ${String(rec.type, Charsets.US_ASCII)}: ${rec.payload.size}B"
            android.nfc.NdefRecord.TNF_EXTERNAL_TYPE -> "External ${String(rec.type, Charsets.US_ASCII)}"
            android.nfc.NdefRecord.TNF_EMPTY -> "(empty)"
            else -> "TNF ${rec.tnf}: ${rec.payload.size}B"
        }
    }

    private fun bindRow(row: com.identium.nfc.databinding.RowKvBinding, label: String, value: String) {
        row.rowLabel.text = label
        row.rowValue.text = value
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport(info: TagInfo) {
        val sb = StringBuilder()
        sb.append("Identium NFC — tag report\n")
        sb.append("Type: ${info.type.display}\n")
        sb.append("UID: ${info.uidHex}\n")
        sb.append("Tech: ${info.techList.joinToString(", ")}\n")
        info.totalMemoryBytes?.let { sb.append("Memory: $it bytes\n") }
        info.usedMemoryBytes?.let { sb.append("Used: $it bytes\n") }
        sb.append("Writable: ${if (info.writable) "yes" else "no"}\n")
        sb.append("Can lock: ${if (info.canMakeReadOnly) "yes" else "no"}\n")
        if (info.rawDump != null) {
            sb.append("\nMemory dump:\n")
            sb.append(HexUtil.hexDump(info.rawDump))
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            putExtra(Intent.EXTRA_SUBJECT, "Identium NFC tag report")
        }
        startActivity(Intent.createChooser(intent, "Share report"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
