/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import java.io.File;
import android.content.Context;

public class PluginNativeLoader {

    private static final String TAG = "NativeLoader";
    private static String ndl = null;

    synchronized static public void init(final Context context) {
        if (ndl == null) {
            try {
                ndl = context.getPackageManager()
                        .getApplicationInfo(context.getPackageName(),
                                0).nativeLibraryDir;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "native library loading will fail, unable to grab the nativeLibraryDir from the package name");
            }
        }
    }

    public static void loadLibrary(final String name) {
        if (ndl != null) {
            final String lib = ndl + File.separator
                    + System.mapLibraryName(name);
            if (new File(lib).exists()) {
                System.load(lib);
            }
        } else {
            throw new IllegalArgumentException("NativeLoader not initialized");
        }
    }
}
