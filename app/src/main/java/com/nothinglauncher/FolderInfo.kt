package com.nothinglauncher

data class FolderInfo(
    val id: String,
    var name: String,
    val apps: MutableList<AppInfo> = mutableListOf()
)
