package com.identium.nfc.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.R

/**
 * Big, unmistakable success popup that the user can't miss.
 * Used after every tag write so the user knows the operation actually
 * landed on the chip (not just a transient Toast).
 */
object SuccessDialog {

    fun show(activity: Activity, title: String, body: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        vibrate(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_success, null, false)
        view.findViewById<TextView>(R.id.success_title).text = title
        view.findViewById<TextView>(R.id.success_body).text = body
        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showError(activity: Activity, title: String, body: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_success, null, false)
        view.findViewById<ImageView>(R.id.success_icon).setImageResource(android.R.drawable.ic_dialog_alert)
        view.findViewById<ImageView>(R.id.success_icon).setColorFilter(activity.getColor(R.color.error))
        view.findViewById<View>(R.id.success_chip).setBackgroundColor(0x33DC2626.toInt())
        view.findViewById<TextView>(R.id.success_title).text = title
        view.findViewById<TextView>(R.id.success_body).text = body
        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun vibrate(ctx: Context) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(80)
            }
        } catch (_: Exception) { /* no haptic available */ }
    }
}
