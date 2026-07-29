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
 * Tunes to 161.988 MHz at 1.6 Msps, FM-demodulates, HDLC-decodes, and calls
 * the listener for each position report.
 */
public class RtlSdrAisClient {

    private static final String TAG = "RtlSdrAisClient";

    // After this many consecutive ECONNREFUSED on the known port, do a quick
    // rtl_tcp probe. Full port sweeps are too slow while we are reconnecting.
    private static final int QUICK_PROBE_AFTER = 3;
    private static final long ERROR_REPORT_AFTER_MS = 120_000L;
    // Match RTL_AIS_Driver defaults: tuner AGC + RTL digital AGC enabled.
    private static final boolean AIS_TUNER_AUTO_GAIN = true;
    private static final boolean AIS_RTL_AGC_ENABLED = true;
    private static final int ACQUIRE_MIN_DECODES = 3;

    public interface StatusCallback {
        void onStatus(String status);
    }

    private final AisStreamClient.Listener listener;
    private final RtlSdrAisDecoder.PpmCallback ppmCallback;
    private final StatusCallback statusCallback;
    private String currentHost;
    private int    currentPort;
    private final int gainTenthsDb;
    private final int ppmOffset;
    private RtlTcpClient tcpClient;
    private volatile boolean running = false;
    private Thread thread;

    public RtlSdrAisClient(AisStreamClient.Listener listener,
                           String host, int port, int gainTenthsDb, int ppmOffset,
                           RtlSdrAisDecoder.PpmCallback ppmCallback,
                           StatusCallback statusCallback) {
        this.listener      = listener;
        this.ppmCallback   = ppmCallback;
        this.statusCallback = statusCallback;
        this.currentHost   = host;
        this.currentPort   = port;
        this.gainTenthsDb  = gainTenthsDb;
        this.ppmOffset     = ppmOffset;
    }

    public RtlSdrAisClient(AisStreamClient.Listener listener,
                           String host, int port, int gainTenthsDb, int ppmOffset,
                           RtlSdrAisDecoder.PpmCallback ppmCallback) {
        this(listener, host, port, gainTenthsDb, ppmOffset, ppmCallback, null);
    }

    public RtlSdrAisClient(AisStreamClient.Listener listener,
                            String host, int port, int gainTenthsDb, int ppmOffset) {
        this(listener, host, port, gainTenthsDb, ppmOffset, null);
    }

    /** Connect to the local rtl_tcp server and start streaming AIS. Auto-reconnects on drop. */
    public void connect() {
        running = true;
        thread = new Thread(() -> {
            int refusedCount = 0;
            long outageStartedAt = 0L;
            boolean errorReported = false; // true once we've told the UI about a persistent outage
            final int[] lockedBox = {0};

            while (running) {
                tcpClient = new RtlTcpClient(currentHost, currentPort,
                        gainTenthsDb, ppmOffset,
                        AIS_TUNER_AUTO_GAIN, AIS_RTL_AGC_ENABLED);
                try {
                    tcpClient.connect(RtlSdrAisDecoder.CENTER_FREQ,
                                      RtlSdrAisDecoder.SAMPLE_RATE);

                    refusedCount = 0; // successful connect — reset counters
                    outageStartedAt = 0L;
                    errorReported = false;
                    listener.onConnected();
                    Log.d(TAG, "AIS streaming started (center="
                            + RtlSdrAisDecoder.CENTER_FREQ
                            + " rate=" + RtlSdrAisDecoder.SAMPLE_RATE
                            + " ppm=" + (ppmOffset >= 0 ? "+" : "") + ppmOffset + ")");

                    // Fresh decoder on each connection so state doesn't carry over.
                    final int[] decodeCount = {0};
                    final RtlSdrAisDecoder decoder = newDecoder(decodeCount, lockedBox);

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
                    long now = System.currentTimeMillis();
                    if (outageStartedAt == 0L) {
                        outageStartedAt = now;
                        notifyStatus("RTL-SDR reconnecting...");
                    }

                    if (msg != null && msg.contains("Connection refused")) {
                        refusedCount++;
                        if (refusedCount >= QUICK_PROBE_AFTER) {
                            refusedCount = 0;
                            Log.d(TAG, "AIS port " + currentPort
                                    + " consistently refused — probing known rtl_tcp ports");
                            RtlTcpDiscovery.Result r = RtlTcpDiscovery.findQuick(currentPort);
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

                    if (!errorReported
                            && now - outageStartedAt >= ERROR_REPORT_AFTER_MS) {
                        errorReported = true;
                        listener.onError("RTL-TCP unavailable — ensure RTL-SDR Driver is open "
                                + "and exempt from battery optimization");
                    }
                } finally {
                    try { tcpClient.disconnect(); } catch (Exception ignored) {}
                }

                if (!running) break;
                // Brief pause before reconnect so we don't spin if the server is restarting
                long delayMs = lockedBox[0] == 0 ? 1200 : 2000;
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { break; }
                Log.d(TAG, "AIS reconnecting...");
            }
            Log.d(TAG, "AIS streaming stopped");
            listener.onDisconnected();
        }, "RTL-AIS");
        thread.start();
    }

    public void disconnect() {
        running = false;
        if (tcpClient != null) tcpClient.disconnect();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(3000); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    private RtlSdrAisDecoder newDecoder(final int[] decodeCount,
                                        final int[] lockedBox) {
        return new RtlSdrAisDecoder(new RtlSdrAisDecoder.Callback() {
            @Override
            public void onPosition(int mmsi, String shipName,
                                   double lat, double lon, double cog, double sog,
                                   int rot, int heading, int navStatus, int shipType,
                                   double draught, String destination, String eta,
                                   int imoNumber) {
                if (!running) return;
                decodeCount[0]++;

                if (lockedBox[0] == 0 && decodeCount[0] >= ACQUIRE_MIN_DECODES) {
                    lockedBox[0] = 1;
                    Log.d(TAG, "AIS lock acquired");
                    if (ppmCallback != null) ppmCallback.onPpmEstimated(0);
                }

                listener.onShipPosition(mmsi,
                        shipName.isEmpty() ? "MMSI-" + mmsi : shipName,
                        lat, lon, cog, sog, rot, heading, navStatus,
                        shipType, draught, destination, eta, imoNumber);
            }

            @Override
            public void onShipName(int mmsi, String shipName) {
                if (!running || shipName == null || shipName.isEmpty()) return;
                listener.onShipName(mmsi, shipName);
            }
        });
    }

    private void notifyStatus(String status) {
        if (statusCallback != null) statusCallback.onStatus(status);
    }
}
