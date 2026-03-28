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
import java.net.URLEncoder;

/**
 * HTTP polling client for VesselFinder LiveData API.
 * Polls https://api.vesselfinder.com/livedata at a configured interval and
 * delivers vessel position data to a {@link Listener}.
 */
public class VesselFinderClient {

    private static final String TAG = "VesselFinderClient";
    private static final String BASE_URL = "https://api.vesselfinder.com/livedata";

    public interface Listener {
        void onConnected();
        void onShipPosition(int mmsi, String name, double lat, double lon,
                double cog, double sog, int trueHeading, int navStatus,
                int shipType, double draught, String destination, String eta,
                int imoNumber);
        void onPollComplete(int count);
        void onError(String error);
    }

    private final Listener listener;
    private volatile boolean running = false;
    private Thread thread;
    private volatile HttpURLConnection currentConnection;

    public VesselFinderClient(Listener listener) {
        this.listener = listener;
    }

    public void start(final String apiKey, final int intervalSeconds) {
        running = true;
        thread = new Thread(() -> {
            Log.d(TAG, "VesselFinder poll loop starting, interval=" + intervalSeconds + "s");
            listener.onConnected();
            while (running) {
                poll(apiKey);
                if (!running) break;
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            Log.d(TAG, "VesselFinder poll loop stopped");
        }, "VesselFinder-Poller");
        thread.start();
    }

    public void stop() {
        running = false;
        HttpURLConnection conn = currentConnection;
        if (conn != null) conn.disconnect();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(3000); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    private void poll(String apiKey) {
        HttpURLConnection conn = null;
        try {
            String encodedKey = URLEncoder.encode(apiKey, "UTF-8");
            URL url = new URL(BASE_URL + "?userkey=" + encodedKey + "&format=json");
            Log.d(TAG, "Polling VesselFinder, key length=" + apiKey.length());
            conn = (HttpURLConnection) url.openConnection();
            currentConnection = conn;
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");

            int status = conn.getResponseCode();
            if (status != 200) {
                if (running) listener.onError("HTTP " + status);
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            currentConnection = null;

            if (running) parseResponse(sb.toString());

        } catch (java.net.SocketException e) {
            // disconnect() called during stop() — ignore
        } catch (Exception e) {
            if (running) {
                String msg = e.getMessage();
                listener.onError(msg != null ? msg : "Poll failed");
            }
        } finally {
            if (conn != null) conn.disconnect();
            currentConnection = null;
        }
    }

    private void parseResponse(String json) {
        try {
            // API returns a JSON object on error (e.g. {"error":"Invalid Userkey!"})
            if (json.trim().startsWith("{")) {
                JSONObject errObj = new JSONObject(json);
                String apiError = errObj.optString("error", "Unknown API error");
                if (running) listener.onError(apiError);
                return;
            }
            JSONArray vessels = new JSONArray(json);
            int count = 0;
            for (int i = 0; i < vessels.length(); i++) {
                if (!running) break;
                JSONObject vessel = vessels.optJSONObject(i);
                if (vessel == null) continue;

                JSONObject ais = vessel.optJSONObject("AIS");
                if (ais == null) continue;

                int mmsi = ais.optInt("MMSI", 0);
                if (mmsi == 0) continue;

                double lat = ais.optDouble("LATITUDE", Double.NaN);
                double lon = ais.optDouble("LONGITUDE", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

                String name = ais.optString("NAME", "").trim();
                if (name.isEmpty()) name = "MMSI-" + mmsi;

                double cog     = ais.optDouble("COURSE", 0);
                double sog     = ais.optDouble("SPEED", 0);
                int heading    = ais.optInt("HEADING", 511);
                int navStatus  = ais.optInt("NAVSTAT", -1);
                int shipType   = ais.optInt("TYPE", -1);
                double draught = ais.optDouble("DRAUGHT", -1);
                String dest    = ais.optString("DESTINATION", "").trim();
                String eta     = ais.optString("ETA", "").trim();
                int imo        = ais.optInt("IMO", -1);

                listener.onShipPosition(mmsi, name, lat, lon, cog, sog,
                        heading, navStatus, shipType, draught, dest, eta, imo);
                count++;
            }
            if (running) listener.onPollComplete(count);
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + e.getMessage());
            if (running) listener.onError("Parse error: " + e.getMessage());
        }
    }
}
