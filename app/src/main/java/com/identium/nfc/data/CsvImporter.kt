package com.identium.nfc.data

import android.content.Context
import android.net.Uri
import com.identium.nfc.nfc.WriteRecord
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads a CSV or XLSX file from a content URI and turns each row into one
 * or more [WriteRecord]s.
 *
 * Detection is done by sniffing the first bytes of the stream rather than
 * relying on filename — content URIs from many providers don't surface the
 * extension, and trying to parse XLSX as CSV gives a useless "parse failed"
 * error. ZIP magic (`PK\x03\x04`) means XLSX; anything else is CSV.
 *
 * Supported columns (case-insensitive headers):
 *   url, text
 *   email [+ email_subject, email_body]
 *   phone
 *   sms_number, sms_body
 *   ssid, wifi_password, wifi_auth, wifi_enc, wifi_hidden
 *   vcard_name, vcard_company, vcard_title, vcard_phone, vcard_email,
 *     vcard_website, vcard_address, vcard_note
 *   lat, lon, label   (geolocation)
 *   app_package
 *   mime, mime_payload
 *
 * Each non-empty group on a row produces one record; rows with no
 * recognizable content are skipped.
 */
object CsvImporter {

    private const val UTF8_BOM = '﻿'

    fun importFromUri(context: Context, uri: Uri): List<ImportedRow> {
        val mime = context.contentResolver.getType(uri)
        val name = queryDisplayName(context, uri)
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open file (provider returned null stream)")

        if (raw.isEmpty()) throw IllegalStateException("File is empty")

        val format = detectFormat(raw, name, mime)
        return when (format) {
            Format.XLSX -> parseXlsx(ByteArrayInputStream(raw))
            Format.CSV -> parseCsv(ByteArrayInputStream(raw))
        }
    }

