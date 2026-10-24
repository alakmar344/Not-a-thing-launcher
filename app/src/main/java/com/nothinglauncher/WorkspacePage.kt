package com.nothinglauncher

enum class BespokeElement(val id: String, val titleRes: Int, val subRes: Int, val iconRes: Int) {
    CLOCK("clock", R.string.element_clock, R.string.element_clock_sub, R.drawable.ic_alarm),
    BATTERY_CALENDAR("battery_cal", R.string.element_battery_cal, R.string.element_battery_cal_sub, R.drawable.ic_bolt),
    WEATHER("weather", R.string.element_weather, R.string.element_weather_sub, R.drawable.ic_weather),
    QUICK_TOGGLES("quick_toggles", R.string.element_toggles, R.string.element_toggles_sub, R.drawable.ic_tune),
    PEDOMETER("pedometer", R.string.element_pedometer, R.string.element_pedometer_sub, R.drawable.ic_walk),
    COMPASS("compass", R.string.element_compass, R.string.element_compass_sub, R.drawable.ic_compass),
    SCREEN_TIME("screen_time", R.string.element_screen_time, R.string.element_screen_time_sub, R.drawable.ic_time),
    STORAGE("storage", R.string.element_storage, R.string.element_storage_sub, R.drawable.ic_storage),
    WORLD_CLOCK("world_clock", R.string.element_world_clock, R.string.element_world_clock_sub, R.drawable.ic_globe),
    BATTERY_DOTS("battery_dots", R.string.element_battery_dots, R.string.element_battery_dots_sub, R.drawable.ic_bolt),
    QUICK_NOTE("quick_note", R.string.element_quick_note, R.string.element_quick_note_sub, R.drawable.ic_pin),
    ANALOG_CLOCK("analog_clock", R.string.element_analog_clock, R.string.element_analog_clock_sub, R.drawable.ic_alarm),
    MEDIA_PLAYER("media_player", R.string.element_media, R.string.element_media_sub, R.drawable.ic_play),
    STOPWATCH("stopwatch", R.string.element_stopwatch, R.string.element_stopwatch_sub, R.drawable.ic_stopwatch),
    SYSTEM_MONITOR("system_monitor", R.string.element_system_monitor, R.string.element_system_monitor_sub, R.drawable.ic_memory),
    HABIT_TRACKER("habit_tracker", R.string.element_habit_tracker, R.string.element_habit_tracker_sub, R.drawable.ic_check),
    COUNTDOWN("countdown", R.string.element_countdown, R.string.element_countdown_sub, R.drawable.ic_calendar_event),
    SOUND_RECORDER("sound_recorder", R.string.element_sound_recorder, R.string.element_sound_recorder_sub, R.drawable.ic_mic);

    companion object {
        fun fromId(id: String): BespokeElement? = values().firstOrNull { it.id == id }
    }
}

data class WorkspaceFolder(
    val id: String,
    val name: String,
    val appKeys: List<String> = emptyList(),
    val isEnlarged: Boolean = false
)

data class WorkspacePage(
    val id: String,
    val appKeys: List<String> = emptyList(),
    val elements: List<String> = emptyList(),
    val widgetIds: List<Int> = emptyList(),
    val enlargedApps: List<String> = emptyList(),
    val folders: List<WorkspaceFolder> = emptyList()
)
