package com.nothinglauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val _allApps = MutableLiveData<List<AppInfo>>()
    val allApps: LiveData<List<AppInfo>> = _allApps

    private val _homeApps = MutableLiveData<List<AppInfo>>()
    val homeApps: LiveData<List<AppInfo>> = _homeApps

    private val _dockApps = MutableLiveData<List<AppInfo>>()
    val dockApps: LiveData<List<AppInfo>> = _dockApps

    private val prefs = application.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    init {
        loadApps()
        registerPackageReceiver()
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(
                packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            getApplication<Application>().registerReceiver(packageReceiver, filter)
        }
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfoList: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
            val apps = resolveInfoList
                .map { ri ->
                    AppInfo(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        icon = ri.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }

            _allApps.postValue(apps)
            loadHomeLayout(apps)
            loadDockApps(apps)
        }
    }

    private fun loadHomeLayout(allApps: List<AppInfo>) {
        val savedJson = prefs.getString("home_apps", null)
        if (savedJson != null) {
            try {
                val jsonArray = JSONArray(savedJson)
                val homeList = mutableListOf<AppInfo>()
                for (i in 0 until jsonArray.length()) {
                    val pkg = jsonArray.getString(i)
                    allApps.find { it.packageName == pkg }?.let { homeList.add(it) }
                }
                if (homeList.isNotEmpty()) {
                    _homeApps.postValue(homeList)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _homeApps.postValue(allApps.take(20))
    }

    private fun loadDockApps(allApps: List<AppInfo>) {
        val savedJson = prefs.getString("dock_apps", null)
        if (savedJson != null) {
            try {
                val jsonArray = JSONArray(savedJson)
                val dockList = mutableListOf<AppInfo>()
                for (i in 0 until jsonArray.length()) {
                    val pkg = jsonArray.getString(i)
                    allApps.find { it.packageName == pkg }?.let { dockList.add(it) }
                }
                if (dockList.isNotEmpty()) {
                    _dockApps.postValue(dockList)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val phoneApp = allApps.find { it.packageName.contains("dialer") || it.packageName.contains("phone") }
        val cameraApp = allApps.find { it.packageName.contains("camera") }
        val browserApp = allApps.find { it.packageName.contains("browser") || it.packageName.contains("chrome") }
        val messengerApp = allApps.find { it.packageName.contains("messaging") || it.packageName.contains("sms") }
        val defaultDock = listOfNotNull(phoneApp, messengerApp, cameraApp, browserApp).take(4)
        _dockApps.postValue(defaultDock)
    }

    fun saveHomeLayout(apps: List<AppInfo>) {
        val jsonArray = JSONArray()
        apps.forEach { jsonArray.put(it.packageName) }
        prefs.edit().putString("home_apps", jsonArray.toString()).apply()
        _homeApps.postValue(apps)
    }

    fun saveDockApps(apps: List<AppInfo>) {
        val jsonArray = JSONArray()
        apps.forEach { jsonArray.put(it.packageName) }
        prefs.edit().putString("dock_apps", jsonArray.toString()).apply()
        _dockApps.postValue(apps)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
