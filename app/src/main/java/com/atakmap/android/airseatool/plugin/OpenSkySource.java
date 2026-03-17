/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ADS-B data source: OpenSky Network (https://opensky-network.org)
 * Free anonymous access (400 credits/day). Optional API key used as Bearer token
 * for authenticated access (4,000 credits/day). Uses bounding-box REST endpoint.
 * Returns state vectors; altitude in meters, speed in m/s.
 *
 * State vector array indices:
 *  0  icao24      1  callsign    2  origin_country
 *  3  time_pos    4  last_contact 5  longitude
 *  6  latitude    7  baro_alt(m) 8  on_ground
 *  9  velocity(m/s) 10 true_track 11 vertical_rate
 *  12 sensors     13 geo_alt(m)  14 squawk
 *  15 spi         16 pos_source  17 category
 */
public class OpenSkySource implements AdsbSource {

    private static final String TAG = "OpenSkySource";
    private static final String BASE =
            "https://opensky-network.org/api/states/all";

    @Override
    public String getName() {
        return "OpenSky";
    }

    @Override
    public boolean requiresApiKey() {
        return false; // optional; anonymous access available
    }

    @Override
    public void fetch(String apiKey,
                      double minLat, double maxLat,
                      double minLon, double maxLon,
                      Callback callback) {
        String urlStr = BASE
                + "?lamin=" + minLat + "&lomin=" + minLon
                + "&lamax=" + maxLat + "&lomax=" + maxLon;
        Log.d(TAG, "fetch: " + urlStr);

        try {
            String response = AbstractReadsbSource.httpGet(urlStr,
                    (apiKey != null && !apiKey.isEmpty()) ? apiKey : null);
            callback.onResult(parseStateVectors(response));
        } catch (Exception e) {
            Log.e(TAG, "fetch error", e);
            callback.onError(e.getMessage() != null ? e.getMessage() : "Fetch failed");
        }
    }

    private static List<Aircraft> parseStateVectors(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray states = root.optJSONArray("states");
        List<Aircraft> result = new ArrayList<>();
        if (states == null) return result;

        for (int i = 0; i < states.length(); i++) {
            JSONArray s = states.getJSONArray(i);
            // latitude [6] and longitude [5] are required
            if (s.isNull(5) || s.isNull(6)) continue;

            Aircraft a = new Aircraft();
            a.icao24 = s.optString(0, "").trim().toLowerCase();
            a.callsign = s.optString(1, "").trim();
            a.lat = s.getDouble(6);
            a.lon = s.getDouble(5);
            a.onGround = !s.isNull(8) && s.getBoolean(8);

            // baro altitude: meters → feet
            if (!s.isNull(7)) {
                a.altitudeFt = s.getDouble(7) * 3.28084;
            }
            // velocity: m/s → knots
            if (!s.isNull(9)) {
                a.groundSpeedKnots = s.getDouble(9) * 1.94384;
            }
            if (!s.isNull(10)) {
                a.trackDeg = s.getDouble(10);
            }
            a.squawk = s.isNull(14) ? "" : s.optString(14, "").trim();
            result.add(a);
        }
        return result;
    }
}
