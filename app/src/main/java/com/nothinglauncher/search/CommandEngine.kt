package com.nothinglauncher.search

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nothinglauncher.HardwareControlRepository
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.settings.NothingSettingsActivity
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlin.math.pow

/**
 * 100% offline Command Bar engine + fuzzy/acronym app matcher.
 * Evaluates math & percentages, unit/temperature conversions, timezone lookups,
 * device quick toggles, optional contacts, and clean web search actions.
 */
object CommandEngine {

    enum class Kind { ANSWER, ACTION, CONTACT, WEB }

    data class CommandResult(
        val kind: Kind,
        val badge: String,
        val title: String,
        val subtitle: String,
        val copyValue: String? = null,
        val action: (Context) -> Unit
    )

    private val NUM_FMT = DecimalFormat("#,##0.####")

    fun evaluate(context: Context, rawQuery: String): List<CommandResult> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<CommandResult>(4)

        evalMath(q)?.let { ans ->
            out += CommandResult(
                kind = Kind.ANSWER,
                badge = "CALC",
                title = ans,
                subtitle = "Tap to copy result",
                copyValue = ans
            ) { ctx -> copyToClipboard(ctx, ans) }
        }

        evalConversion(q)?.let { (res, detail) ->
            out += CommandResult(
                kind = Kind.ANSWER,
                badge = "UNIT",
                title = res,
                subtitle = "$detail · Tap to copy",
                copyValue = res
            ) { ctx -> copyToClipboard(ctx, res) }
        }

        evalTimezone(q)?.let { (timeText, zoneDetail) ->
            out += CommandResult(
                kind = Kind.ANSWER,
                badge = "TIME",
                title = timeText,
                subtitle = zoneDetail
            ) { ctx -> copyToClipboard(ctx, timeText) }
        }

        evalQuickAction(context, q)?.let { out += it }
        evalContacts(context, q, out)

