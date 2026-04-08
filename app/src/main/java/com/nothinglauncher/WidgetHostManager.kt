package com.nothinglauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class WidgetHostManager(private val context: Context) : AppWidgetHost(context, HOST_ID) {

    companion object {
        const val HOST_ID = 1024
    }

    private val appWidgetManager = AppWidgetManager.getInstance(context)

    fun allocateWidgetId(): Int {
        return allocateAppWidgetId()
    }

    fun deleteWidgetId(widgetId: Int) {
        deleteAppWidgetId(widgetId)
    }

    fun createWidgetView(widgetId: Int, providerInfo: AppWidgetProviderInfo): AppWidgetHostView {
        return createView(context, widgetId, providerInfo)
    }

    fun updateWidget(widgetId: Int) {
        appWidgetManager.getAppWidgetInfo(widgetId) ?: return
    }

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        return super.onCreateView(context, appWidgetId, appWidget)
    }

    override fun onProviderChanged(appWidgetId: Int, appWidget: AppWidgetProviderInfo) {
        super.onProviderChanged(appWidgetId, appWidget)
    }
}
