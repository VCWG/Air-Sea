/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import java.util.List;

public interface AdsbSource {
    String getName();
    boolean requiresApiKey();

    void fetch(String cred1, String cred2,
               double minLat, double maxLat,
               double minLon, double maxLon,
               Callback callback);

    interface Callback {
        void onResult(List<Aircraft> aircraft);
        void onError(String error);
    }
}