        if (q.length >= 2) {
            out += CommandResult(
                kind = Kind.WEB,
                badge = "WEB",
                title = "Search web for \"$q\"",
                subtitle = "Open in default browser"
            ) { ctx ->
                val url = "https://duckduckgo.com/?q=" + Uri.encode(q)
                Intents.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        return out
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Nothing Command", text))
            Toast.makeText(context, context.getString(R.string.copied_fmt, text), Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }

    private fun evalQuickAction(context: Context, q: String): CommandResult? {
        val low = q.lowercase(Locale.US)
        return when {
            low == "torch" || low == "flashlight" || low == "flash" -> CommandResult(
                Kind.ACTION, "CMD",
                if (HardwareControlRepository.torchOn) "Turn Flashlight Off" else "Turn Flashlight On",
                "Hardware torch toggle"
            ) { HardwareControlRepository.toggleTorch(it) }
            low == "focus" || low == "focus mode" -> CommandResult(
                Kind.ACTION, "CMD",
                if (NothingPrefs.get(context).focusMode) "Exit Essential Focus Mode" else "Enter Essential Focus Mode",
                "Monochrome low-distraction home"
            ) {
                val p = NothingPrefs.get(it)
                p.focusMode = !p.focusMode
            }
            low == "caffeine" || low == "awake" -> CommandResult(
                Kind.ACTION, "CMD",
                if (NothingPrefs.get(context).caffeineEnabled) "Disable Caffeine (Screen Awake)" else "Enable Caffeine (Keep Screen On)",
                "Home screen display lock"
            ) {
                val p = NothingPrefs.get(it)
                p.caffeineEnabled = !p.caffeineEnabled
            }
            low == "wifi" || low == "wi-fi" -> CommandResult(
                Kind.ACTION, "SYS", "Open Wi-Fi Settings", "Network & internet"
            ) { Intents.start(it, Intent(Settings.ACTION_WIFI_SETTINGS)) }
            low == "bt" || low == "bluetooth" -> CommandResult(
                Kind.ACTION, "SYS", "Open Bluetooth Settings", "Connected devices"
            ) { Intents.start(it, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            low == "wallpaper" || low == "wall" -> CommandResult(
                Kind.ACTION, "SYS", "Choose Wallpaper", "System wallpaper picker"
            ) { Intents.openWallpaperPicker(it) }
            low == "settings" || low == "launcher settings" || low == "config" -> CommandResult(
                Kind.ACTION, "SYS", "Not a Thing Settings", "Grid, icon style, gestures, quality"
            ) { Intents.start(it, Intent(it, NothingSettingsActivity::class.java)) }
            else -> null
        }
    }

    private fun evalContacts(context: Context, q: String, out: MutableList<CommandResult>) {
        if (q.length < 2) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                var added = 0
                while (c.moveToNext() && added < 2) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val num = c.getString(1)?.trim().orEmpty()
                    if (name.isEmpty() || num.isEmpty()) continue
                    added++
                    out += CommandResult(
                        Kind.CONTACT, "CALL", name, num
                    ) { ctx ->
                        Intents.start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // --- Timezones ---

    private val ZONE_ALIASES = mapOf(
        "utc" to "UTC", "gmt" to "GMT", "london" to "Europe/London", "uk" to "Europe/London",
        "nyc" to "America/New_York", "ny" to "America/New_York", "est" to "America/New_York",
        "sf" to "America/Los_Angeles", "la" to "America/Los_Angeles", "pst" to "America/Los_Angeles",
        "tokyo" to "Asia/Tokyo", "jst" to "Asia/Tokyo",
        "india" to "Asia/Kolkata", "ist" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata", "delhi" to "Asia/Kolkata",
        "paris" to "Europe/Paris", "berlin" to "Europe/Berlin", "cet" to "Europe/Berlin",
        "dubai" to "Asia/Dubai", "singapore" to "Asia/Singapore", "sydney" to "Australia/Sydney"
    )

    internal fun evalTimezone(q: String): Pair<String, String>? {
        val low = q.trim().lowercase(Locale.US)
        if (!low.startsWith("time ")) return null
        val target = low.removePrefix("time ").trim()
        val zoneId = ZONE_ALIASES[target] ?: TimeZone.getAvailableIDs()
            .firstOrNull { it.substringAfterLast('/').replace('_', ' ').equals(target, ignoreCase = true) }
            ?: return null
        val tz = TimeZone.getTimeZone(zoneId)
        val fmt = SimpleDateFormat("HH:mm · EEE, MMM d", Locale.US).apply { timeZone = tz }
        return fmt.format(Date()) to tz.id.replace('_', ' ')
    }

    // --- Unit conversions ---

    private val CONV_RE = Regex(
        """^\s*(-?\d+(?:\.\d+)?)\s*([a-zA-Z]+)\s+(?:to|in)\s+([a-zA-Z]+)\s*$""",
        RegexOption.IGNORE_CASE
    )

    internal fun evalConversion(q: String): Pair<String, String>? {
        val m = CONV_RE.matchEntire(q) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2].lowercase(Locale.US)
        val to = m.groupValues[3].lowercase(Locale.US)

        if ((from == "c" || from == "celsius") && (to == "f" || to == "fahrenheit")) {
            val out = value * 9.0 / 5.0 + 32.0
            return "${NUM_FMT.format(out)} °F" to "${NUM_FMT.format(value)} °C"
        }
        if ((from == "f" || from == "fahrenheit") && (to == "c" || to == "celsius")) {
            val out = (value - 32.0) * 5.0 / 9.0
            return "${NUM_FMT.format(out)} °C" to "${NUM_FMT.format(value)} °F"
        }

        val lengthMeters = mapOf(
            "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
            "mi" to 1609.344, "ft" to 0.3048, "in" to 0.0254, "yd" to 0.9144
        )
        if (from in lengthMeters && to in lengthMeters) {
            val out = value * lengthMeters.getValue(from) / lengthMeters.getValue(to)
            return "${NUM_FMT.format(out)} $to" to "${NUM_FMT.format(value)} $from"
        }

        val massGrams = mapOf(
            "g" to 1.0, "kg" to 1000.0, "lb" to 453.59237, "lbs" to 453.59237, "oz" to 28.349523125
        )
        if (from in massGrams && to in massGrams) {
            val out = value * massGrams.getValue(from) / massGrams.getValue(to)
            return "${NUM_FMT.format(out)} $to" to "${NUM_FMT.format(value)} $from"
        }

        val dataMb = mapOf("kb" to 1.0 / 1024.0, "mb" to 1.0, "gb" to 1024.0, "tb" to 1024.0 * 1024.0)
        if (from in dataMb && to in dataMb) {
            val out = value * dataMb.getValue(from) / dataMb.getValue(to)
            return "${NUM_FMT.format(out)} ${to.uppercase(Locale.US)}" to "${NUM_FMT.format(value)} ${from.uppercase(Locale.US)}"
        }
        return null
    }

    // --- Math & percentage recursive-descent evaluator ---

    private val PCT_OF_RE = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*%\s*of\s*(-?\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)

    internal fun evalMath(q: String): String? {
        PCT_OF_RE.matchEntire(q)?.let { m ->
            val pct = m.groupValues[1].toDoubleOrNull() ?: return null
            val base = m.groupValues[2].toDoubleOrNull() ?: return null
            return NUM_FMT.format((pct / 100.0) * base)
        }
        val clean = q.replace(" ", "")
        if (clean.all { it.isDigit() || it == '.' }) return null // plain number isn't a math expression
        if (!clean.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '^' }) return null
        return try {
            val parser = ExprParser(clean)
            val result = parser.parse()
            if (!parser.atEnd() || result.isNaN() || result.isInfinite()) null
            else NUM_FMT.format(result)
        } catch (_: Throwable) {
            null
        }
    }

    private class ExprParser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length

        fun parse(): Double = parseAddSub()

        private fun parseAddSub(): Double {
            var v = parseMulDiv()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val r = parseMulDiv()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun parseMulDiv(): Double {
            var v = parsePow()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]
                val r = parsePow()
                if (op == '/' && r == 0.0) return Double.NaN
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        private fun parsePow(): Double {
            var v = parseUnary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                val exp = parsePow()
                v = v.pow(exp)
            }
            return v
        }

        private fun parseUnary(): Double = when {
            pos < s.length && s[pos] == '-' -> { pos++; -parseUnary() }
            pos < s.length && s[pos] == '+' -> { pos++; parseUnary() }
            else -> parseAtom()
        }

        private fun parseAtom(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = parseAddSub()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Unclosed (")
                pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Unexpected char")
            return s.substring(start, pos).toDouble()
        }
    }

    // --- Fuzzy + acronym app scoring ---

    fun scoreApp(rawQuery: String, appTitle: String): Int {
        val q = rawQuery.trim().lowercase(Locale.US)
        val t = appTitle.trim().lowercase(Locale.US)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 120
        if (t.startsWith(q)) return 100
        val words = t.split(' ', '-', '_', '.', '/').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 80
        // Acronym / initials match: "yt" -> "YouTube", "ps" -> "Play Store"
        if (q.length >= 2) {
            val initials = words.map { it[0] }.joinToString("")
            if (initials.startsWith(q)) return 75
            // CamelCase initials: "YouTube" -> "yt"
            val camelInitials = appTitle.filterIndexed { i, c -> i == 0 || c.isUpperCase() }.lowercase(Locale.US)
            if (camelInitials.startsWith(q)) return 72
        }
        if (t.contains(q)) return 55
        if (q.length >= 3) {
            for (w in words) {
                if (w.length >= 3 && editDistance1(q, w.take(q.length + 1))) return 32
            }
        }
        return 0
    }

    private fun editDistance1(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
            } else {
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> { i++; j++ }
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
