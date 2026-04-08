package com.nothinglauncher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class FolderManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE)

    fun saveFolders(folders: List<FolderInfo>) {
        val jsonArray = JSONArray()
        for (folder in folders) {
            val folderObj = JSONObject()
            folderObj.put("id", folder.id)
            folderObj.put("name", folder.name)
            val appsArray = JSONArray()
            for (app in folder.apps) {
                val appObj = JSONObject()
                appObj.put("packageName", app.packageName)
                appObj.put("activityName", app.activityName)
                appsArray.put(appObj)
            }
            folderObj.put("apps", appsArray)
            jsonArray.put(folderObj)
        }
        prefs.edit().putString("folders", jsonArray.toString()).apply()
    }

    fun loadFolderIds(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val jsonStr = prefs.getString("folders", null) ?: return result
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val folderObj = jsonArray.getJSONObject(i)
                val id = folderObj.getString("id")
                val name = folderObj.getString("name")
                result.add(Pair(id, name))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun loadFolderAppPackages(folderId: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val jsonStr = prefs.getString("folders", null) ?: return result
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val folderObj = jsonArray.getJSONObject(i)
                if (folderObj.getString("id") == folderId) {
                    val appsArray = folderObj.getJSONArray("apps")
                    for (j in 0 until appsArray.length()) {
                        val appObj = appsArray.getJSONObject(j)
                        result.add(
                            Pair(
                                appObj.getString("packageName"),
                                appObj.getString("activityName")
                            )
                        )
                    }
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun createFolder(name: String, apps: List<AppInfo>): FolderInfo {
        return FolderInfo(
            id = UUID.randomUUID().toString(),
            name = name,
            apps = apps.toMutableList()
        )
    }
}
