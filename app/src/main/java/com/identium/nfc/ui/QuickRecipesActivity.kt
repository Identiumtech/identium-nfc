package com.identium.nfc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.identium.nfc.MainActivity
import com.identium.nfc.R
import com.identium.nfc.data.Profile
import com.identium.nfc.databinding.ItemActionCardBinding
import com.identium.nfc.nfc.WifiAuth
import com.identium.nfc.nfc.WifiEnc
import com.identium.nfc.nfc.WriteRecord
import com.identium.nfc.util.toQrText

/**
 * Pre-built recipe pack — taps fill the Write tab queue with sensible
 * defaults so the customer can write a useful tag in seconds.
 *
 * Recipes that depend on user details (vCard, contact handover) read from
 * the saved Profile. If the profile is empty we route the user there first
 * instead of writing junk.
 */
class QuickRecipesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Quick recipes"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        val explanation = TextView(this).apply {
            text = "Pick a recipe — we'll pre-fill the Write screen with the right records. " +
                    "Edit anything before tapping a tag."
            setTextColor(getColor(R.color.text_secondary))
        }
        root.addView(explanation, lp().apply { bottomMargin = dp(14) })

        for (recipe in recipes) {
            val card = ItemActionCardBinding.inflate(LayoutInflater.from(this), root, false)
            card.actionTitle.text = recipe.title
            card.actionSubtitle.text = recipe.subtitle
            card.actionIcon.setImageResource(recipe.iconRes)
            card.root.setOnClickListener { applyRecipe(recipe) }
            root.addView(card.root)
        }

        setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun applyRecipe(recipe: Recipe) {
        val needsProfile = recipe.id in setOf(R_VCARD, R_CONTACT_HANDOVER, R_LANDING)
        if (needsProfile && !Profile.isFilled(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Fill your profile first")
                .setMessage("This recipe uses details from your business card. Set them once and every recipe picks them up automatically.")
                .setPositiveButton("Open profile") { _, _ ->
                    startActivity(Intent(this, ProfileActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val records = recipe.build(Profile.load(this))
        // Two outputs: program an NFC tag or show a QR code.
        MaterialAlertDialogBuilder(this)
            .setTitle(recipe.title)
            .setMessage("Where do you want this data?")
            .setPositiveButton("Write to NFC tag") { _, _ ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_LOAD_RECORDS, ArrayList(records))
                    putExtra(MainActivity.EXTRA_OPEN_TAB, R.id.tab_write)
                }
                startActivity(intent)
                Toast.makeText(this, "Loaded ${records.size} record(s) — review and tap Write", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNeutralButton("Show as QR") { _, _ ->
                val payload = records.first().toQrText()
                startActivity(QrCodeActivity.intent(this, payload,
                    label = "${recipe.title} — ${records.first().summary}"))
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    data class Recipe(
        val id: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val build: (Profile.Card) -> List<WriteRecord>
    )

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val R_WIFI_GUEST = "wifi_guest"
        const val R_VCARD = "vcard"
        const val R_RESTAURANT_MENU = "menu"
        const val R_PRODUCT_INFO = "product_info"
        const val R_ASSET_TAG = "asset_tag"
        const val R_LANDING = "landing"
        const val R_CONTACT_HANDOVER = "contact"
        const val R_REVIEW_LINK = "review"
        const val R_EVENT_CHECKIN = "event_checkin"
        const val R_DOC_LINK = "doc"

        private val recipes: List<Recipe> = listOf(
            Recipe(
                R_VCARD,
                "Business card",
                "vCard built from your saved profile — instant share-on-tap",
                android.R.drawable.ic_menu_myplaces
            ) { profile ->
                listOf(
                    WriteRecord.Vcard(
                        fullName = profile.fullName.ifBlank { "Identium Team" },
                        organization = profile.company,
                        titleField = profile.title,
                        phone = profile.phone,
                        email = profile.email,
                        website = profile.website,
                        address = profile.address,
                        note = profile.note
                    )
                )
            },
            Recipe(
                R_WIFI_GUEST,
                "Wi-Fi guest network",
                "Configurable WPA2 Wi-Fi tag — tap to connect, no typing",
                android.R.drawable.ic_menu_share
            ) { _ ->
                listOf(
                    WriteRecord.Wifi(
                        ssid = "Guest WiFi",
                        password = "welcome123",
                        auth = WifiAuth.WPA2_PSK.name,
                        enc = WifiEnc.AES.name,
                        hidden = false
                    )
                )
            },
            Recipe(
                R_RESTAURANT_MENU,
                "Restaurant table card",
                "URL to your menu — counter inserts the table number on each tap",
                android.R.drawable.ic_menu_compass
            ) { profile ->
                listOf(
                    WriteRecord.Url("${profile.website.ifBlank { "https://identium.in" }}/menu?table={n}")
                )
            },
            Recipe(
                R_PRODUCT_INFO,
                "Product info link",
                "Open a product/SKU page — auto-counter for serial numbers",
                android.R.drawable.ic_menu_info_details
            ) { profile ->
                listOf(
                    WriteRecord.Url("${profile.website.ifBlank { "https://identium.in" }}/products/{n}")
                )
            },
            Recipe(
                R_ASSET_TAG,
                "Asset / inventory tag",
                "Plain text serial number with auto-counter — locked layout",
                android.R.drawable.ic_menu_save
            ) { _ ->
                listOf(
                    WriteRecord.Text("ASSET-{n}"),
                    WriteRecord.CustomMime("application/com.identium.asset", "{n}")
                )
            },
            Recipe(
                R_LANDING,
                "Landing page",
                "Your company URL — fastest possible tap-to-open",
                android.R.drawable.ic_menu_send
            ) { profile ->
                listOf(WriteRecord.Url(profile.website.ifBlank { "https://identium.in" }))
            },
            Recipe(
                R_CONTACT_HANDOVER,
                "Click-to-call",
                "Tap to dial your phone number directly",
                android.R.drawable.ic_menu_call
            ) { profile ->
                listOf(WriteRecord.Phone(profile.phone.ifBlank { "+919999999999" }))
            },
            Recipe(
                R_REVIEW_LINK,
                "Review request",
                "Pre-typed SMS asking for a review — tap and send",
                android.R.drawable.ic_dialog_dialer
            ) { profile ->
                listOf(WriteRecord.Sms(profile.phone.ifBlank { "+919999999999" },
                    "Hi! Thanks for choosing ${profile.company.ifBlank { "Identium" }}. Mind sharing a quick review? ${profile.website}"))
            },
            Recipe(
                R_EVENT_CHECKIN,
                "Event check-in",
                "Open a check-in URL with the attendee number from {n}",
                android.R.drawable.ic_menu_my_calendar
            ) { profile ->
                listOf(
                    WriteRecord.Url("${profile.website.ifBlank { "https://identium.in" }}/event/checkin?id={n}")
                )
            },
            Recipe(
                R_DOC_LINK,
                "Document / datasheet",
                "Open a PDF / docs URL — handy for product datasheets in the box",
                android.R.drawable.ic_menu_agenda
            ) { profile ->
                listOf(
                    WriteRecord.Url("${profile.website.ifBlank { "https://identium.in" }}/docs/{n}")
                )
            }
        )
    }
}
