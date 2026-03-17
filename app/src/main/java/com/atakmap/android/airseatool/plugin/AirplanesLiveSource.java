/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * ADS-B data source: airplanes.live (https://airplanes.live)
 * Free, no API key required. Uses /v2/point/{lat}/{lon}/{radius} endpoint.
 * Same readsb/tar1090 JSON format as adsb.fi.
 */
public class AirplanesLiveSource extends AbstractReadsbSource {

    private static final String TAG = "AirplanesLiveSource";

    @Override
    public String getName() {
        return "airplanes.live";
    }

    @Override
    String getBaseUrl() {
        return "https://api.airplanes.live";
    }

    /**
     * airplanes.live uses /v2/point/{lat}/{lon}/{radius} instead of
     * /v2/lat/{lat}/lon/{lon}/dist/{dist}, so we override fetch directly.
     */
    @Override
    public void fetch(String cred1, String cred2,
                      double minLat, double maxLat,
                      double minLon, double maxLon,
                      Callback callback) {
        double centerLat = (minLat + maxLat) / 2.0;
        double centerLon = (minLon + maxLon) / 2.0;

        double halfLat = (maxLat - minLat) / 2.0;
        double halfLon = (maxLon - minLon) / 2.0;
        double latNm = halfLat * 60.0;
        double lonNm = halfLon * 60.0 * Math.cos(Math.toRadians(centerLat));
        double radiusNm = Math.sqrt(latNm * latNm + lonNm * lonNm);
        radiusNm = Math.min(250, Math.max(1, radiusNm));

        String urlStr = "https://api.airplanes.live/v2/point/"
                + centerLat + "/" + centerLon + "/" + (int) Math.ceil(radiusNm);
        Log.d(TAG, "fetch: " + urlStr);

        try {
            String response = httpGet(urlStr, null);
            List<Aircraft> result = parseReadsb(response);
            callback.onResult(result);
        } catch (Exception e) {
            Log.e(TAG, "fetch error", e);
            callback.onError(e.getMessage() != null ? e.getMessage() : "Fetch failed");
        }
    }
}
