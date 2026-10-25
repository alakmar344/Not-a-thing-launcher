package com.nothinglauncher

/**
 * The bespoke Nothing elements that can be placed on the home screen like widgets.
 * [id] values are persisted (v8 layouts and the v9 database) — never rename them.
 */
enum class BespokeElement(
    val id: String,
    val titleRes: Int,
    val subRes: Int,
    val iconRes: Int,
    val spanX: Int,
    val spanY: Int,
    val minSpanX: Int,
    val minSpanY: Int
) {
    CLOCK("clock", R.string.element_clock, R.string.element_clock_sub, R.drawable.ic_alarm, 4, 2, 2, 1),
    BATTERY_CALENDAR("battery_cal", R.string.element_battery_cal, R.string.element_battery_cal_sub, R.drawable.ic_bolt, 4, 2, 2, 1),
    WEATHER("weather", R.string.element_weather, R.string.element_weather_sub, R.drawable.ic_weather, 4, 2, 2, 2),
    QUICK_TOGGLES("quick_toggles", R.string.element_toggles, R.string.element_toggles_sub, R.drawable.ic_tune, 4, 2, 2, 2),
    PEDOMETER("pedometer", R.string.element_pedometer, R.string.element_pedometer_sub, R.drawable.ic_walk, 2, 2, 2, 2),
    COMPASS("compass", R.string.element_compass, R.string.element_compass_sub, R.drawable.ic_compass, 2, 2, 2, 2),
    SCREEN_TIME("screen_time", R.string.element_screen_time, R.string.element_screen_time_sub, R.drawable.ic_time, 4, 2, 2, 2),
    STORAGE("storage", R.string.element_storage, R.string.element_storage_sub, R.drawable.ic_storage, 2, 2, 2, 2),
    WORLD_CLOCK("world_clock", R.string.element_world_clock, R.string.element_world_clock_sub, R.drawable.ic_globe, 4, 2, 2, 2),
    BATTERY_DOTS("battery_dots", R.string.element_battery_dots, R.string.element_battery_dots_sub, R.drawable.ic_bolt, 2, 2, 2, 2),
    QUICK_NOTE("quick_note", R.string.element_quick_note, R.string.element_quick_note_sub, R.drawable.ic_pin, 4, 2, 2, 1),
    ANALOG_CLOCK("analog_clock", R.string.element_analog_clock, R.string.element_analog_clock_sub, R.drawable.ic_alarm, 2, 2, 2, 2),
    MEDIA_PLAYER("media_player", R.string.element_media, R.string.element_media_sub, R.drawable.ic_play, 4, 2, 4, 2),
    STOPWATCH("stopwatch", R.string.element_stopwatch, R.string.element_stopwatch_sub, R.drawable.ic_stopwatch, 4, 2, 2, 2),
    SYSTEM_MONITOR("system_monitor", R.string.element_system_monitor, R.string.element_system_monitor_sub, R.drawable.ic_memory, 2, 2, 2, 2),
    HABIT_TRACKER("habit_tracker", R.string.element_habit_tracker, R.string.element_habit_tracker_sub, R.drawable.ic_check, 4, 2, 4, 2),
    COUNTDOWN("countdown", R.string.element_countdown, R.string.element_countdown_sub, R.drawable.ic_calendar_event, 2, 2, 2, 2),
    SOUND_RECORDER("sound_recorder", R.string.element_sound_recorder, R.string.element_sound_recorder_sub, R.drawable.ic_mic, 4, 2, 2, 2);

    companion object {
        fun fromId(id: String?): BespokeElement? = values().firstOrNull { it.id == id }
    }
}

// ---- v8 layout format (read once by the v9 migration; still written by nothing) -------------

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