    private fun detectFormat(bytes: ByteArray, name: String?, mime: String?): Format {
        // ZIP magic bytes — XLSX is a zip container.
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())) {
            return Format.XLSX
        }
        if (name?.endsWith(".xlsx", ignoreCase = true) == true) return Format.XLSX
        if (mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") return Format.XLSX
        return Format.CSV
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    fun parseCsv(input: InputStream): List<ImportedRow> {
        BufferedReader(InputStreamReader(BufferedInputStream(input), Charsets.UTF_8)).use { reader ->
            val rows = mutableListOf<List<String>>()
            for ((idx, rawLine) in reader.lineSequence().withIndex()) {
                val line = if (idx == 0) rawLine.trimStart(UTF8_BOM) else rawLine
                if (line.isBlank()) continue
                rows += splitCsvLine(line)
            }
            if (rows.isEmpty()) throw IllegalStateException("CSV file has no rows")
            return rowsToRecords(rows)
        }
    }

    /**
     * XLSX is a zip with shared strings + sheet XML. The two entries can
     * appear in any order in the zip — earlier code assumed sharedStrings
     * came first and silently produced empty cells when sheet1.xml came
     * first. We now buffer both into memory then parse.
     */
    fun parseXlsx(input: InputStream): List<ImportedRow> {
        var sharedStringsXml: ByteArray? = null
        var sheetXml: ByteArray? = null
        val zip = ZipInputStream(BufferedInputStream(input))
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name.lowercase()
            when {
                name == "xl/sharedstrings.xml" -> sharedStringsXml = zip.readBytes()
                // Use the first worksheet found. XLSX files vary in
                // capitalization and may have sheet1.xml, Sheet1.xml,
                // worksheets/sheet1.xml etc.
                name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") && sheetXml == null ->
                    sheetXml = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        zip.close()

        if (sheetXml == null) throw IllegalStateException("XLSX has no worksheet (xl/worksheets/sheet*.xml not found)")

        val sharedStrings = sharedStringsXml?.let { parseSharedStrings(ByteArrayInputStream(it)) } ?: emptyList()
        val rows = parseSheet(ByteArrayInputStream(sheetXml), sharedStrings)
        if (rows.isEmpty()) throw IllegalStateException("XLSX has no rows on the first sheet")
        return rowsToRecords(rows)
    }

    private fun parseSharedStrings(stream: InputStream): List<String> {
        val out = mutableListOf<String>()
        val handler = object : DefaultHandler() {
            var inText = false
            val current = StringBuilder()
            override fun startElement(uri: String?, lname: String?, qname: String?, atts: Attributes?) {
                if (qname == "t") { inText = true; current.setLength(0) }
                if (qname == "si") current.setLength(0)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.append(ch, start, length)
            }
            override fun endElement(uri: String?, lname: String?, qname: String?) {
                if (qname == "t") inText = false
                if (qname == "si") out += current.toString()
            }
        }
        SAXParserFactory.newInstance().newSAXParser().parse(stream, handler)
        return out
    }

    private fun parseSheet(stream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val handler = object : DefaultHandler() {
            var currentRow: MutableList<String> = mutableListOf()
            var currentValue = StringBuilder()
            var currentType: String? = null
            var inValue = false
            override fun startElement(uri: String?, lname: String?, qname: String?, atts: Attributes?) {
                when (qname) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> currentType = atts?.getValue("t")
                    "v", "t" -> { inValue = true; currentValue.setLength(0) }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) currentValue.append(ch, start, length)
            }
            override fun endElement(uri: String?, lname: String?, qname: String?) {
                when (qname) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = currentValue.toString()
                        val resolved = if (currentType == "s") {
                            val idx = raw.toIntOrNull()
                            if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else raw
                        } else raw
                        currentRow += resolved
                        currentValue.setLength(0)
                        currentType = null
                    }
                    "row" -> rows += currentRow
                }
            }
        }
        SAXParserFactory.newInstance().newSAXParser().parse(stream, handler)
        return rows
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                    else inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun rowsToRecords(rows: List<List<String>>): List<ImportedRow> {
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().trimStart(UTF8_BOM).lowercase() }
        val data = rows.drop(1)
        val out = mutableListOf<ImportedRow>()
        for ((idx, raw) in data.withIndex()) {
            val cells = raw.toMutableList()
            while (cells.size < header.size) cells += ""
            val map = header.zip(cells.map { it.trim() }).toMap()
            val records = buildRecords(map)
            if (records.isEmpty()) continue
            out += ImportedRow(
                lineNumber = idx + 2,
                source = map.values.firstOrNull { it.isNotBlank() } ?: "row ${idx + 2}",
                records = records
            )
        }
        if (out.isEmpty()) {
            val sample = header.joinToString(", ").ifEmpty { "(no header detected)" }
            throw IllegalStateException(
                "No recognised columns found.\nHeader was: [$sample]\nSee the 'Show expected columns' button for the supported names."
            )
        }
        return out
    }

    private fun buildRecords(row: Map<String, String>): List<WriteRecord> {
        val records = mutableListOf<WriteRecord>()
        row["url"]?.takeIf { it.isNotBlank() }?.let { records += WriteRecord.Url(it) }
        row["text"]?.takeIf { it.isNotBlank() }?.let { records += WriteRecord.Text(it) }
        row["email"]?.takeIf { it.isNotBlank() }?.let {
            records += WriteRecord.Email(it, row["email_subject"].orEmpty(), row["email_body"].orEmpty())
        }
        row["phone"]?.takeIf { it.isNotBlank() }?.let { records += WriteRecord.Phone(it) }
        row["sms_number"]?.takeIf { it.isNotBlank() }?.let {
            records += WriteRecord.Sms(it, row["sms_body"].orEmpty())
        }
        val lat = row["lat"]?.toDoubleOrNull()
        val lon = row["lon"]?.toDoubleOrNull()
        if (lat != null && lon != null) {
            records += WriteRecord.Geo(lat, lon, row["label"]?.takeIf { it.isNotBlank() })
        }
        row["ssid"]?.takeIf { it.isNotBlank() }?.let { ssid ->
            val auth = row["wifi_auth"]?.uppercase()?.replace("-", "_") ?: "WPA2_PSK"
            val enc = row["wifi_enc"]?.uppercase()?.replace("-", "_") ?: "AES"
            records += WriteRecord.Wifi(
                ssid,
                row["wifi_password"].orEmpty(),
                auth.takeIf { isValidEnum<com.identium.nfc.nfc.WifiAuth>(it) } ?: "WPA2_PSK",
                enc.takeIf { isValidEnum<com.identium.nfc.nfc.WifiEnc>(it) } ?: "AES",
                row["wifi_hidden"]?.lowercase() in listOf("1", "true", "yes")
            )
        }
        row["vcard_name"]?.takeIf { it.isNotBlank() }?.let { name ->
            records += WriteRecord.Vcard(
                name,
                row["vcard_company"].orEmpty(),
                row["vcard_title"].orEmpty(),
                row["vcard_phone"].orEmpty(),
                row["vcard_email"].orEmpty(),
                row["vcard_website"].orEmpty(),
                row["vcard_address"].orEmpty(),
                row["vcard_note"].orEmpty()
            )
        }
        row["app_package"]?.takeIf { it.isNotBlank() }?.let { records += WriteRecord.App(it) }
        row["mime"]?.takeIf { it.isNotBlank() }?.let { mime ->
            records += WriteRecord.CustomMime(mime, row["mime_payload"].orEmpty())
        }
        return records
    }

    private inline fun <reified T : Enum<T>> isValidEnum(value: String): Boolean =
        enumValues<T>().any { it.name == value }

    private enum class Format { CSV, XLSX }
}

data class ImportedRow(
    val lineNumber: Int,
    val source: String,
    val records: List<WriteRecord>
)
