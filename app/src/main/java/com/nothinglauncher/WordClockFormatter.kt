package com.nothinglauncher

import java.util.Calendar

/** Converts time to concise typographic phrases for the Word Clock face. */
object WordClockFormatter {

    private val HOURS = arrayOf(
        "TWELVE", "ONE", "TWO", "THREE", "FOUR", "FIVE",
        "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "ELEVEN"
    )

    fun format(cal: Calendar): Pair<String, String> {
        val rawHour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val rounded = ((minute + 2) / 5) * 5
        val hourIdx = (if (rounded >= 35) rawHour + 1 else rawHour) % 12
        val hourWord = HOURS[hourIdx]
        val line1 = when (rounded % 60) {
            0 -> hourWord
            5 -> "FIVE PAST"
            10 -> "TEN PAST"
            15 -> "QUARTER PAST"
            20 -> "TWENTY PAST"
            25 -> "TWENTY FIVE"
            30 -> "HALF PAST"
            35 -> "TWENTY FIVE TO"
            40 -> "TWENTY TO"
            45 -> "QUARTER TO"
            50 -> "TEN TO"
            55 -> "FIVE TO"
            else -> hourWord
        }
        val line2 = if (rounded % 60 == 0) "O CLOCK" else hourWord
        return line1 to line2
    }
}
