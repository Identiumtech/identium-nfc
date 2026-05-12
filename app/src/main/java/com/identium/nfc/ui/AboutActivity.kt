package com.identium.nfc.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.identium.nfc.R

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "About Identium"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // Header card with logo on white square
        val headerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundResource(R.drawable.bg_brand_header)
        }

        val logoFrame = androidx.appcompat.widget.AppCompatImageView(this).apply {
            setImageResource(R.drawable.identium_logo)
            setBackgroundResource(R.drawable.bg_logo_square)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        headerWrap.addView(logoFrame, LinearLayout.LayoutParams(dp(72), dp(72)))

        headerWrap.addView(TextView(this).apply {
            text = "Identium NFC"
            setTextColor(getColor(R.color.white))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        })
        headerWrap.addView(TextView(this).apply {
            text = "Identium Tech Solutions Pvt Ltd"
            setTextColor(0xFFB6BAD0.toInt())
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "1.0" }
        headerWrap.addView(TextView(this).apply {
            text = "Version $versionName  •  free to Identium customers"
            setTextColor(0xFFB6BAD0.toInt())
            textSize = 11f
            letterSpacing = 0.06f
            setPadding(0, dp(6), 0, 0)
        })

        root.addView(headerWrap, lp().apply { setMargins(0, 0, 0, dp(20)) })

        root.addView(section("What we make"))
        root.addView(body("Identium designs and manufactures RFID readers, NFC tags, and end-to-end RFID solutions. " +
                "This app is bundled free with every NFC tag we sell — read, write, lock, password-protect, " +
                "verify and bulk-program any NTAG21x or Mifare tag in seconds."))

        root.addView(section("Get in touch"))
        contactRow("Website", "identium.in", "https://identium.in")?.let { root.addView(it) }
        contactRow("Email", "info@identium.in", "mailto:info@identium.in")?.let { root.addView(it) }
        contactRow("Phone", "011-47147839", "tel:01147147839")?.let { root.addView(it) }
        contactRow("Mobile", "+91 70110 01472", "tel:+917011001472")?.let { root.addView(it) }

        root.addView(section("Visit us").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })
        root.addView(body("Plot No. 5, First Floor, Santnagar,\nEast of Kailash,\nNew Delhi – 110065"))

        val mapBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open in Maps"
            setOnClickListener {
                val q = Uri.encode("Identium Tech Solutions Plot 5 Santnagar East of Kailash New Delhi 110065")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
            }
        }
        root.addView(mapBtn, lp().apply { topMargin = dp(12) })

        root.addView(section("Credits").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })
        root.addView(body("Built on the Android NFC stack and Material 3 design. " +
                "Supports NTAG213 / 215 / 216, Mifare Ultralight, Mifare Classic, and NFC Forum Type 4."))

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun contactRow(label: String, value: String, uri: String?): android.view.View? {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_card_outlined)
            isClickable = uri != null
            isFocusable = uri != null
        }
        container.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(TextView(this).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.brand_blue))
        })
        if (uri != null) {
            container.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (_: Exception) { }
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        container.layoutParams = params
        return container
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.brand_blue))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(6) }
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(getColor(R.color.text_primary))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
