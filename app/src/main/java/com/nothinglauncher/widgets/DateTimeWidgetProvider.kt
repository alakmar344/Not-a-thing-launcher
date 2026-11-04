package com.nothinglauncher.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.nothinglauncher.R

/**
 * A plain (system-hosted) date & time widget.
 *
 * Besides being usable on its own, it guarantees the device lists at least one widget provider:
 * Launcher3's CustomWidgetManager builds the in-process Not a Thing elements from an installed
 * provider and registers none when the list is empty.
 */
class DateTimeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_date_time)
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
