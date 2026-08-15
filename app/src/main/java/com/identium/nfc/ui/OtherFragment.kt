package com.identium.nfc.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.MainActivity
import com.identium.nfc.NfcViewModel
import com.identium.nfc.R
import com.identium.nfc.databinding.FragmentOtherBinding
import com.identium.nfc.databinding.ItemActionCardBinding
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.util.SuccessDialog

class OtherFragment : Fragment() {

    private var _binding: FragmentOtherBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NfcViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOtherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val protection = listOf(
            Action("Set password", "Protect tag with a 4-byte NTAG password (NTAG213/215/216)") {
                startActivity(PasswordActivity.intent(requireContext(), PasswordActivity.MODE_SET))
            },
            Action("Remove password", "Clear PWD/PACK and unlock the tag") {
                startActivity(PasswordActivity.intent(requireContext(), PasswordActivity.MODE_REMOVE))
            },
            Action("Make tag read-only", "Set the lock bytes — irreversible") {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Lock tag permanently?")
                    .setMessage("After locking, the tag can never be written again. Are you sure?")
                    .setPositiveButton("Lock now") { _, _ ->
                        viewModel.queueOperation(PendingOperation.MakeReadOnly())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )

        val tools = listOf(
            Action("Erase tag", "Write a single empty NDEF record") {
                viewModel.queueOperation(PendingOperation.Erase())
            },
            Action("Format memory", "Format an unformatted tag (NDEF Formatable only)") {
                viewModel.queueOperation(PendingOperation.Format())
            },
            Action("Read memory dump", "Read pages and show full hex dump") {
                (activity as? MainActivity)?.selectTab(R.id.tab_read)
                viewModel.queueOperation(PendingOperation.Read())
            },
            Action("Verify tag", "Read a tag and check it matches an expected URL or text") {
                startActivity(Intent(requireContext(), VerifyTagActivity::class.java))
            },
            Action("Digital Passport / Authenticity", "Issue tap-to-verify passport tags tied to your verification URL") {
                startActivity(Intent(requireContext(), PassportActivity::class.java))
            },
            Action("Check chip authenticity", "Verify the NXP originality signature offline + read the scan counter") {
                startActivity(Intent(requireContext(), ChipInspectorActivity::class.java))
            },
            Action("Memory editor", "Read and edit raw user-memory pages in hex (power users)") {
                startActivity(Intent(requireContext(), MemoryEditorActivity::class.java))
            },
            Action("Copy tag", "Copy NDEF records — works across different chip types") {
                startActivity(Intent(requireContext(), CopyTagActivity::class.java))
            },
            Action("Clone tag", "Mirror raw user-memory pages to a same-type target") {
                startActivity(Intent(requireContext(), CloneTagActivity::class.java))
            }
        )

        val bulk = listOf(
            Action("Scan to encode", "Scan a QR code and write it straight to an NFC tag — single or continuous") {
                startActivity(Intent(requireContext(), ScanToEncodeActivity::class.java))
            },
            Action("Bulk write & lock", "Same URL on tag after tag — writes, locks and re-arms in one tap") {
                startActivity(Intent(requireContext(), BulkWriteLockActivity::class.java))
            },
            Action("Generate QR code", "Show the same data as a printable QR — for customers without NFC tags") {
                promptForQrText()
            },
            Action("Quick recipes", "Pre-built tag templates — Wi-Fi, vCard, asset tag, menu URL…") {
                startActivity(Intent(requireContext(), QuickRecipesActivity::class.java))
            },
            Action("Import from Excel / CSV", "Pick a file and write tags one after another") {
                startActivity(Intent(requireContext(), ImportExcelActivity::class.java))
            },
            Action("Tag history", "Browse a log of every tag you've read or written") {
                startActivity(Intent(requireContext(), HistoryActivity::class.java))
            },
            Action("Statistics", "Counts and trends of your tag operations") {
                startActivity(Intent(requireContext(), StatsActivity::class.java))
            },
            Action("Settings & profile", "Counter, templates, business card, backup, diagnostic, about") {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
        )

        bindGroup(binding.groupProtection, protection)
        bindGroup(binding.groupTools, tools)
        bindGroup(binding.groupBulk, bulk)

        viewModel.lastResult.observe(viewLifecycleOwner) { event ->
            val result = event?.consume() ?: return@observe
            if (result.success) {
                SuccessDialog.show(
                    requireActivity(),
                    title = "Operation successful",
                    body = result.message
                )
            } else {
                SuccessDialog.showError(
                    requireActivity(),
                    title = "Operation failed",
                    body = result.message
                )
            }
        }
    }

    private fun promptForQrText() {
        val ctx = requireContext()
        val til = com.google.android.material.textfield.TextInputLayout(
            ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "URL, text, or any payload"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val edit = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        til.addView(edit)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Generate QR code")
            .setMessage("Type or paste any URL or text. We'll render a scannable QR you can share or print.")
            .setView(til)
            .setPositiveButton("Generate") { _, _ ->
                val v = edit.text?.toString().orEmpty().trim()
                if (v.isNotEmpty()) {
                    startActivity(QrCodeActivity.intent(ctx, v))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bindGroup(container: LinearLayout, items: List<Action>) {
        container.removeAllViews()
        items.forEach { a ->
            val card = ItemActionCardBinding.inflate(layoutInflater, container, false)
            card.actionTitle.text = a.title
            card.actionSubtitle.text = a.subtitle
            card.root.setOnClickListener { a.onClick() }
            container.addView(card.root)
        }
    }

    private data class Action(val title: String, val subtitle: String, val onClick: () -> Unit)

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
