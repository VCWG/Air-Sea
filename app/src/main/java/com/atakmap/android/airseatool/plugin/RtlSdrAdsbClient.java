/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

/**
 * ADS-B client using a locally running rtl_tcp server (127.0.0.1:1234).
 *
 * The rtl_tcp server handles the physical USB RTL-SDR dongle. A compatible
 * server app (e.g. "RTL-SDR Driver" on the Play Store) must be running before
 * this client is started.
 *
 * Uses the same {@link AdsbStreamClient.Listener} interface as the network
 * clients so {@link AirSeaTool} needs no new listener methods.
 *
 * Tunes to 1090 MHz at 2 Msps, continuously decodes Mode-S frames,
 * and calls the listener for each aircraft detected.
 */
public class RtlSdrAdsbClient {

    private static final String TAG         = "RtlSdrAdsbClient";
    private static final String SOURCE_NAME = "USB RTL-SDR";

    // After this many consecutive ECONNREFUSED on the known port, run a fresh
    // discovery scan — the RTL-SDR Driver app may have restarted on a new port.
    private static final int REDISCOVER_AFTER = 3;

    private final AdsbStreamClient.Listener listener;
    private final RtlSdrAdsbDecoder.PpmCallback ppmCallback;
    private String currentHost;
    private int    currentPort;
    private final int gainTenthsDb;
    private final int ppmOffset;
    private RtlTcpClient tcpClient;
    private volatile boolean running = false;
    private Thread thread;

    public RtlSdrAdsbClient(AdsbStreamClient.Listener listener,
                            String host, int port, int gainTenthsDb, int ppmOffset,
                            RtlSdrAdsbDecoder.PpmCallback ppmCallback) {
        this.listener      = listener;
        this.ppmCallback   = ppmCallback;
        this.currentHost   = host;
        this.currentPort   = port;
        this.gainTenthsDb  = gainTenthsDb;
        this.ppmOffset     = ppmOffset;
    }

    public RtlSdrAdsbClient(AdsbStreamClient.Listener listener,
                            String host, int port, int gainTenthsDb, int ppmOffset) {
        this(listener, host, port, gainTenthsDb, ppmOffset, null);
    }

    public RtlSdrAdsbClient(AdsbStreamClient.Listener listener,
                            String host, int port, int gainTenthsDb) {
        this(listener, host, port, gainTenthsDb, 0, null);
    }

    public RtlSdrAdsbClient(AdsbStreamClient.Listener listener,
                            String host, int port) {
        this(listener, host, port, RtlTcpClient.DEFAULT_GAIN_TENTHS_DB, 0, null);
    }

    /** Connect to the local rtl_tcp server and start streaming ADS-B. Auto-reconnects on drop. */
    public void start() {
        running = true;
        thread = new Thread(() -> {
            int refusedCount = 0;
            boolean errorReported = false; // true once we've told the UI the server is gone

            while (running) {
                tcpClient = new RtlTcpClient(currentHost, currentPort, gainTenthsDb, ppmOffset);
                try {
                    tcpClient.connect(1_090_000_000L, 2_000_000);

                    refusedCount = 0; // successful connect — reset counters
                    errorReported = false;
                    listener.onConnected(SOURCE_NAME);
                    Log.d(TAG, "ADS-B streaming started");

                    // Fresh decoder on each connection
                    RtlSdrAdsbDecoder decoder = new RtlSdrAdsbDecoder(aircraft -> {
                        if (!running) return;
                        listener.onAircraft(aircraft);
                    }, estimatedPpm -> {
                        Log.d(TAG, "Auto-PPM estimated: " + estimatedPpm + " ppm");
                        if (ppmCallback != null) ppmCallback.onPpmEstimated(estimatedPpm);
                    });

                    final long[] lastReport     = {System.currentTimeMillis()};
                    final int[]  countSinceLast = {0};

                    tcpClient.stream((buf, len) -> {
                        if (!running) return;
                        decoder.process(buf, len);
                        countSinceLast[0]++;
                        long now = System.currentTimeMillis();
                        if (now - lastReport[0] >= 2000) {
                            listener.onPollComplete(SOURCE_NAME, countSinceLast[0]);
                            countSinceLast[0] = 0;
                            lastReport[0] = now;
                        }
                    });

                    Log.d(TAG, "ADS-B stream ended cleanly");

                } catch (Exception e) {
                    if (!running) break;
                    String msg = e.getMessage();
                    Log.w(TAG, "ADS-B stream error, will reconnect: " + msg);

                    if (msg != null && msg.contains("Connection refused")) {
                        refusedCount++;
                        if (!errorReported) {
                            errorReported = true;
                            listener.onError(SOURCE_NAME,
                                    "RTL-TCP not running — ensure RTL-SDR Driver is open "
                                    + "and exempt from battery optimization");
                        }

                        if (refusedCount >= REDISCOVER_AFTER) {
                            refusedCount = 0;
                            Log.d(TAG, "ADS-B port " + currentPort
                                    + " consistently refused — running rediscovery scan");
                            RtlTcpDiscovery.Result r = RtlTcpDiscovery.find();
                            if (r != null && running) {
                                Log.d(TAG, "ADS-B rediscovered RTL-SDR Driver on "
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
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                Log.d(TAG, "ADS-B reconnecting...");
            }
            Log.d(TAG, "ADS-B streaming stopped");
            listener.onDisconnected(SOURCE_NAME);
        }, "RTL-ADSB");
        thread.start();
    }

    public void stop() {
        running = false;
        if (tcpClient != null) tcpClient.disconnect();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(3000); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }
}
