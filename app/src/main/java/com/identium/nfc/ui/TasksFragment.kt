package com.identium.nfc.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.identium.nfc.NfcViewModel
import com.identium.nfc.databinding.FragmentTasksBinding
import com.identium.nfc.databinding.ItemRecordTypeBinding
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.nfc.WifiAuth
import com.identium.nfc.nfc.WifiEnc
import com.identium.nfc.nfc.WriteRecord
import com.identium.nfc.util.SuccessDialog

/**
 * "Tasks" tab — quick recipes that build a single record and queue an
 * immediate write. Each task is a one-tap shortcut for a common use-case
 * (Wi-Fi handover, dial number, open URL, etc.).
 */
class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NfcViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.gridTasks.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.gridTasks.adapter = TaskAdapter(TASKS) { handleTask(it) }

        viewModel.lastResult.observe(viewLifecycleOwner) { event ->
            val result = event?.consume() ?: return@observe
            if (result.success) {
                SuccessDialog.show(
                    requireActivity(),
                    title = "Tag written",
                    body = "${result.bytesWritten} bytes written. The next time someone taps this tag, the task will trigger."
                )
            } else {
                SuccessDialog.showError(requireActivity(), "Write failed", result.message)
            }
        }
    }

    private fun handleTask(task: TaskUI) {
        when (task.id) {
            ID_OPEN_URL -> textPrompt("Open URL", "https://example.com") {
                writeRecord(WriteRecord.Url(it))
            }
            ID_DIAL -> textPrompt("Phone number", "+1 555 123 4567") {
                writeRecord(WriteRecord.Phone(it))
            }
            ID_SMS -> twoFieldPrompt("SMS", "Number", "Message") { number, body ->
                writeRecord(WriteRecord.Sms(number, body))
            }
            ID_EMAIL -> threeFieldPrompt("Email", "Recipient", "Subject", "Body") { to, sub, body ->
                writeRecord(WriteRecord.Email(to, sub, body))
            }
            ID_WIFI -> wifiPrompt()
            ID_BLUETOOTH -> twoFieldPrompt("Bluetooth pair", "MAC (AA:BB:CC:DD:EE:FF)", "Device name (optional)") { mac, name ->
                if (!mac.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"))) {
                    Toast.makeText(requireContext(), "Invalid MAC address — use AA:BB:CC:DD:EE:FF", Toast.LENGTH_LONG).show()
                } else {
                    writeRecord(WriteRecord.Bluetooth(mac, name.ifEmpty { null }))
                }
            }
            ID_APP -> textPrompt("Run application", "com.example.app") {
                writeRecord(WriteRecord.App(it))
            }
            ID_SETTINGS -> {
                val items = listOf(
                    "Wi-Fi" to "android.settings.WIFI_SETTINGS",
                    "Bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
                    "Location" to "android.settings.LOCATION_SOURCE_SETTINGS",
                    "Airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
                    "Sound" to "android.settings.SOUND_SETTINGS",
                    "Display" to "android.settings.DISPLAY_SETTINGS",
                    "NFC" to "android.settings.NFC_SETTINGS"
                )
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Open settings panel")
                    .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                        // Encode as a custom MIME so a tap from any reader opens it via our app.
                        writeRecord(WriteRecord.CustomMime("application/com.identium.nfc.settings", items[which].second))
                    }.show()
            }
            ID_ALARM -> twoFieldPrompt("Set alarm", "Hour (0-23)", "Minute (0-59)") { h, m ->
                writeRecord(WriteRecord.CustomMime("application/com.identium.nfc.alarm", "$h:$m"))
            }
            ID_TIMER -> textPrompt("Timer (seconds)", "60") {
                writeRecord(WriteRecord.CustomMime("application/com.identium.nfc.timer", it))
            }
            ID_VOLUME -> textPrompt("Set ringer volume (0–10)", "5") {
                writeRecord(WriteRecord.CustomMime("application/com.identium.nfc.volume", it))
            }
            ID_BRIGHTNESS -> textPrompt("Set brightness (0–255)", "128") {
                writeRecord(WriteRecord.CustomMime("application/com.identium.nfc.brightness", it))
            }
            ID_EVENT -> threeFieldPrompt("Calendar event", "Title", "Date (YYYY-MM-DD)", "Time (HH:MM)") { t, d, time ->
                writeRecord(WriteRecord.CustomMime("application/com.identium.nfc.event", "$t|$d|$time"))
            }
        }
    }

    private fun writeRecord(record: WriteRecord) {
        viewModel.queueOperation(PendingOperation.Write(listOf(record), lockAfter = false))
    }

    private fun textPrompt(title: String, hint: String, onOk: (String) -> Unit) {
        val edit = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setHint(hint)
        }
        val til = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            addView(edit); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(til)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = edit.text?.toString().orEmpty().trim()
                if (v.isNotEmpty()) onOk(v)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun twoFieldPrompt(title: String, h1: String, h2: String, onOk: (String, String) -> Unit) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val e1 = TextInputEditText(requireContext()).apply { setHint(h1) }
        val e2 = TextInputEditText(requireContext()).apply { setHint(h2) }
        val t1 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(e1); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val t2 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(e2); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        container.addView(t1)
        container.addView(t2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v1 = e1.text?.toString().orEmpty().trim()
                val v2 = e2.text?.toString().orEmpty().trim()
                if (v1.isNotEmpty()) onOk(v1, v2)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun threeFieldPrompt(title: String, h1: String, h2: String, h3: String, onOk: (String, String, String) -> Unit) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val e1 = TextInputEditText(requireContext()).apply { setHint(h1) }
        val e2 = TextInputEditText(requireContext()).apply { setHint(h2) }
        val e3 = TextInputEditText(requireContext()).apply { setHint(h3); minLines = 2 }
        val t1 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(e1); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val t2 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(e2); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val t3 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(e3); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        container.addView(t1)
        container.addView(t2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        container.addView(t3, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v1 = e1.text?.toString().orEmpty().trim()
                val v2 = e2.text?.toString().orEmpty().trim()
                val v3 = e3.text?.toString().orEmpty().trim()
                if (v1.isNotEmpty()) onOk(v1, v2, v3)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun wifiPrompt() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val ssid = TextInputEditText(requireContext()).apply { setHint("SSID") }
        val pwd = TextInputEditText(requireContext()).apply { setHint("Password"); inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT }
        val s1 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(ssid); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE }
        val s2 = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { addView(pwd); boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE }
        container.addView(s1)
        container.addView(s2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Connect Wi-Fi")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val s = ssid.text?.toString().orEmpty().trim()
                val p = pwd.text?.toString().orEmpty()
                if (s.isNotEmpty()) writeRecord(WriteRecord.Wifi(s, p, WifiAuth.WPA2_PSK.name, WifiEnc.AES.name, false))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private class TaskAdapter(
        val items: List<TaskUI>,
        val onClick: (TaskUI) -> Unit
    ) : RecyclerView.Adapter<TaskAdapter.VH>() {
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

    data class TaskUI(val id: Int, val label: String, val iconRes: Int)

    companion object {
        const val ID_OPEN_URL = 1
        const val ID_DIAL = 2
        const val ID_SMS = 3
        const val ID_EMAIL = 4
        const val ID_WIFI = 5
        const val ID_BLUETOOTH = 6
        const val ID_APP = 7
        const val ID_SETTINGS = 8
        const val ID_ALARM = 9
        const val ID_TIMER = 10
        const val ID_VOLUME = 11
        const val ID_BRIGHTNESS = 12
        const val ID_EVENT = 13

        private val TASKS = listOf(
            TaskUI(ID_OPEN_URL, "Open URL", android.R.drawable.ic_menu_send),
            TaskUI(ID_DIAL, "Dial number", android.R.drawable.ic_menu_call),
            TaskUI(ID_SMS, "Send SMS", android.R.drawable.ic_dialog_dialer),
            TaskUI(ID_EMAIL, "Email", android.R.drawable.ic_dialog_email),
            TaskUI(ID_WIFI, "Connect Wi-Fi", android.R.drawable.ic_menu_share),
            TaskUI(ID_BLUETOOTH, "Pair Bluetooth", android.R.drawable.stat_sys_data_bluetooth),
            TaskUI(ID_APP, "Run app", android.R.drawable.ic_menu_manage),
            TaskUI(ID_SETTINGS, "Open settings", android.R.drawable.ic_menu_preferences),
            TaskUI(ID_ALARM, "Set alarm", android.R.drawable.ic_lock_idle_alarm),
            TaskUI(ID_TIMER, "Timer", android.R.drawable.ic_menu_recent_history),
            TaskUI(ID_VOLUME, "Volume", android.R.drawable.ic_lock_silent_mode_off),
            TaskUI(ID_BRIGHTNESS, "Brightness", android.R.drawable.ic_menu_view),
            TaskUI(ID_EVENT, "Calendar event", android.R.drawable.ic_menu_my_calendar)
        )
    }
}
