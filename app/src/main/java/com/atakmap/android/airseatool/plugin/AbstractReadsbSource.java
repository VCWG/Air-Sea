/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for readsb/tar1090-format ADS-B sources (adsb.fi, airplanes.live, adsb.lol).
 * All three use the same /v2/lat/{lat}/lon/{lon}/dist/{dist} endpoint and JSON schema.
 */
abstract class AbstractReadsbSource implements AdsbSource {

    private static final String TAG = "AbstractReadsbSource";

    /** Base URL without trailing slash, e.g. "https://opendata.adsb.fi" */
    abstract String getBaseUrl();

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public void fetch(String cred1, String cred2,
                      double minLat, double maxLat,
                      double minLon, double maxLon,
                      Callback callback) {
        double centerLat = (minLat + maxLat) / 2.0;
        double centerLon = (minLon + maxLon) / 2.0;
        double radiusNm = bboxToRadiusNm(minLat, maxLat, minLon, maxLon, centerLat);
        radiusNm = Math.min(250, Math.max(1, radiusNm));

        String urlStr = getBaseUrl() + "/v2/lat/" + centerLat
                + "/lon/" + centerLon + "/dist/" + (int) Math.ceil(radiusNm);
        Log.d(TAG, getName() + " fetch: " + urlStr);

        try {
            String response = httpGet(urlStr, null);
            callback.onResult(parseReadsb(response));
        } catch (Exception e) {
            Log.e(TAG, getName() + " fetch error", e);
            callback.onError(e.getMessage() != null ? e.getMessage() : "Fetch failed");
        }
    }

    private static double bboxToRadiusNm(double minLat, double maxLat,
                                          double minLon, double maxLon,
                                          double centerLat) {
        double halfLat = (maxLat - minLat) / 2.0;
        double halfLon = (maxLon - minLon) / 2.0;
        double latNm = halfLat * 60.0;
        double lonNm = halfLon * 60.0 * Math.cos(Math.toRadians(centerLat));
        return Math.sqrt(latNm * latNm + lonNm * lonNm);
    }

    static String httpGet(String urlStr, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "AirSea-ATAK/1.0");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Translate readsb category codes like "A2" to human-readable labels. */
    private static String decodeCategoryCode(String code) {
        if (code == null || code.length() != 2) return code;
        switch (code) {
            case "A1": return "Light";
            case "A2": return "Small";
            case "A3": return "Large";
            case "A4": return "High Vortex Large";
            case "A5": return "Heavy";
            case "A6": return "High Performance";
            case "A7": return "Rotorcraft";
            case "B1": return "Glider/Sailplane";
            case "B2": return "Lighter-than-Air";
            case "B3": return "Parachutist/Skydiver";
            case "B4": return "Ultralight/Hang-glider";
            case "B6": return "UAV";
            case "B7": return "Space Vehicle";
            case "C1": return "Emergency Vehicle";
            case "C2": return "Service Vehicle";
            case "C3": return "Ground Obstruction";
            default:   return code;
        }
    }

    static List<Aircraft> parseReadsb(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        // readsb API format uses "aircraft"; tar1090 internal format uses "ac"
        JSONArray ac = root.optJSONArray("aircraft");
        if (ac == null) ac = root.optJSONArray("ac");
        List<Aircraft> result = new ArrayList<>();
        if (ac == null) return result;

        for (int i = 0; i < ac.length(); i++) {
            JSONObject obj = ac.getJSONObject(i);
            if (!obj.has("lat") || !obj.has("lon")) continue;

            Aircraft a = new Aircraft();
            a.icao24 = obj.optString("hex", "").trim().toLowerCase();
            a.callsign = obj.optString("flight", "").trim();
            a.registration = obj.optString("r", "").trim();
            a.aircraftType = obj.optString("t", "").trim();
            a.lat = obj.getDouble("lat");
            a.lon = obj.getDouble("lon");
            a.trackDeg = obj.optDouble("track", 0);
            a.groundSpeedKnots = obj.optDouble("gs", 0);
            a.squawk = obj.optString("squawk", "").trim();

            // alt_baro can be a number or the string "ground"
            Object altObj = obj.opt("alt_baro");
            if (altObj instanceof Number) {
                a.altitudeFt = ((Number) altObj).doubleValue();
                a.onGround = false;
            } else {
                a.altitudeFt = 0;
                a.onGround = "ground".equals(altObj);
            }
            a.verticalRateFpm = obj.optDouble("baro_rate", 0);
            a.category = decodeCategoryCode(obj.optString("category", "").trim());
            result.add(a);
        }
        return result;
    }
}
