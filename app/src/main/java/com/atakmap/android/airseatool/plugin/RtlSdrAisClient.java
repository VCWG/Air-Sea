/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

/**
 * AIS client using a locally running rtl_tcp server (127.0.0.1:1234).
 *
 * The rtl_tcp server handles the physical USB RTL-SDR dongle. A compatible
 * server app (e.g. "RTL-SDR Driver" on the Play Store) must be running before
 * this client is started.
 *
 * Uses the same {@link AisStreamClient.Listener} interface so
 * {@link AirSeaTool} needs no new listener methods.
 *
 * Tunes to 162.0 MHz at 288 ksps, FM-demodulates, HDLC-decodes, and calls
 * the listener for each position report.
 */
public class RtlSdrAisClient {

    private static final String TAG = "RtlSdrAisClient";

    // After this many consecutive ECONNREFUSED on the known port, run a fresh
    // discovery scan — the RTL-SDR Driver app may have restarted on a new port.
    private static final int REDISCOVER_AFTER = 3;

    private final AisStreamClient.Listener listener;
    private String currentHost;
    private int    currentPort;
    private RtlTcpClient tcpClient;
    private volatile boolean running = false;

    public RtlSdrAisClient(AisStreamClient.Listener listener,
                           String host, int port) {
        this.listener    = listener;
        this.currentHost = host;
        this.currentPort = port;
    }

    /** Connect to the local rtl_tcp server and start streaming AIS. Auto-reconnects on drop. */
    public void connect() {
        running = true;
        new Thread(() -> {
            int refusedCount = 0;
            boolean errorReported = false; // true once we've told the UI the server is gone

            while (running) {
                tcpClient = new RtlTcpClient(currentHost, currentPort);
                try {
                    tcpClient.connect(RtlSdrAisDecoder.CENTER_FREQ,
                                      RtlSdrAisDecoder.SAMPLE_RATE);

                    refusedCount = 0; // successful connect — reset counters
                    errorReported = false;
                    listener.onConnected();
                    Log.d(TAG, "AIS streaming started");

                    // Fresh decoder on each connection so state doesn't carry over
                    RtlSdrAisDecoder decoder = new RtlSdrAisDecoder(
                            (mmsi, shipName, lat, lon, cog, sog, heading, navStatus) -> {
                        if (!running) return;
                        listener.onShipPosition(mmsi,
                                shipName.isEmpty() ? "MMSI-" + mmsi : shipName,
                                lat, lon, cog, sog, 0, heading,
                                navStatus, -1, -1, "", "", -1);
                    });

                    tcpClient.stream((buf, len) -> {
                        if (!running) return;
                        decoder.process(buf, len);
                    });

                    // stream() returned normally (server closed cleanly)
                    Log.d(TAG, "AIS stream ended cleanly");

                } catch (Exception e) {
                    if (!running) break; // intentional stop — exit loop
                    String msg = e.getMessage();
                    Log.w(TAG, "AIS stream error, will reconnect: " + msg);

                    if (msg != null && msg.contains("Connection refused")) {
                        refusedCount++;
                        if (!errorReported) {
                            errorReported = true;
                            listener.onError("RTL-TCP not running — ensure RTL-SDR Driver is open "
                                    + "and exempt from battery optimization");
                        }

                        if (refusedCount >= REDISCOVER_AFTER) {
                            refusedCount = 0;
                            Log.d(TAG, "AIS port " + currentPort
                                    + " consistently refused — running rediscovery scan");
                            RtlTcpDiscovery.Result r = RtlTcpDiscovery.find();
                            if (r != null && running) {
                                Log.d(TAG, "AIS rediscovered RTL-SDR Driver on "
                                        + r.host + ":" + r.port);
                                currentHost = r.host;
                                currentPort = r.port;
                            }
                        }
                    } else {
                        refusedCount = 0;
                    }
                } finally {
                    try { tcpClient.disconnect(); } catch (Exception ignored) {}
                }

                if (!running) break;
                // Brief pause before reconnect so we don't spin if the server is restarting
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                Log.d(TAG, "AIS reconnecting...");
            }
            Log.d(TAG, "AIS streaming stopped");
            listener.onDisconnected();
        }, "RTL-AIS").start();
    }

    public void disconnect() {
        running = false;
        if (tcpClient != null) tcpClient.disconnect();
    }
}
