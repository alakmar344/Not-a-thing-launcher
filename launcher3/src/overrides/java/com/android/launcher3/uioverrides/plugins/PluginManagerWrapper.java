/*
 * Copyright (C) 2018 The Android Open Source Project
 * Modifications copyright (C) 2026 Not a Thing Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.launcher3.uioverrides.plugins;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceDataStore;

import com.android.launcher3.R;
import com.android.launcher3.util.MainThreadInitializedObject;
import com.android.systemui.plugins.CustomWidgetPlugin;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Plugin manager for a third-party build of Launcher3.
 *
 * <p>The stock (non-Quickstep) build connects no plugins at all, because SystemUI plugins require a
 * platform signature. Not a Thing instead ships its plugins in-process: every class listed in
 * {@code R.array.custom_widget_providers} is instantiated (constructor taking a {@link Context})
 * and connected as a {@link CustomWidgetPlugin}, so the dot-matrix elements show up in the stock
 * widget picker and behave like any other widget (drag, resize, remove).
 */
public class PluginManagerWrapper {

    public static final MainThreadInitializedObject<PluginManagerWrapper> INSTANCE =
            new MainThreadInitializedObject<>(PluginManagerWrapper::new);

    private static final String TAG = "PluginManagerWrapper";
    private static final String PREFIX_PLUGIN_ENABLED = "PLUGIN_ENABLED_";
    public static final String PLUGIN_CHANGED = "com.android.systemui.action.PLUGIN_CHANGED";

    private final Context mContext;
    private List<CustomWidgetPlugin> mWidgetPlugins;

    private PluginManagerWrapper(Context c) {
        mContext = c.getApplicationContext() != null ? c.getApplicationContext() : c;
    }

    public void addPluginListener(PluginListener<? extends Plugin> listener, Class<?> pluginClass) {
        addPluginListener(listener, pluginClass, false);
    }

    @SuppressWarnings("unchecked")
    public void addPluginListener(PluginListener<? extends Plugin> listener, Class<?> pluginClass,
            boolean allowMultiple) {
        if (pluginClass != CustomWidgetPlugin.class) return;
        PluginListener<CustomWidgetPlugin> widgetListener =
                (PluginListener<CustomWidgetPlugin>) listener;
        for (CustomWidgetPlugin plugin : widgetPlugins()) {
            widgetListener.onPluginConnected(plugin, mContext);
        }
    }

    public void removePluginListener(PluginListener<? extends Plugin> listener) { }

    public Set<String> getPluginActions() {
        return Collections.emptySet();
    }

    public PreferenceDataStore getPluginEnabler() {
        return new PreferenceDataStore() { };
    }

    public static String pluginEnabledKey(ComponentName cn) {
        return PREFIX_PLUGIN_ENABLED + cn.flattenToString();
    }

    public static boolean hasPlugins(Context context) {
        return false;
    }

    private List<CustomWidgetPlugin> widgetPlugins() {
        if (mWidgetPlugins != null) return mWidgetPlugins;
        List<CustomWidgetPlugin> plugins = new ArrayList<>();
        for (String name : mContext.getResources().getStringArray(R.array.custom_widget_providers)) {
            try {
                Class<?> cls = Class.forName(name);
                plugins.add((CustomWidgetPlugin) cls.getDeclaredConstructor(Context.class)
                        .newInstance(mContext));
            } catch (ReflectiveOperationException | ClassCastException e) {
                Log.e(TAG, "Could not create custom widget " + name, e);
            }
        }
        mWidgetPlugins = Collections.unmodifiableList(plugins);
        return mWidgetPlugins;
    }
}
