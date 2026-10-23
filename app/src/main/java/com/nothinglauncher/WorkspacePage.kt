package com.nothinglauncher

enum class BespokeElement(val id: String, val titleRes: Int, val subRes: Int, val iconRes: Int) {
    CLOCK("clock", R.string.element_clock, R.string.element_clock_sub, R.drawable.ic_alarm),
    BATTERY_CALENDAR("battery_cal", R.string.element_battery_cal, R.string.element_battery_cal_sub, R.drawable.ic_bolt),
    WEATHER("weather", R.string.element_weather, R.string.element_weather_sub, R.drawable.ic_weather),
    QUICK_TOGGLES("quick_toggles", R.string.element_toggles, R.string.element_toggles_sub, R.drawable.ic_tune),
    PEDOMETER("pedometer", R.string.element_pedometer, R.string.element_pedometer_sub, R.drawable.ic_walk),
    COMPASS("compass", R.string.element_compass, R.string.element_compass_sub, R.drawable.ic_compass);

    companion object {
        fun fromId(id: String): BespokeElement? = values().firstOrNull { it.id == id }
    }
}

data class WorkspacePage(
    val id: String,
    val appKeys: List<String> = emptyList(),
    val elements: List<String> = emptyList(),
    val widgetIds: List<Int> = emptyList()
)
