package com.identium.nfc.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.identium.nfc.R
import com.identium.nfc.util.QrEncoder
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a QR code for a single piece of payload data. The matrix is built
 * in pure Kotlin by [QrEncoder] — no external libraries — and rasterised
 * to a Bitmap at module-pixel precision.
 *
 * Customers who don't deploy NFC tags can print the QR instead and serve
 * the same data; the encoding for URL / Wi-Fi / vCard / phone / etc.
 * matches what every iOS and Android camera recognises natively.
 */
class QrCodeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "QR code"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val payload = intent.getStringExtra(EXTRA_PAYLOAD).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        if (payload.isBlank()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val qr = try {
            QrEncoder.encode(payload, pickEccForSize(payload))
        } catch (e: Exception) {
            TextView(this).apply {
                text = "Could not encode: ${e.message ?: "payload too long"}"
                setPadding(dp(20), dp(40), dp(20), dp(20))
                setTextColor(getColor(R.color.error))
            }.also { root.addView(it) }
            setContentView(root)
            return
        }

        // Card surrounding the QR — white tile, soft border, matches CRM cards.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_outlined)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Brand header inside the card
        card.addView(brandStrip())

        val qrBitmap = qr.toBitmap(scale = 12, border = 2)
        val qrView = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        card.addView(qrView, LinearLayout.LayoutParams(dp(280), dp(280)).apply {
            topMargin = dp(16)
        })

        // Caption — what's inside the QR
        val captionView = TextView(this).apply {
            text = label.ifBlank { payload.take(120) + if (payload.length > 120) "…" else "" }
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), 0)
        }
        card.addView(captionView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tech = TextView(this).apply {
            text = "v${qr.version} · ${qr.ecc.name.lowercase().replaceFirstChar { it.uppercase() }} ECC · ${qr.size}×${qr.size} modules"
            setTextColor(getColor(R.color.text_tertiary))
            textSize = 11f
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(tech, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Action buttons
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(button("Share image") { share(qrBitmap, label.ifBlank { payload.take(40) }) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(outlinedButton("Save to gallery") { saveToGallery(qrBitmap) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Payload preview (selectable, for verification)
        val payloadView = TextView(this).apply {
            text = payload
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_card_outlined)
        }
        root.addView(payloadView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun pickEccForSize(payload: String): QrEncoder.Ecc {
        // Higher EC = more robust scanning but smaller capacity. Pick the
        // strongest EC that still fits comfortably.
        val len = payload.toByteArray(Charsets.UTF_8).size
        return when {
            len <= 80 -> QrEncoder.Ecc.HIGH      // small payload, max robustness
            len <= 200 -> QrEncoder.Ecc.QUARTILE
            len <= 500 -> QrEncoder.Ecc.MEDIUM
            else -> QrEncoder.Ecc.LOW
        }
    }

    private fun brandStrip(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.identium_logo)
            setBackgroundResource(R.drawable.bg_logo_square)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(28), dp(28)))
        val text = TextView(this).apply {
            this.text = "IDENTIUM NFC"
            setTextColor(getColor(R.color.brand_blue))
            textSize = 11f
            letterSpacing = 0.16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(text)
        return row
    }

    // ---- share / save ----

    private fun share(bitmap: Bitmap, label: String) {
        try {
            val file = File(cacheDir, "identium-qr-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Identium NFC QR — $label")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share QR code"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Share failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(bitmap: Bitmap) {
        try {
            val name = "Identium-QR-${System.currentTimeMillis()}.png"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Identium NFC")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("MediaStore refused the insert")
                contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: throw java.io.IOException("Could not open output")
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Identium NFC")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaStore.Images.Media.insertImage(contentResolver, file.absolutePath, name, "Identium NFC QR")
            }
            android.widget.Toast.makeText(this, "Saved to Pictures / Identium NFC", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun button(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun outlinedButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_LABEL = "label"

        fun intent(ctx: Context, payload: String, label: String? = null): Intent =
            Intent(ctx, QrCodeActivity::class.java).apply {
                putExtra(EXTRA_PAYLOAD, payload)
                putExtra(EXTRA_LABEL, label.orEmpty())
            }
    }
}
