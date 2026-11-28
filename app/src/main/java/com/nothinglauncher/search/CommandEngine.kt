package com.nothinglauncher.search

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nothinglauncher.DeskHudActivity
import com.nothinglauncher.FocusTimerRepository
import com.nothinglauncher.HardwareControlRepository
import com.nothinglauncher.Intents
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.SetupBackupManager
import com.nothinglauncher.settings.NothingSettingsActivity
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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

    private val NUM_FMT_TL = object : ThreadLocal<DecimalFormat>() {
        override fun initialValue(): DecimalFormat = DecimalFormat("#,##0.####")
    }

    private fun fmtNum(v: Double): String = NUM_FMT_TL.get()!!.format(v)

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

        evalColor(q)?.let { (title, sub, copy) ->
            out += CommandResult(
                kind = Kind.ANSWER,
                badge = "HEX",
                title = title,
                subtitle = sub,
                copyValue = copy
            ) { ctx -> copyToClipboard(ctx, copy) }
        }

        evalUtility(q)?.let { (badge, title, sub, copy) ->
            out += CommandResult(
                kind = Kind.ANSWER,
                badge = badge,
                title = title,
                subtitle = sub,
                copyValue = copy
            ) { ctx -> copyToClipboard(ctx, copy, sensitive = (badge == "KEY")) }
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

    private fun copyToClipboard(context: Context, text: String, sensitive: Boolean = false) {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Launcher Command", text)
            if (sensitive && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm?.setPrimaryClip(clip)
            val toastText = if (sensitive) {
                context.getString(R.string.nt_password_copied)
            } else {
                context.getString(R.string.copied_fmt, text)
            }
            Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }

    private fun evalQuickAction(context: Context, q: String): CommandResult? {
        val low = q.lowercase(Locale.US)
        val timerMatch = TIMER_CMD_RE.matchEntire(low)
        if (timerMatch != null) {
            val mins = timerMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 180) ?: 25
            return CommandResult(
                Kind.ACTION, "TIMER",
                "Start ${mins}m Focus Timer",
                "Syncs across Home widget & Dock Space"
            ) { ctx ->
                val p = NothingPrefs.get(ctx)
                p.timerStopwatchMode = false
                p.timerDurationMs = mins * 60_000L
                p.timerRemainingMs = p.timerDurationMs
                p.timerRunning = false
                FocusTimerRepository.toggleStartPause(ctx)
                Toast.makeText(ctx, "${mins}m Focus Timer started", Toast.LENGTH_SHORT).show()
            }
        }
        if (low.startsWith("note ") && q.length > 5) {
            val noteBody = q.substring(5).trim()
            if (noteBody.isNotEmpty()) {
                return CommandResult(
                    Kind.ACTION, "NOTE",
                    "Save to Focus Note",
                    noteBody
                ) { ctx ->
                    NothingPrefs.get(ctx).essentialNote = noteBody
                    Toast.makeText(ctx, "Saved to Focus Note", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (low.startsWith("preset ")) {
            val target = low.removePrefix("preset ").trim()
            val preset = when {
                target.startsWith("tele") -> SetupBackupManager.Preset.TELEMETRY
                target.startsWith("mono") -> SetupBackupManager.Preset.MONOLITH
                target.startsWith("comm") -> SetupBackupManager.Preset.COMMAND
                target.startsWith("stud") -> SetupBackupManager.Preset.STUDIO
                else -> null
            }
            if (preset != null) {
                return CommandResult(
                    Kind.ACTION, "PRESET",
                    "Apply ${context.getString(preset.titleRes)}",
                    context.getString(preset.subtitleRes)
                ) { ctx ->
                    SetupBackupManager.applyPreset(ctx, preset)
                    Toast.makeText(ctx, "${ctx.getString(preset.titleRes)} applied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return when {
            low == "desk" || low == "standby" || low == "hud" || low == "desk hud" -> CommandResult(
                Kind.ACTION, "HUD",
                "Open Matrix Desk HUD",
                "Full-screen StandBy clock, weather & focus cockpit"
            ) { DeskHudActivity.launch(it) }
            low == "stopwatch" || low == "sw" -> CommandResult(
                Kind.ACTION, "TIMER",
                "Start Stopwatch",
                "Counts up in Focus Timer & Dock Space"
            ) { ctx ->
                val p = NothingPrefs.get(ctx)
                p.timerStopwatchMode = true
                FocusTimerRepository.reset(ctx)
                FocusTimerRepository.toggleStartPause(ctx)
                Toast.makeText(ctx, "Stopwatch started", Toast.LENGTH_SHORT).show()
            }
            low == "pause timer" || low == "stop timer" -> CommandResult(
                Kind.ACTION, "TIMER",
                "Pause Focus Timer",
                "Keeps remaining focus progress"
            ) { ctx ->
                if (NothingPrefs.get(ctx).timerRunning) FocusTimerRepository.toggleStartPause(ctx)
            }
            low == "reset timer" -> CommandResult(
                Kind.ACTION, "TIMER",
                "Reset Focus Timer",
                "Resets countdown/stopwatch"
            ) { FocusTimerRepository.reset(it) }
            low == "torch" || low == "flashlight" || low == "flash" -> CommandResult(
                Kind.ACTION, "CMD",
                if (HardwareControlRepository.torchOn) "Turn Flashlight Off" else "Turn Flashlight On",
                "Hardware torch toggle"
            ) { HardwareControlRepository.toggleTorch(it) }
            low == "focus" || low == "focus mode" -> CommandResult(
                Kind.ACTION, "CMD",
                if (NothingPrefs.get(context).focusMode) "Exit Focus Mode" else "Enter Focus Mode",
                "Monochrome low-distraction home"
            ) {
                val p = NothingPrefs.get(it)
                p.focusMode = !p.focusMode
                Toast.makeText(
                    it,
                    if (p.focusMode) "Focus Mode active" else "Focus Mode off",
                    Toast.LENGTH_SHORT
                ).show()
            }
            low == "caffeine" || low == "awake" -> CommandResult(
                Kind.ACTION, "CMD",
                if (NothingPrefs.get(context).caffeineEnabled) "Disable Caffeine (Screen Awake)" else "Enable Caffeine (Keep Screen On)",
                "Home screen display lock"
            ) {
                val p = NothingPrefs.get(it)
                p.caffeineEnabled = !p.caffeineEnabled
                Toast.makeText(
                    it,
                    if (p.caffeineEnabled) "Caffeine active" else "Caffeine off",
                    Toast.LENGTH_SHORT
                ).show()
            }
            low == "wifi" || low == "wi-fi" -> CommandResult(
                Kind.ACTION, "SYS", "Open Wi-Fi Settings", "Network & internet"
            ) { Intents.start(it, Intent(Settings.ACTION_WIFI_SETTINGS)) }
            low == "bt" || low == "bluetooth" -> CommandResult(
                Kind.ACTION, "SYS", "Open Bluetooth Settings", "Connected devices"
            ) { Intents.start(it, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            low == "battery" -> CommandResult(
                Kind.ACTION, "SYS", "Open Battery Usage", "Power & charging status"
            ) { Intents.openBatterySettings(it) }
            low == "storage" -> CommandResult(
                Kind.ACTION, "SYS", "Open Storage Settings", "Free space & files"
            ) { Intents.start(it, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            low == "display" -> CommandResult(
                Kind.ACTION, "SYS", "Open Display Settings", "Brightness, dark mode & refresh rate"
            ) { Intents.start(it, Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
            low == "wallpaper" || low == "wall" -> CommandResult(
                Kind.ACTION, "SYS", "Choose Wallpaper", "System wallpaper picker"
            ) { Intents.openWallpaperPicker(it) }
            low == "settings" || low == "launcher settings" || low == "config" -> CommandResult(
                Kind.ACTION, "SYS", "Not a Thing Settings", "Grid, icon style, gestures, quality"
            ) { Intents.start(it, Intent(it, NothingSettingsActivity::class.java)) }
            else -> null
        }
    }

    private val TIMER_CMD_RE = Regex("""^timer\s+(\d{1,3})\s*(?:m|min|mins|minutes)?$""")
    private val HEX_COLOR_RE = Regex("""^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
    private val RGB_COLOR_RE = Regex("""^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$""", RegexOption.IGNORE_CASE)
    private val DICE_RE = Regex("""^roll\s+(\d*)d(\d+)$""", RegexOption.IGNORE_CASE)

    internal fun evalColor(q: String): Triple<String, String, String>? {
        HEX_COLOR_RE.matchEntire(q.trim())?.let { m ->
            val raw = m.groupValues[1].uppercase(Locale.US)
            val color = Color.parseColor("#$raw")
            val rgb = "rgb(${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)})"
            return Triple("#$raw", "$rgb · Tap to copy", rgb)
        }
        RGB_COLOR_RE.matchEntire(q.trim())?.let { m ->
            val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
            val hex = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
            return Triple(hex, "RGB($r, $g, $b) · Tap to copy", hex)
        }
        return null
    }

    private val secureRng = java.security.SecureRandom()

    internal fun evalUtility(q: String): List4? {
        val low = q.trim().lowercase(Locale.US)
        if (low == "uuid") {
            val u = UUID.randomUUID().toString()
            return List4("DEV", u, "Random UUID · Tap to copy", u)
        }
        if (low == "flip" || low == "flip coin" || low == "coin") {
            val side = if (secureRng.nextBoolean()) "HEADS" else "TAILS"
            return List4("RNG", side, "Coin flip · Tap to copy", side)
        }
        if (low.startsWith("pass")) {
            val len = low.removePrefix("pass").trim().toIntOrNull()?.coerceIn(8, 32) ?: 16
            val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
            val lower = "abcdefghijkmnopqrstuvwxyz"
            val digits = "23456789"
            val symbols = "!@#$%"
            val all = upper + lower + digits + symbols
            val buf = CharArray(len)
            buf[0] = upper[secureRng.nextInt(upper.length)]
            buf[1] = lower[secureRng.nextInt(lower.length)]
            buf[2] = digits[secureRng.nextInt(digits.length)]
            buf[3] = symbols[secureRng.nextInt(symbols.length)]
            for (i in 4 until len) buf[i] = all[secureRng.nextInt(all.length)]
            // Fisher-Yates shuffle using CSPRNG so required classes aren't pinned to indexes 0..3.
            for (i in len - 1 downTo 1) {
                val j = secureRng.nextInt(i + 1)
                val tmp = buf[i]
                buf[i] = buf[j]
                buf[j] = tmp
            }
            val pw = String(buf)
            java.util.Arrays.fill(buf, '\u0000')
            return List4("KEY", pw, "${len}-char password · Tap to copy", pw)
        }
        DICE_RE.matchEntire(low)?.let { m ->
            val count = (m.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 10)
            val sides = (m.groupValues[2].toIntOrNull() ?: 6).coerceIn(2, 1000)
            val rolls = List(count) { secureRng.nextInt(sides) + 1 }
            val sum = rolls.sum()
            val detail = if (count == 1) "d$sides · Tap to copy" else "${rolls.joinToString(" + ")} (${count}d$sides)"
            return List4("RNG", sum.toString(), detail, sum.toString())
        }
        return null
    }

    internal data class List4(val badge: String, val title: String, val subtitle: String, val copy: String)

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

    private val TZ_CITY_MAP: Map<String, String> by lazy {
        val ids = TimeZone.getAvailableIDs()
        val m = HashMap<String, String>(ids.size)
        for (id in ids) {
            val key = id.substringAfterLast('/').replace('_', ' ').lowercase(Locale.US)
            m.putIfAbsent(key, id)
        }
        m
    }

    private val TZ_FMT_TL = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm · EEE, MMM d", Locale.US)
    }

    internal fun evalTimezone(q: String): Pair<String, String>? {
        val low = q.trim().lowercase(Locale.US)
        if (!low.startsWith("time ")) return null
        val target = low.removePrefix("time ").trim()
        val zoneId = ZONE_ALIASES[target] ?: TZ_CITY_MAP[target] ?: return null
        val tz = TimeZone.getTimeZone(zoneId)
        val fmt = TZ_FMT_TL.get()!!
        fmt.timeZone = tz
        return fmt.format(Date()) to tz.id.replace('_', ' ')
    }

    // --- Unit conversions ---

    private val CONV_RE = Regex(
        """^\s*(-?\d+(?:\.\d+)?)\s*([a-zA-Z]+)\s+(?:to|in)\s+([a-zA-Z]+)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val LENGTH_METERS = mapOf(
        "m" to 1.0, "km" to 1000.0, "cm" to 0.01, "mm" to 0.001,
        "mi" to 1609.344, "ft" to 0.3048, "in" to 0.0254, "yd" to 0.9144
    )
    private val MASS_GRAMS = mapOf(
        "g" to 1.0, "kg" to 1000.0, "lb" to 453.59237, "lbs" to 453.59237, "oz" to 28.349523125
    )
    private val DATA_MB = mapOf("kb" to 1.0 / 1024.0, "mb" to 1.0, "gb" to 1024.0, "tb" to 1024.0 * 1024.0)

    internal fun evalConversion(q: String): Pair<String, String>? {
        val m = CONV_RE.matchEntire(q) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2].lowercase(Locale.US)
        val to = m.groupValues[3].lowercase(Locale.US)

        if ((from == "c" || from == "celsius") && (to == "f" || to == "fahrenheit")) {
            val out = value * 9.0 / 5.0 + 32.0
            return "${fmtNum(out)} °F" to "${fmtNum(value)} °C"
        }
        if ((from == "f" || from == "fahrenheit") && (to == "c" || to == "celsius")) {
            val out = (value - 32.0) * 5.0 / 9.0
            return "${fmtNum(out)} °C" to "${fmtNum(value)} °F"
        }

        val lFrom = LENGTH_METERS[from]
        val lTo = LENGTH_METERS[to]
        if (lFrom != null && lTo != null) {
            val out = value * lFrom / lTo
            return "${fmtNum(out)} $to" to "${fmtNum(value)} $from"
        }

        val mFrom = MASS_GRAMS[from]
        val mTo = MASS_GRAMS[to]
        if (mFrom != null && mTo != null) {
            val out = value * mFrom / mTo
            return "${fmtNum(out)} $to" to "${fmtNum(value)} $from"
        }

        val dFrom = DATA_MB[from]
        val dTo = DATA_MB[to]
        if (dFrom != null && dTo != null) {
            val out = value * dFrom / dTo
            return "${fmtNum(out)} ${to.uppercase(Locale.US)}" to "${fmtNum(value)} ${from.uppercase(Locale.US)}"
        }
        return null
    }

    // --- Math & percentage recursive-descent evaluator ---

    private val PCT_OF_RE = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*%\s*of\s*(-?\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)

    internal fun evalMath(q: String): String? {
        PCT_OF_RE.matchEntire(q)?.let { m ->
            val pct = m.groupValues[1].toDoubleOrNull() ?: return null
            val base = m.groupValues[2].toDoubleOrNull() ?: return null
            return fmtNum((pct / 100.0) * base)
        }
        val clean = q.replace(" ", "")
        if (clean.all { it.isDigit() || it == '.' }) return null // plain number isn't a math expression
        if (!clean.any { it == '+' || it == '-' || it == '*' || it == '/' || it == '^' }) return null
        return try {
            val parser = ExprParser(clean)
            val result = parser.parse()
            if (!parser.atEnd() || result.isNaN() || result.isInfinite()) null
            else fmtNum(result)
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
        var qs = 0
        var qe = rawQuery.length
        while (qs < qe && rawQuery[qs] <= ' ') qs++
        while (qe > qs && rawQuery[qe - 1] <= ' ') qe--
        var ts = 0
        var te = appTitle.length
        while (ts < te && appTitle[ts] <= ' ') ts++
        while (te > ts && appTitle[te - 1] <= ' ') te--
        val qLen = qe - qs
        val tLen = te - ts
        if (qLen <= 0 || tLen <= 0) return 0

        if (tLen >= qLen && matchesSlice(appTitle, ts, rawQuery, qs, qLen)) {
            return if (tLen == qLen) 120 else 100
        }

        // Single zero-allocation pass over word delimiters (' ', '-', '_', '.', '/'):
        // checks word-prefix (score 80) AND tracks word-initials match (score 75).
        var initialPos = 0
        var initialsFailed = qLen < 2
        var idx = ts
        while (idx < te) {
            while (idx < te && isWordSep(appTitle[idx])) idx++
            if (idx >= te) break
            val wStart = idx
            while (idx < te && !isWordSep(appTitle[idx])) idx++
            val wLen = idx - wStart
            if (wLen >= qLen && matchesSlice(appTitle, wStart, rawQuery, qs, qLen)) return 80
            if (!initialsFailed && initialPos < qLen) {
                if (lowerChar(appTitle[wStart]) == lowerChar(rawQuery[qs + initialPos])) {
                    initialPos++
                } else {
                    initialsFailed = true
                }
            }
        }
        if (!initialsFailed && initialPos == qLen) return 75

        // CamelCase initials: "YouTube" -> "yt"
        if (qLen >= 2) {
            var camelPos = 0
            var camelFailed = false
            for (i in 0 until appTitle.length) {
                val c = appTitle[i]
                if (i == 0 || c.isUpperCase()) {
                    if (camelPos < qLen) {
                        if (lowerChar(c) == lowerChar(rawQuery[qs + camelPos])) {
                            camelPos++
                        } else {
                            camelFailed = true
                            break
                        }
                    } else {
                        break
                    }
                }
            }
            if (!camelFailed && camelPos == qLen) return 72
        }

        if (tLen >= qLen && containsSlice(appTitle, ts, te, rawQuery, qs, qLen)) return 55

        if (qLen >= 3) {
            idx = ts
            while (idx < te) {
                while (idx < te && isWordSep(appTitle[idx])) idx++
                if (idx >= te) break
                val wStart = idx
                while (idx < te && !isWordSep(appTitle[idx])) idx++
                val wLen = idx - wStart
                if (wLen >= 3) {
                    val cmpEnd = min(idx, wStart + qLen + 1)
                    if (editDistance1Slice(rawQuery, qs, qe, appTitle, wStart, cmpEnd)) return 32
                }
            }
        }
        return 0
    }

    private fun isWordSep(c: Char): Boolean =
        c == ' ' || c == '-' || c == '_' || c == '.' || c == '/'

    private fun lowerChar(c: Char): Char =
        if (c in 'A'..'Z') (c.code + 32).toChar() else c.lowercaseChar()

    private fun matchesSlice(a: String, aStart: Int, b: String, bStart: Int, len: Int): Boolean {
        for (i in 0 until len) {
            if (lowerChar(a[aStart + i]) != lowerChar(b[bStart + i])) return false
        }
        return true
    }

    private fun containsSlice(
        hay: String, hStart: Int, hEnd: Int,
        needle: String, nStart: Int, nLen: Int
    ): Boolean {
        val last = hEnd - nLen
        val firstChar = lowerChar(needle[nStart])
        var i = hStart
        while (i <= last) {
            if (lowerChar(hay[i]) == firstChar && matchesSlice(hay, i, needle, nStart, nLen)) {
                return true
            }
            i++
        }
        return false
    }

    private fun editDistance1Slice(
        a: String, aStart: Int, aEnd: Int,
        b: String, bStart: Int, bEnd: Int
    ): Boolean {
        val aLen = aEnd - aStart
        val bLen = bEnd - bStart
        if (kotlin.math.abs(aLen - bLen) > 1) return false
        var i = aStart
        var j = bStart
        var edits = 0
        while (i < aEnd && j < bEnd) {
            if (lowerChar(a[i]) == lowerChar(b[j])) {
                i++; j++
            } else {
                if (++edits > 1) return false
                when {
                    aLen > bLen -> i++
                    bLen > aLen -> j++
                    else -> { i++; j++ }
                }
            }
        }
        if (i < aEnd || j < bEnd) edits++
        return edits <= 1
    }
}
