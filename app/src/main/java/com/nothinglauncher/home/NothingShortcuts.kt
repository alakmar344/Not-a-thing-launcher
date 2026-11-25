package com.nothinglauncher.home

import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.android.launcher3.LauncherSettings
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.nothinglauncher.NothingPrefs
import com.nothinglauncher.R
import com.nothinglauncher.Sheets
import com.nothinglauncher.icons.NothingIconLogic

/**
 * Custom long-press icon menu actions for Not a Thing v13:
 * - Hide app in the biometric Hidden Vault
 * - Custom-rename any app icon label
 */
object NothingShortcuts {

    val HIDE_IN_VAULT: SystemShortcut.Factory<NothingLauncher> =
        SystemShortcut.Factory { launcher, itemInfo, originalView ->
            val component = itemInfo.targetComponent ?: return@Factory null
            val pkg = component.packageName
            if (pkg == launcher.packageName || pkg.isBlank()) return@Factory null
            if (itemInfo.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) return@Factory null
            HideInVaultShortcut(launcher, itemInfo, originalView, pkg)
        }

    val RENAME_APP: SystemShortcut.Factory<NothingLauncher> =
        SystemShortcut.Factory { launcher, itemInfo, originalView ->
            val component = itemInfo.targetComponent ?: return@Factory null
            if (itemInfo.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) return@Factory null
            RenameAppShortcut(launcher, itemInfo, originalView, component)
        }

    private class HideInVaultShortcut(
        launcher: NothingLauncher,
        itemInfo: ItemInfo,
        originalView: View,
        private val pkg: String
    ) : SystemShortcut<NothingLauncher>(
        R.drawable.ic_eye_off,
        R.string.nt_shortcut_hide_vault,
        launcher,
        itemInfo,
        originalView
    ) {
        override fun onClick(view: View) {
            dismissTaskMenuView(mTarget)
            val prefs = NothingPrefs.get(mTarget)
            prefs.hiddenPackages = prefs.hiddenPackages + pkg
            mTarget.refreshHiddenApps()
            Toast.makeText(mTarget, R.string.nt_shortcut_hidden_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private class RenameAppShortcut(
        launcher: NothingLauncher,
        itemInfo: ItemInfo,
        originalView: View,
        private val component: ComponentName
    ) : SystemShortcut<NothingLauncher>(
        R.drawable.ic_note,
        R.string.nt_shortcut_rename,
        launcher,
        itemInfo,
        originalView
    ) {
        override fun onClick(view: View) {
            dismissTaskMenuView(mTarget)
            val prefs = NothingPrefs.get(mTarget)
            val key = component.flattenToString()
            val current = prefs.customAppLabels[key] ?: mItemInfo.title?.toString().orEmpty()
            val ctx = mTarget
            val padH = (20 * ctx.resources.displayMetrics.density).toInt()
            val padV = (8 * ctx.resources.displayMetrics.density).toInt()
            val input = EditText(ctx).apply {
                setText(current)
                setSelection(text?.length ?: 0)
                hint = ctx.getString(R.string.nt_rename_app_hint)
                setSingleLine(true)
            }
            val box = FrameLayout(ctx).apply {
                setPadding(padH, padV, padH, padV)
                addView(
                    input,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.nt_shortcut_rename)
                .setView(box)
                .setNeutralButton(R.string.nt_reset) { _, _ ->
                    val map = prefs.customAppLabels.toMutableMap()
                    map.remove(key)
                    prefs.customAppLabels = map
                    NothingIconLogic.refreshAll(ctx)
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val label = input.text?.toString()?.trim().orEmpty()
                    val map = prefs.customAppLabels.toMutableMap()
                    if (label.isEmpty()) map.remove(key) else map[key] = label
                    prefs.customAppLabels = map
                    NothingIconLogic.refreshAll(ctx)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
