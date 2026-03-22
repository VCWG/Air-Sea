/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketExtension;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AisStreamClient {

    private static final String TAG = "AisStreamClient";
    private static final String WS_URL = "wss://stream.aisstream.io/v0/stream";

    public interface Listener {
        void onConnected();
        void onShipName(int mmsi, String name);
        void onShipPosition(int mmsi, String shipName, double lat, double lon,
                double cog, double sog, int rot, int trueHeading,
                int navStatus, int shipType, double draught,
                String destination, String eta, int imoNumber);
        void onError(String error);
        void onDisconnected();
    }

    private final Listener listener;
    private WebSocket webSocket;
    private volatile boolean stopped = false;

    // Cache static data keyed by MMSI
    private final Map<Integer, StaticData> staticDataCache =
            new ConcurrentHashMap<>();

    static class StaticData {
        String name = "";
        int imoNumber = -1;
        int shipType = -1;
        double draught = -1;
        String destination = "";
        String eta = "";
    }

    public AisStreamClient(Listener listener) {
        this.listener = listener;
    }

    public void connect(String apiKey, double[][] boundingBox) {
        final String subscriptionMsg = buildSubscription(apiKey, boundingBox);
        Log.d(TAG, "Subscription message built");
        stopped = false;

        new Thread(() -> {
            try {
                // Assign webSocket before connect() so disconnect() can interrupt
                // an in-progress connection attempt (webSocket would otherwise be
                // null until connect() returns, making disconnect() a no-op).
                webSocket = new WebSocketFactory()
                        .setConnectionTimeout(10000)
                        .createSocket(WS_URL)
                        .addExtension(WebSocketExtension.PERMESSAGE_DEFLATE)
                        .setPingInterval(25 * 1000)
                        .addListener(new WebSocketAdapter() {
                            @Override
                            public void onConnected(WebSocket ws,
                                    Map<String, List<String>> headers) {
                                if (stopped) return;
                                Log.d(TAG, "WebSocket connected");
                                for (Map.Entry<String, List<String>> h
                                        : headers.entrySet()) {
                                    Log.d(TAG, "  Header: " + h.getKey()
                                            + " = " + h.getValue());
                                }
                                ws.sendText(subscriptionMsg);
                                Log.d(TAG, "Subscription sent");
                                listener.onConnected();
                            }

                            @Override
                            public void onStateChanged(WebSocket ws,
                                    WebSocketState newState) {
                                Log.d(TAG, "State: " + newState);
                            }

                            @Override
                            public void onFrame(WebSocket ws,
                                    WebSocketFrame frame) {
                                Log.d(TAG, "Frame: opcode=" + frame.getOpcode()
                                        + " fin=" + frame.getFin()
                                        + " len=" + frame.getPayloadLength());
                            }

                            @Override
                            public void onTextMessage(WebSocket ws,
                                    String text) {
                                Log.d(TAG, "Received: " + text.substring(0,
                                        Math.min(text.length(), 300)));
                                parseMessage(text);
                            }

                            @Override
                            public void onBinaryMessage(WebSocket ws,
                                    byte[] binary) {
                                String text = new String(binary,
                                        java.nio.charset.StandardCharsets.UTF_8);
                                Log.d(TAG, "Received: " + text.substring(0,
                                        Math.min(text.length(), 300)));
                                parseMessage(text);
                            }

                            @Override
                            public void onFrameError(WebSocket ws,
                                    WebSocketException cause,
                                    WebSocketFrame frame) {
                                Log.e(TAG, "Frame error", cause);
                            }

                            @Override
                            public void onMessageDecompressionError(
                                    WebSocket ws, WebSocketException cause,
                                    byte[] compressed) {
                                Log.e(TAG, "Decompression error, "
                                        + compressed.length + " bytes", cause);
                            }

                            @Override
                            public void onError(WebSocket ws,
                                    WebSocketException cause) {
                                Log.e(TAG, "WebSocket error", cause);
                                listener.onError(cause.getMessage() != null
                                        ? cause.getMessage()
                                        : "Connection error");
                            }

                            @Override
                            public void onDisconnected(WebSocket ws,
                                    WebSocketFrame serverFrame,
                                    WebSocketFrame clientFrame,
                                    boolean closedByServer) {
                                Log.d(TAG, "WebSocket disconnected, "
                                        + "closedByServer=" + closedByServer);
                                if (!stopped) {
                                    listener.onDisconnected();
                                }
                            }

                            @Override
                            public void onUnexpectedError(WebSocket ws,
                                    WebSocketException cause) {
                                Log.e(TAG, "Unexpected error", cause);
                            }
                        });
                webSocket.connect();
            } catch (Exception e) {
                Log.e(TAG, "WebSocket connect failed", e);
                if (!stopped) {
                    listener.onError(e.getMessage() != null
                            ? e.getMessage() : "Connection failed");
                }
            }
        }, "AIS-WebSocket").start();
    }

    public void disconnect() {
        stopped = true;
        if (webSocket != null) {
            webSocket.disconnect();
            webSocket = null;
        }
    }

    private String buildSubscription(String apiKey, double[][] boundingBox) {
        try {
            JSONObject sub = new JSONObject();
            sub.put("APIKey", apiKey);

            JSONArray corner1 = new JSONArray();
            corner1.put(boundingBox[0][0]);
            corner1.put(boundingBox[0][1]);

            JSONArray corner2 = new JSONArray();
            corner2.put(boundingBox[1][0]);
            corner2.put(boundingBox[1][1]);

            JSONArray box = new JSONArray();
            box.put(corner1);
            box.put(corner2);

            JSONArray boxes = new JSONArray();
            boxes.put(box);

            sub.put("BoundingBoxes", boxes);

            JSONArray filters = new JSONArray();
            filters.put("PositionReport");
            filters.put("ShipStaticData");
            sub.put("FilterMessageTypes", filters);

            return sub.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error building subscription", e);
            return "{}";
        }
    }

    private void parseMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);

            if (msg.has("error")) {
                listener.onError(msg.getString("error"));
                return;
            }

            String type = msg.optString("MessageType", "");
            if (!"PositionReport".equals(type)
                    && !"ShipStaticData".equals(type)) {
                Log.d(TAG, "Ignoring message type: " + type);
                return;
            }

            JSONObject metadata = msg.optJSONObject("MetaData");
            if (metadata == null) {
                Log.w(TAG, "No MetaData in message");
                return;
            }
            int mmsi = metadata.getInt("MMSI");
            String shipName = metadata.optString("ShipName", "").trim();
            if (shipName.isEmpty()) shipName = "MMSI-" + mmsi;

            if ("ShipStaticData".equals(type)) {
                JSONObject ssd = msg.getJSONObject("Message")
                        .getJSONObject("ShipStaticData");
                StaticData sd = staticDataCache.get(mmsi);
                if (sd == null) sd = new StaticData();

                String ssdName = ssd.optString("Name", "").trim();
                if (!ssdName.isEmpty()) {
                    sd.name = ssdName;
                    listener.onShipName(mmsi, ssdName);
                }

                sd.imoNumber = ssd.optInt("ImoNumber", -1);
                sd.shipType = ssd.optInt("Type", -1);
                sd.draught = ssd.optDouble("MaximumStaticDraught", -1);
                sd.destination = ssd.optString("Destination", "").trim();

                JSONObject etaObj = ssd.optJSONObject("Eta");
                if (etaObj != null) {
                    int month = etaObj.optInt("Month", 0);
                    int day = etaObj.optInt("Day", 0);
                    int hour = etaObj.optInt("Hour", 24);
                    int minute = etaObj.optInt("Minute", 60);
                    if (month > 0 && month <= 12 && day > 0 && day <= 31
                            && hour < 24 && minute < 60) {
                        sd.eta = String.format("%02d/%02d %02d:%02d",
                                month, day, hour, minute);
                    } else {
                        sd.eta = "";
                    }
                }

                staticDataCache.put(mmsi, sd);
                return;
            }

            if (!"PositionReport".equals(type)) return;

            JSONObject posReport = msg.getJSONObject("Message")
                    .getJSONObject("PositionReport");

            double lat = posReport.getDouble("Latitude");
            double lon = posReport.getDouble("Longitude");
            double cog = posReport.optDouble("Cog", 0);
            double sog = posReport.optDouble("Sog", 0);
            int rot = posReport.optInt("RateOfTurn", 0);
            int heading = posReport.optInt("TrueHeading", 511);
            int navStatus = posReport.optInt("NavigationalStatus", -1);

            StaticData sd = staticDataCache.get(mmsi);

            // Prefer cached static name over MetaData name
            if (sd != null && !sd.name.isEmpty()) {
                shipName = sd.name;
            }

            int shipType = sd != null ? sd.shipType : -1;
            double draught = sd != null ? sd.draught : -1;
            String destination = sd != null ? sd.destination : "";
            String eta = sd != null ? sd.eta : "";
            int imoNumber = sd != null ? sd.imoNumber : -1;

            listener.onShipPosition(mmsi, shipName, lat, lon,
                    cog, sog, rot, heading,
                    navStatus, shipType, draught,
                    destination, eta, imoNumber);
        } catch (Exception e) {
            Log.w(TAG, "Error parsing AIS message: " + e.getMessage());
        }
    }
}
