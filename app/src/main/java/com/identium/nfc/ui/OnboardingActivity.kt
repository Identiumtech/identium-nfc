package com.identium.nfc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.identium.nfc.MainActivity
import com.identium.nfc.R

/**
 * One-time welcome shown on the very first launch.
 *
 * 4 slides:
 *  1. Brand intro
 *  2. Read tag
 *  3. Write any data
 *  4. Quick recipes / production-line features
 *
 * Skipping or finishing flips a "seen" flag in SharedPreferences so it
 * never re-appears.
 */
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_IdentiumNfc)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_brand_header)
        }

        val pager = ViewPager2(this).apply {
            id = View.generateViewId()
            adapter = SlidesAdapter(slides)
        }
        root.addView(pager, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), dp(28))
            gravity = Gravity.CENTER_VERTICAL
        }

        // Dot indicator
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val dotViews = (0 until slides.size).map {
            View(this).apply {
                background = dotDrawable(false)
                val params = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(6)
                }
                layoutParams = params
            }
        }
        dotViews.forEach { dots.addView(it) }
        controls.addView(dots, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val skipBtn = TextView(this).apply {
            text = "Skip"
            textSize = 14f
            setTextColor(0xFFB6BAD0.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { finishOnboarding() }
        }
        controls.addView(skipBtn)

        val nextBtn = MaterialButton(this).apply {
            text = "Next"
            setOnClickListener {
                if (pager.currentItem < slides.size - 1) {
                    pager.currentItem = pager.currentItem + 1
                } else finishOnboarding()
            }
        }
        controls.addView(nextBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })

        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dotViews.forEachIndexed { i, v ->
                    v.background = dotDrawable(i == position)
                }
                nextBtn.text = if (position == slides.size - 1) "Get started" else "Next"
                skipBtn.visibility = if (position == slides.size - 1) View.GONE else View.VISIBLE
            }
        })

        setContentView(root)
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun dotDrawable(active: Boolean): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(if (active) 0xFFFFFFFF.toInt() else 0x55FFFFFF.toInt())
        return d
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class Slide(
        val title: String,
        val body: String,
        val iconRes: Int,
        val showLogo: Boolean = false
    )

    private val slides = listOf(
        Slide(
            "Welcome to Identium NFC",
            "The complete tag toolkit, free with every Identium NFC tag. Read, write, lock, copy, clone — production-grade workflows in your pocket.",
            R.drawable.ic_nfc_wave,
            showLogo = true
        ),
        Slide(
            "Read every tag",
            "UID, ATQA, SAK, NDEF records, full hex memory dump — see exactly what's on any NTAG, Mifare or NFC Forum tag.",
            R.drawable.ic_read
        ),
        Slide(
            "Write anything",
            "URLs, business cards, Wi-Fi credentials, contacts, custom MIME data — multi-record messages with optional lock and password.",
            R.drawable.ic_write
        ),
        Slide(
            "Built for production",
            "Bulk write from CSV/XLSX, auto-incrementing counters, templates, history, verify and clone — go from 1 tag to 1,000 with no extra typing.",
            R.drawable.ic_other
        )
    )

    private inner class SlidesAdapter(val slides: List<Slide>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<SlideHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(48), dp(32), dp(32))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return SlideHolder(container)
        }

        override fun getItemCount() = slides.size

        override fun onBindViewHolder(holder: SlideHolder, position: Int) {
            val slide = slides[position]
            val v = holder.itemView as LinearLayout
            v.removeAllViews()

            if (slide.showLogo) {
                val logo = ImageView(v.context).apply {
                    setImageResource(R.drawable.identium_logo)
                    setBackgroundResource(R.drawable.bg_logo_square)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                v.addView(logo, LinearLayout.LayoutParams(dp(80), dp(80)))
            } else {
                val icon = ImageView(v.context).apply {
                    setImageResource(slide.iconRes)
                    setColorFilter(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.bg_pulse_ring)
                    setPadding(dp(20), dp(20), dp(20), dp(20))
                }
                v.addView(icon, LinearLayout.LayoutParams(dp(120), dp(120)))
            }

            v.addView(TextView(v.context).apply {
                text = slide.title
                textSize = 26f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, 0)
            })

            v.addView(TextView(v.context).apply {
                text = slide.body
                textSize = 16f
                setTextColor(0xFFB6BAD0.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
                setLineSpacing(0f, 1.3f)
            })
        }
    }

    private inner class SlideHolder(view: View) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

    companion object {
        private const val PREFS = "identium_onboarding"
        private const val KEY_SEEN = "seen"

        fun shouldShow(ctx: Context): Boolean =
            !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)
    }
}
