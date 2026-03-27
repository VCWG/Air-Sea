/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ADS-B Exchange data source via the RapidAPI proxy.
 *
 * <p>Uses the same readsb/tar1090 JSON response schema as the other network
 * sources, but authenticates with {@code X-RapidAPI-Key} /
 * {@code X-RapidAPI-Host} headers instead of a Bearer token.
 *
 * <p>The API caps the query radius at 100 nautical miles; bounding boxes
 * larger than that are clamped automatically.
 */
public class AdsbExchangeSource extends AbstractReadsbSource {

    private static final String TAG = "AdsbExchangeSource";
    private static final String RAPIDAPI_HOST = "adsbexchange-com1.p.rapidapi.com";
    private static final String BASE_URL      = "https://" + RAPIDAPI_HOST;
    /** ADS-B Exchange RapidAPI hard limit. */
    private static final int MAX_DIST_NM = 100;

    private volatile HttpURLConnection pendingConn = null;

    @Override
    public String getName() { return "ADS-B Exchange"; }

    @Override
    public boolean requiresApiKey() { return true; }

    @Override
    String getBaseUrl() { return BASE_URL; }

    @Override
    public void cancel() {
        HttpURLConnection c = pendingConn;
        if (c != null) c.disconnect();
    }

    @Override
    public void fetch(String apiKey, String cred2,
                      double minLat, double maxLat,
                      double minLon, double maxLon,
                      Callback callback) {
        double centerLat = (minLat + maxLat) / 2.0;
        double centerLon = (minLon + maxLon) / 2.0;

        // Compute bounding-circle radius, capped at the 100 nm API limit
        double halfLat = (maxLat - minLat) / 2.0;
        double halfLon = (maxLon - minLon) / 2.0;
        double latNm   = halfLat * 60.0;
        double lonNm   = halfLon * 60.0 * Math.cos(Math.toRadians(centerLat));
        int distNm = (int) Math.min(MAX_DIST_NM,
                Math.max(1, Math.ceil(Math.sqrt(latNm * latNm + lonNm * lonNm))));

        String urlStr = BASE_URL + "/v2/lat/" + centerLat
                + "/lon/" + centerLon + "/dist/" + distNm;
        Log.d(TAG, "fetch: " + urlStr);

        try {
            HttpURLConnection conn =
                    (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "AirSea-ATAK/1.0");
            conn.setRequestProperty("X-RapidAPI-Key",  apiKey != null ? apiKey : "");
            conn.setRequestProperty("X-RapidAPI-Host", RAPIDAPI_HOST);
            pendingConn = conn;
            String response;
            try {
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                response = sb.toString();
            } finally {
                pendingConn = null;
                conn.disconnect();
            }
            callback.onResult(parseReadsb(response));
        } catch (Exception e) {
            Log.e(TAG, "fetch error", e);
            callback.onError(e.getMessage() != null ? e.getMessage() : "Fetch failed");
        }
    }
}
