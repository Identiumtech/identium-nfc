package com.identium.nfc.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.InputType
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.NfcViewModel
import com.identium.nfc.data.Counter
import com.identium.nfc.data.Templates
import com.identium.nfc.databinding.FragmentWriteBinding
import com.identium.nfc.databinding.ItemRecordCardBinding
import com.identium.nfc.databinding.ItemRecordTypeBinding
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.nfc.WriteRecord
import com.identium.nfc.nfc.toNdef
import com.identium.nfc.util.SuccessDialog
import com.identium.nfc.util.toQrText

class WriteFragment : Fragment() {

    private var _binding: FragmentWriteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NfcViewModel by activityViewModels()

    private val recordEditorLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val rec = res.data?.getSerializableExtra(RecordEditorActivity.EXTRA_RECORD) as? WriteRecord
            if (rec != null) viewModel.appendWriteRecord(rec)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWriteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.gridRecordTypes.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.gridRecordTypes.adapter = TypeAdapter(RECORD_TYPES) { type ->
            recordEditorLauncher.launch(RecordEditorActivity.intent(requireContext(), type.key))
        }

        viewModel.writeQueue.observe(viewLifecycleOwner) { records ->
            renderRecords(records)
        }

        viewModel.lastResult.observe(viewLifecycleOwner) { event ->
            val result = event?.consume() ?: return@observe
            if (result.success) {
                SuccessDialog.show(
                    requireActivity(),
                    title = "Write successful",
                    body = "${result.bytesWritten} bytes written to the tag."
                )
                // Clear the queue + lock checkbox so the next write starts fresh.
                viewModel.clearWriteQueue()
                binding.checkLock.isChecked = false
            } else {
                SuccessDialog.showError(
                    requireActivity(),
                    title = "Write failed",
                    body = result.message
                )
            }
        }

