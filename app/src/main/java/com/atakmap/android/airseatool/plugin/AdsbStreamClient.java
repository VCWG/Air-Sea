/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * Polling client for ADS-B data sources. Calls the selected {@link AdsbSource}
 * at the specified interval and notifies the {@link Listener} with each aircraft.
 */
public class AdsbStreamClient {

    private static final String TAG = "AdsbStreamClient";

    public interface Listener {
        void onConnected(String sourceName);
        void onAircraft(Aircraft aircraft);
        void onPollComplete(String sourceName, int count);
        void onError(String sourceName, String error);
        void onDisconnected(String sourceName);
    }

    private final AdsbSource source;
    private final Listener listener;
    private volatile boolean running = false;
    private Thread thread;

    public AdsbStreamClient(AdsbSource source, Listener listener) {
        this.source = source;
        this.listener = listener;
    }

    public void start(final String cred1, final String cred2,
                      final double minLat, final double maxLat,
                      final double minLon, final double maxLon,
                      final int intervalSeconds) {
        running = true;
        thread = new Thread(() -> {
            Log.d(TAG, "Starting poll loop for " + source.getName()
                    + " every " + intervalSeconds + "s");
            listener.onConnected(source.getName());

            while (running) {
                source.fetch(cred1, cred2, minLat, maxLat, minLon, maxLon,
                        new AdsbSource.Callback() {
                            @Override
                            public void onResult(List<Aircraft> aircraft) {
                                Log.d(TAG, source.getName() + " returned "
                                        + aircraft.size() + " aircraft");
                                for (Aircraft a : aircraft) {
                                    if (!running) break;
                                    listener.onAircraft(a);
                                }
                                if (running)
                                    listener.onPollComplete(source.getName(),
                                            aircraft.size());
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, source.getName() + " error: " + error);
                                if (running) listener.onError(source.getName(), error);
                            }
                        });

                if (!running) break;
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
            }

            Log.d(TAG, source.getName() + " poll loop stopped");
            listener.onDisconnected(source.getName());
        }, "ADSB-Poller-" + source.getName());
        thread.start();
    }

    public void stop() {
        running = false;
        source.cancel();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
