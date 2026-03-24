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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * ADS-B data source: OpenSky Network (https://opensky-network.org)
 * Anonymous access: 400 credits/day, 10-second resolution.
 * Authenticated access: 4,000 credits/day, 5-second resolution.
 *   Authentication uses OAuth2 Client Credentials flow — obtain a
 *   client_id and client_secret from your OpenSky account page.
 * Uses bounding-box REST endpoint; returns state vectors (altitude
 * in metres, speed in m/s — converted to feet/knots on parse).
 *
 * State vector array indices:
 *  0  icao24      1  callsign    2  origin_country
 *  3  time_pos    4  last_contact 5  longitude
 *  6  latitude    7  baro_alt(m) 8  on_ground
 *  9  velocity(m/s) 10 true_track 11 vertical_rate
 *  12 sensors     13 geo_alt(m)  14 squawk
 *  15 spi         16 pos_source  17 category (plain int 0-20, NOT nibble-encoded)
 */
public class OpenSkySource extends AbstractReadsbSource {

    private static final String TAG = "OpenSkySource";

    private static final String STATES_URL =
            "https://opensky-network.org/api/states/all";
    private static final String TOKEN_URL =
            "https://auth.opensky-network.org/auth/realms/opensky-network"
            + "/protocol/openid-connect/token";

    /** Cached bearer token and its expiry (epoch ms). */
    private String cachedToken = null;
    private long tokenExpiryMs = 0L;

    @Override
    String getBaseUrl() { return ""; } // unused — fetch() is fully overridden

    public String getName() {
        return "OpenSky";
    }

    @Override
    public boolean requiresApiKey() {
        return false; // optional; anonymous access available
    }

    @Override
    public void fetch(String clientId, String clientSecret,
                      double minLat, double maxLat,
                      double minLon, double maxLon,
                      Callback callback) {
        String urlStr = STATES_URL
                + "?lamin=" + minLat + "&lomin=" + minLon
                + "&lamax=" + maxLat + "&lomax=" + maxLon
                + "&extended=1";
        Log.d(TAG, "fetch: " + urlStr);

        try {
            String bearer = null;
            boolean hasCredentials = clientId != null && !clientId.isEmpty()
                    && clientSecret != null && !clientSecret.isEmpty();
            if (hasCredentials) {
                bearer = acquireToken(clientId, clientSecret);
            }
            String response = httpGet(urlStr, bearer);
            callback.onResult(parseStateVectors(response));
        } catch (Exception e) {
            Log.e(TAG, "fetch error", e);
            callback.onError(e.getMessage() != null ? e.getMessage() : "Fetch failed");
        }
    }

    /**
     * Returns a valid bearer token, fetching a new one via the OAuth2
     * client credentials flow if the cached token is absent or expiring soon.
     */
    private synchronized String acquireToken(String clientId, String clientSecret)
            throws Exception {
        // Refresh 60 s before actual expiry to avoid using a stale token
        if (cachedToken != null
                && System.currentTimeMillis() < tokenExpiryMs - 60_000L) {
            return cachedToken;
        }

        Log.d(TAG, "Fetching new OAuth2 token for OpenSky");
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8");

        HttpURLConnection conn = (HttpURLConnection)
                new URL(TOKEN_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("OpenSky token request failed: HTTP " + code);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        conn.disconnect();

        JSONObject resp = new JSONObject(sb.toString());
        if (!resp.has("access_token")) {
            throw new Exception("OpenSky token response missing access_token");
        }
        cachedToken = resp.getString("access_token");
        int expiresIn = resp.optInt("expires_in", 1800);
        tokenExpiryMs = System.currentTimeMillis() + expiresIn * 1000L;
        Log.d(TAG, "OpenSky token acquired, expires in " + expiresIn + "s");
        return cachedToken;
    }

    /**
     * OpenSky category field is a plain integer 0–20 mapping directly to
     * ADS-B emitter category values per the OpenSky REST API spec.
     */
    private static String decodeOpenSkyCategory(int cat) {
        switch (cat) {
            case 2:  return "Light";
            case 3:  return "Small";
            case 4:  return "Large";
            case 5:  return "High Vortex Large";
            case 6:  return "Heavy";
            case 7:  return "High Performance";
            case 8:  return "Rotorcraft";
            case 9:  return "Glider/Sailplane";
            case 10: return "Lighter-than-Air";
            case 11: return "Parachutist/Skydiver";
            case 12: return "Ultralight/Hang-glider";
            case 14: return "UAV";
            case 15: return "Space Vehicle";
            case 16: return "Emergency Vehicle";
            case 17: return "Service Vehicle";
            default: return "";
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

            // baro altitude: metres → feet
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
            // vertical_rate: m/s → ft/min
            if (!s.isNull(11)) {
                a.verticalRateFpm = s.getDouble(11) * 196.85;
            }
            // category: integer emitter category
            if (s.length() > 17 && !s.isNull(17)) {
                a.category = decodeOpenSkyCategory(s.optInt(17, 0));
            }
            result.add(a);
        }
        return result;
    }
}