        binding.btnWrite.setOnClickListener { onWritePressed() }
        binding.btnShowQr.setOnClickListener { onShowQrPressed() }
        binding.btnSaveTemplate.setOnClickListener { promptSaveTemplate() }
        binding.btnLoadTemplate.setOnClickListener { promptLoadTemplate() }
    }

    private fun onShowQrPressed() {
        val records = viewModel.writeQueue.value.orEmpty()
        if (records.isEmpty()) {
            Toast.makeText(requireContext(), "Add a record first", Toast.LENGTH_SHORT).show()
            return
        }
        // QR carries one piece of data; if the user staged multiple records
        // we use the first and warn so they know the rest aren't included.
        val first = records.first()
        val payload = first.toQrText()
        val applied = if (Counter.isEnabled(requireContext()) && payload.contains("{n}"))
            payload.replace("{n}", Counter.renderCurrent(requireContext()))
        else payload
        if (records.size > 1) {
            Toast.makeText(
                requireContext(),
                "QR only fits one record — using \"${first.title}\". The other ${records.size - 1} stay queued for NFC.",
                Toast.LENGTH_LONG
            ).show()
        }
        startActivity(QrCodeActivity.intent(requireContext(), applied, label = "${first.title}: ${first.summary}"))
    }

    override fun onResume() {
        super.onResume()
        renderCounterStatus()
    }

    private fun renderCounterStatus() {
        val ctx = context ?: return
        if (Counter.isEnabled(ctx)) {
            val current = Counter.renderCurrent(ctx)
            binding.counterStatus.visibility = View.VISIBLE
            binding.counterStatus.text = "Auto-counter ON: {n} → $current (next write will use this and bump)"
        } else {
            binding.counterStatus.visibility = View.GONE
        }
    }

    private fun promptSaveTemplate() {
        val records = viewModel.writeQueue.value.orEmpty()
        if (records.isEmpty()) {
            Toast.makeText(requireContext(), "Add records first", Toast.LENGTH_SHORT).show()
            return
        }
        val til = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Template name"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val edit = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        til.addView(edit)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save template")
            .setView(til)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text?.toString().orEmpty().trim()
                if (name.isNotEmpty()) {
                    Templates.save(requireContext(), name, records)
                    Toast.makeText(requireContext(), "Saved as “$name”", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptLoadTemplate() {
        val templates = Templates.list(requireContext())
        if (templates.isEmpty()) {
            Toast.makeText(requireContext(), "No saved templates yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = templates.map { "${it.name} (${it.records.size} record(s))" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Load template")
            .setItems(labels) { _, which ->
                val t = templates[which]
                viewModel.clearWriteQueue()
                t.records.forEach { viewModel.appendWriteRecord(it) }
                Toast.makeText(requireContext(), "Loaded ${t.records.size} record(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderRecords(records: List<WriteRecord>) {
        binding.recordsContainer.removeAllViews()
        if (records.isEmpty()) {
            binding.recordsEmpty.visibility = View.VISIBLE
            binding.payloadSummary.text = getString(com.identium.nfc.R.string.payload_size) + ": 0 bytes"
            binding.btnWrite.isEnabled = false
        } else {
            binding.recordsEmpty.visibility = View.GONE
            binding.btnWrite.isEnabled = true
            records.forEachIndexed { i, rec ->
                val card = ItemRecordCardBinding.inflate(layoutInflater, binding.recordsContainer, false)
                card.recordTitle.text = rec.title
                card.recordSummary.text = rec.summary
                card.recordIcon.setImageResource(iconForType(rec.typeKey))
                card.recordRemove.setOnClickListener {
                    viewModel.removeWriteRecordAt(i)
                }
                binding.recordsContainer.addView(card.root)
            }
            val msg = try {
                android.nfc.NdefMessage(records.map { it.toNdef() }.toTypedArray())
            } catch (_: Exception) { null }
            binding.payloadSummary.text = getString(com.identium.nfc.R.string.payload_size) +
                ": " + (msg?.byteArrayLength ?: 0) + " bytes"
        }
    }

    private fun onWritePressed() {
        val records = viewModel.writeQueue.value.orEmpty()
        if (records.isEmpty()) return
        val lock = binding.checkLock.isChecked
        if (lock) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Lock after writing?")
                .setMessage("This will permanently lock the tag — it will never be writable again.")
                .setPositiveButton("Write & Lock") { _, _ ->
                    viewModel.queueOperation(PendingOperation.Write(records, lockAfter = true))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            viewModel.queueOperation(PendingOperation.Write(records, lockAfter = false))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class TypeAdapter(
        val items: List<RecordTypeUI>,
        val onClick: (RecordTypeUI) -> Unit
    ) : RecyclerView.Adapter<TypeAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRecordTypeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.typeIcon.setImageResource(item.iconRes)
            holder.b.typeLabel.text = item.label
            holder.b.root.setOnClickListener { onClick(item) }
        }
        class VH(val b: ItemRecordTypeBinding) : RecyclerView.ViewHolder(b.root)
    }

    data class RecordTypeUI(val key: String, val label: String, val iconRes: Int)

    companion object {
        private val RECORD_TYPES = listOf(
            RecordTypeUI(WriteRecord.TYPE_URL, "URL", android.R.drawable.ic_menu_send),
            RecordTypeUI(WriteRecord.TYPE_TEXT, "Text", android.R.drawable.ic_menu_edit),
            RecordTypeUI(WriteRecord.TYPE_VCARD, "Contact", android.R.drawable.ic_menu_myplaces),
            RecordTypeUI(WriteRecord.TYPE_WIFI, "Wi-Fi", android.R.drawable.ic_menu_share),
            RecordTypeUI(WriteRecord.TYPE_EMAIL, "Email", android.R.drawable.ic_dialog_email),
            RecordTypeUI(WriteRecord.TYPE_PHONE, "Phone", android.R.drawable.ic_menu_call),
            RecordTypeUI(WriteRecord.TYPE_SMS, "SMS", android.R.drawable.ic_dialog_dialer),
            RecordTypeUI(WriteRecord.TYPE_GEO, "Geo", android.R.drawable.ic_menu_mylocation),
            RecordTypeUI(WriteRecord.TYPE_ADDR, "Address", android.R.drawable.ic_menu_directions),
            RecordTypeUI(WriteRecord.TYPE_APP, "App", android.R.drawable.ic_menu_manage),
            RecordTypeUI(WriteRecord.TYPE_BT, "Bluetooth", android.R.drawable.stat_sys_data_bluetooth),
            RecordTypeUI(WriteRecord.TYPE_MIME, "Custom", android.R.drawable.ic_menu_save)
        )
        fun iconForType(typeKey: String): Int =
            RECORD_TYPES.firstOrNull { it.key == typeKey }?.iconRes ?: android.R.drawable.ic_menu_edit
    }
}
