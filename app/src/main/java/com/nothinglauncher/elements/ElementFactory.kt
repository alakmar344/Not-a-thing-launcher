package com.nothinglauncher.elements

import android.content.Context
import com.nothinglauncher.BespokeElement

/** Creates the view for each bespoke element. */
object ElementFactory {
    fun create(context: Context, element: BespokeElement): ElementView = when (element) {
        BespokeElement.CLOCK -> ClockElement(context)
        BespokeElement.BATTERY_CALENDAR -> BatteryDateElement(context)
        BespokeElement.WEATHER -> WeatherElement(context)
        BespokeElement.QUICK_TOGGLES -> QuickTogglesElement(context)
        BespokeElement.PEDOMETER -> PedometerElement(context)
        BespokeElement.COMPASS -> CompassElement(context)
        BespokeElement.SCREEN_TIME -> ScreenTimeElement(context)
        BespokeElement.STORAGE -> StorageElement(context)
        BespokeElement.WORLD_CLOCK -> WorldClockElement(context)
        BespokeElement.BATTERY_DOTS -> BatteryGaugeElement(context)
        BespokeElement.QUICK_NOTE -> QuickNoteElement(context)
        BespokeElement.ANALOG_CLOCK -> AnalogClockElement(context)
        BespokeElement.MEDIA_PLAYER -> MediaElement(context)
        BespokeElement.STOPWATCH -> StopwatchElement(context)
        BespokeElement.SYSTEM_MONITOR -> SystemMonitorElement(context)
        BespokeElement.HABIT_TRACKER -> HabitElement(context)
        BespokeElement.COUNTDOWN -> CountdownElement(context)
        BespokeElement.SOUND_RECORDER -> VoiceMemoElement(context)
    }
}
