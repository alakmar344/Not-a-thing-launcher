package com.nothinglauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.os.Build

class WallpaperPickerManager(private val activity: Activity) {

    companion object {
        const val REQUEST_WALLPAPER = 1001
    }

    fun openWallpaperPicker() {
        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            activity.startActivityForResult(
                Intent.createChooser(intent, "Select Wallpaper"),
                REQUEST_WALLPAPER
            )
        } catch (e: Exception) {
            openSystemWallpaperPicker()
        }
    }

    private fun openSystemWallpaperPicker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val wallpaperManager = WallpaperManager.getInstance(activity)
                val intent = wallpaperManager.getCropAndSetWallpaperIntent(null)
                activity.startActivityForResult(intent, REQUEST_WALLPAPER)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
