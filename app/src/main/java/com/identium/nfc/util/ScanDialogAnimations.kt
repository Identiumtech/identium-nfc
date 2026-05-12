package com.identium.nfc.util

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.identium.nfc.R

/**
 * Branded "waiting for tag" sonar animation. Attached to the scan dialog
 * (BaseNfcActivity + ScanDialogFragment) on show, cancelled on dismiss.
 *
 * Three concentric blue rings expand from the center NFC coin and fade
 * out, staggered 600ms apart so the wave looks continuous. The Identium
 * logo gets a subtle 1.0 → 1.06 pulse so the brand reads as alive even
 * before the user taps a tag.
 */
object ScanDialogAnimations {

    private const val PULSE_DURATION_MS = 1800L
    private const val PULSE_STAGGER_MS = 600L
    private const val PULSE_MAX_SCALE = 2.6f
    private const val LOGO_PULSE_DURATION_MS = 1400L
    private const val LOGO_MAX_SCALE = 1.06f

    fun startOn(dialogView: View): List<AnimatorSet> {
        val animators = mutableListOf<AnimatorSet>()
        listOf(R.id.scan_ring_1, R.id.scan_ring_2, R.id.scan_ring_3).forEachIndexed { i, id ->
            val v = dialogView.findViewById<View>(id) ?: return@forEachIndexed
            animators += createSonarPulse(v, i * PULSE_STAGGER_MS)
        }
        dialogView.findViewById<View>(R.id.scan_logo)?.let {
            animators += createLogoPulse(it)
        }
        return animators
    }

    fun stop(animators: List<AnimatorSet>) {
        animators.forEach {
            try { it.cancel() } catch (_: Exception) { /* already cancelled */ }
        }
    }

    private fun createSonarPulse(view: View, startDelayMs: Long): AnimatorSet {
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 0.55f
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, PULSE_MAX_SCALE).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, PULSE_MAX_SCALE).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }
        val a = ObjectAnimator.ofFloat(view, View.ALPHA, 0.55f, 0f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateInterpolator()
        }
        return AnimatorSet().apply {
            playTogether(sx, sy, a)
            startDelay = startDelayMs
            start()
        }
    }

    private fun createLogoPulse(view: View): AnimatorSet {
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, LOGO_MAX_SCALE).apply {
            duration = LOGO_PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, LOGO_MAX_SCALE).apply {
            duration = LOGO_PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        return AnimatorSet().apply {
            playTogether(sx, sy)
            start()
        }
    }
}
