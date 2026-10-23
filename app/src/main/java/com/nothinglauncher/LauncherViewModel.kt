package com.nothinglauncher

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * Single source of truth for apps and the home layout.
 * Apps are loaded through [LauncherApps] (the API launchers are meant to use) and refreshed
 * automatically when packages are installed, updated or removed.
 */
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = LauncherPrefs(application)

    private val _allApps = MutableLiveData<List<AppInfo>>(emptyList())
    /** Every visible (non-hidden) app, alphabetically. */
    val allApps: LiveData<List<AppInfo>> = _allApps

    private val _homeApps = MutableLiveData<List<AppInfo>>(emptyList())
    val homeApps: LiveData<List<AppInfo>> = _homeApps

    private val _dockApps = MutableLiveData<List<AppInfo>>(emptyList())
    val dockApps: LiveData<List<AppInfo>> = _dockApps

    private val _recentApps = MutableLiveData<List<AppInfo>>(emptyList())
    val recentApps: LiveData<List<AppInfo>> = _recentApps

    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded

    /** All launchable apps including hidden ones. */
    private var installed: List<AppInfo> = emptyList()
    private var renderedIconStyle: LauncherPrefs.IconStyle? = null
    private var loadJob: Job? = null

    private val launcherApps = application.getSystemService(LauncherApps::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = loadApps()
        override fun onPackageAdded(packageName: String, user: UserHandle) = loadApps()
        override fun onPackageChanged(packageName: String, user: UserHandle) = loadApps()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = loadApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = loadApps()
    }

    init {
        try {
            launcherApps?.registerCallback(packageCallback, mainHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loadApps()
    }

    fun loadApps() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val style = prefs.iconStyle
            val apps = withContext(Dispatchers.Default) { queryApps(style) }
            installed = apps
            renderedIconStyle = style
            publish()
            _loaded.value = true
        }
    }

    /** Re-applies settings that affect the data (icon style, hidden apps). */
    fun onSettingsChanged() {
        val rendered = renderedIconStyle ?: return // first load still running; it reads prefs itself
        if (prefs.iconStyle != rendered) loadApps() else publish()
    }

    private fun queryApps(style: LauncherPrefs.IconStyle): List<AppInfo> {
        val app = getApplication<Application>()
        val la = launcherApps ?: return emptyList()
        val user = Process.myUserHandle()
        val density = app.resources.displayMetrics.densityDpi
        val sizePx = app.resources.getDimensionPixelSize(R.dimen.icon_render_size)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return try {
            la.getActivityList(null, user)
                .filter { it.componentName.packageName != app.packageName }
                .map { info ->
                    val raw = try { info.getIcon(density) } catch (e: Exception) { null }
                        ?: app.packageManager.defaultActivityIcon
                    val flags = info.applicationInfo.flags
                    AppInfo(
                        label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                        packageName = info.componentName.packageName,
                        activityName = info.componentName.className,
                        icon = IconFactory.render(app.resources, raw, style, sizePx),
                        user = info.user,
                        isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    )
                }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun publish() {
        val hidden = prefs.hiddenApps
        val visible = installed.filter { it.key !in hidden }
        val byKey = installed.associateBy { it.key }
        _allApps.value = visible

        // Dock: saved → legacy (package names) → sensible defaults.
        var dockKeys = prefs.dockItems
        if (dockKeys == null) {
            dockKeys = prefs.legacyDockPackages?.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg }?.key }
                ?: defaultDock(visible).map { it.key }
            if (installed.isNotEmpty()) prefs.dockItems = dockKeys
        }
        val dock = dockKeys.mapNotNull { byKey[it] }.filter { it.key !in hidden }.take(MAX_DOCK)

        var homeKeys = prefs.homeItems
        if (homeKeys == null) {
            homeKeys = prefs.legacyHomePackages?.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg }?.key }
                ?: defaultHome(visible, dock).map { it.key }
            if (installed.isNotEmpty()) prefs.homeItems = homeKeys
        }
        val home = homeKeys.distinct().mapNotNull { byKey[it] }.filter { it.key !in hidden }

        _dockApps.value = dock
        _homeApps.value = home
        _recentApps.value = prefs.recents.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    // ---- Layout mutations ---------------------------------------------------------------

    fun isOnHome(app: AppInfo) = _homeApps.value.orEmpty().any { it.key == app.key }
    fun isInDock(app: AppInfo) = _dockApps.value.orEmpty().any { it.key == app.key }
    fun isDockFull() = _dockApps.value.orEmpty().size >= MAX_DOCK

    fun pinToHome(app: AppInfo) {
        if (isOnHome(app)) return
        saveHome(_homeApps.value.orEmpty() + app)
    }

    fun removeFromHome(app: AppInfo) = saveHome(_homeApps.value.orEmpty().filterNot { it.key == app.key })

    fun saveHome(apps: List<AppInfo>) {
        prefs.homeItems = apps.map { it.key }
        _homeApps.value = apps
    }

    /** Returns false if the dock is already full. */
    fun addToDock(app: AppInfo): Boolean {
        if (isInDock(app)) return true
        if (isDockFull()) return false
        saveDock(_dockApps.value.orEmpty() + app)
        return true
    }

    fun removeFromDock(app: AppInfo) = saveDock(_dockApps.value.orEmpty().filterNot { it.key == app.key })

    private fun saveDock(apps: List<AppInfo>) {
        prefs.dockItems = apps.map { it.key }
        _dockApps.value = apps
    }

    fun hide(app: AppInfo) {
        prefs.hiddenApps = prefs.hiddenApps + app.key
        publish()
    }

    fun recordLaunch(app: AppInfo) {
        prefs.recents = listOf(app.key) + prefs.recents.filterNot { it == app.key }
        val byKey = installed.associateBy { it.key }
        val hidden = prefs.hiddenApps
        _recentApps.value = prefs.recents.mapNotNull { byKey[it] }.filter { it.key !in hidden }
    }

    fun resetLayout() {
        prefs.resetLayout()
        publish()
    }

    // ---- Defaults -----------------------------------------------------------------------

    private fun packageFor(intent: Intent): String? {
        val pm = getApplication<Application>().packageManager
        return try {
            val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = ri?.activityInfo?.packageName
            if (pkg == null || pkg == "android") {
                pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
            } else pkg
        } catch (e: Exception) {
            null
        }
    }

    private fun defaultDock(apps: List<AppInfo>): List<AppInfo> {
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://nothing.tech")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        ).mapNotNull { packageFor(it) }
        val dock = wanted.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }.distinctBy { it.key }
        return if (dock.isNotEmpty()) dock else apps.take(4)
    }

    private fun defaultHome(apps: List<AppInfo>, dock: List<AppInfo>): List<AppInfo> {
        val dockKeys = dock.map { it.key }.toSet()
        val preferred = listOf(
            "com.google.android.apps.photos", "com.google.android.gm", "com.google.android.apps.maps",
            "com.google.android.youtube", "com.google.android.calendar", "com.google.android.deskclock",
            "com.android.vending", "com.spotify.music", "com.whatsapp", "com.google.android.apps.docs",
            "com.android.settings", "com.google.android.apps.nbu.files"
        )
        val picked = preferred.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg && it.key !in dockKeys } }
        val filler = apps.filter { it.key !in dockKeys && picked.none { p -> p.key == it.key } }
        return (picked + filler).take(DEFAULT_HOME_COUNT)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            launcherApps?.unregisterCallback(packageCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val MAX_DOCK = 5
        private const val DEFAULT_HOME_COUNT = 8
    }
}
