# Identium NFC

A complete NFC tag toolkit, distributed free with every Identium NFC tag.

> Read, write, lock, password-protect, copy, clone, verify, and bulk-program
> NTAG21x, Mifare Ultralight, Mifare Classic and NFC Forum Type 4 tags from
> any Android 5.0+ device.

Built by [Identium Tech Solutions Pvt Ltd](https://identium.in).

---

## Features

### Tag operations
- **Read** — UID, ATQA, SAK, technologies, NDEF records, full hex memory dump (FAST_READ for NTAG21x, READ fallback)
- **Write** — 12 record types: URL, Text, vCard, Wi-Fi (WSC), Bluetooth (OOB), Email, Phone, SMS, Geo, Address, Android App, Custom MIME
- **Multi-record messages** with optional "lock after write"
- **Erase** — single empty NDEF record
- **Format** — NDEF Formatable tags
- **Make read-only** — Android's standard call with NTAG static + dynamic lock-byte fallback
- **Set / remove password** — NTAG213/215/216 PWD_AUTH with configurable AUTH0 (protect-from-page)
- **Copy tag** — NDEF-level (cross chip type)
- **Clone tag** — raw user-memory clone (same chip type, byte-for-byte)
- **Verify** — read and compare against expected URL/text

### Production workflow
- **Bulk import** from CSV or XLSX — sequential one-tag-per-tap writing
- **Auto-counter** — replace `{n}` in records with auto-incrementing serial, padded
- **Templates** — save the current write queue, reload later
- **Tag history** — 200-entry log with CSV export
- **Statistics dashboard** — operation breakdown, last-7-days chart, top record types
- **Quick recipes** — pre-built tag templates (Wi-Fi guest, vCard, asset tag, restaurant menu URL, event check-in, …)
- **Identium profile** — store your business card once, auto-fill every vCard / contact tag

### Tools
- **NFC diagnostic** — phone capability check + tag probe
- **Backup & restore** — single JSON snapshot of profile, templates, history, counter
- **Onboarding** — 4-slide welcome carousel on first launch
- **Tasks tab** — one-tap recipes for URL, dial, SMS, email, Wi-Fi, Bluetooth, app launch

### UX
- **Branded sonar animation** on every "tap a tag" prompt — concentric ring pulse + logo pulse
- **Big success dialog** with vibration on every successful write
- Material 3 with the Identium brand gradient (dark → darkblue → blue)
- Adaptive launcher icon + raster fallbacks for legacy launchers
- Dark mode

---

## Build

Requirements:
- JDK 17
- Android SDK 34 + build-tools 34.0.0
- Gradle 8.8 (wrapper included)
- AGP 8.2.1, Kotlin 1.9.22

```bash
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` should set `sdk.dir=/path/to/Android/sdk`.

---

## Architecture

- **MainActivity** — bottom-nav host for Read / Write / Other / Tasks fragments. Owns NFC foreground dispatch for everything inside the activity.
- **BaseNfcActivity** — self-contained NFC dispatcher used by Password / Copy / Clone / Import / Verify / Diagnostic. Each child activity owns its own foreground dispatch so taps land on the screen the user is looking at.
- **NfcViewModel** — shared between MainActivity and its fragments. Wraps results in `Event<T>` so tab switches don't re-toast stale outcomes.
- **NDEF helpers** — `NdefBuilder` constructs records, `Ntag21x` drives the chip-specific raw commands (READ, FAST_READ, WRITE, PWD_AUTH, lock bytes), `TagOperations` glues it all together.
- **Data stores** — `History`, `Templates`, `Counter`, `Profile`, `Backup` all back onto SharedPreferences with JSON / Base64 encoding (no Room / DataStore dependency).
- **CSV / XLSX importer** — no Apache POI; XLSX parsed by extracting `xl/sharedStrings.xml` + `xl/worksheets/sheet1.xml` directly from the zip and feeding them through the platform SAX parser.

---

## Permissions

- `NFC` — required for all tag operations
- `VIBRATE` — haptic feedback on success
- `CALL_PHONE` / `SEND_SMS` — for task-tags that auto-trigger dialer / SMS app on read
- `BLUETOOTH`, `WIFI` (state + change) — declared but only used by tasks that write Wi-Fi / Bluetooth handover records

No camera, storage, location, or internet at runtime.

---

## Contact

- **Website**: https://identium.in
- **Email**: info@identium.in
- **Phone**: 011-47147839 / +91 70110 01472
- **Address**: Plot No. 5, First Floor, Santnagar, East of Kailash, New Delhi – 110065
