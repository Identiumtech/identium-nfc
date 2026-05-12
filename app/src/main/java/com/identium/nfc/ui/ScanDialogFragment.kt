package com.identium.nfc.ui

import android.animation.AnimatorSet
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.NfcViewModel
import com.identium.nfc.R
import com.identium.nfc.databinding.DialogScanBinding
import com.identium.nfc.nfc.PendingOperation
import com.identium.nfc.util.ScanDialogAnimations

class ScanDialogFragment : DialogFragment() {

    private val viewModel: NfcViewModel by activityViewModels()
    private var animators: List<AnimatorSet> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogScanBinding.inflate(layoutInflater)
        val op = arguments?.getSerializable("op") as? PendingOperation

        binding.scanTitle.text = when (op) {
            is PendingOperation.Read, null -> getString(R.string.approach_tag)
            is PendingOperation.Write -> "Approach tag to write"
            is PendingOperation.Erase -> "Approach tag to erase"
            is PendingOperation.Format -> "Approach tag to format"
            is PendingOperation.MakeReadOnly -> "Approach tag to lock"
            is PendingOperation.SetPassword -> "Approach tag to set password"
            is PendingOperation.RemovePassword -> "Approach tag to remove password"
            is PendingOperation.CopyTagCapture -> "Tap source tag to copy"
            is PendingOperation.CopyTagApply -> "Tap target tag to paste"
            is PendingOperation.WriteSequential -> "Tap tag #${op.index + 1} of ${op.total}"
        }

        binding.scanSubtitle.text = when (op) {
            is PendingOperation.WriteSequential -> op.sourceLine
            is PendingOperation.Write -> if (op.lockAfter)
                "Tag will be locked after writing — this is irreversible." else
                "Hold the tag still until you feel a vibration."
            is PendingOperation.MakeReadOnly -> "Once locked, the tag can never be written again."
            is PendingOperation.SetPassword -> "After this, only readers with the password can edit the tag."
            else -> "Hold the tag near the back of your phone."
        }

        binding.btnCancel.setOnClickListener {
            viewModel.clearPending()
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setCancelable(false)
            .create()
            .apply {
                setOnShowListener {
                    animators = ScanDialogAnimations.startOn(binding.root)
                }
            }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        ScanDialogAnimations.stop(animators)
        animators = emptyList()
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG = "scan_dialog"

        fun forOperation(op: PendingOperation) = ScanDialogFragment().apply {
            arguments = Bundle().apply { putSerializable("op", op) }
        }
    }
}
