/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds a running rtl_tcp server on this device.
 *
 * Strategy (fastest-first):
 *
 *   1. /proc/net/tcp6 + /proc/net/tcp — parse the kernel socket table to get
 *      exactly the ports that are in LISTEN state, then probe only those.
 *      Completes in milliseconds if the kernel file is readable (ATAK often
 *      has sufficient privilege; SELinux blocks it on some builds).
 *
 *   2. Two-phase port scan (fallback) — connect-only sweep across all 65535
 *      ports to find which ones accept connections, then probe those for the
 *      RTL0 magic header.  ECONNREFUSED is instant on loopback; on some
 *      Samsung/Android-14 builds closed ports time out instead, so we use a
 *      20 ms connect timeout (scan completes in ≤5 s).
 */
public class RtlTcpDiscovery {

    private static final String TAG                = "RtlTcpDiscovery";
    private static final int    THREAD_COUNT       = 256;
    private static final int    CONNECT_TIMEOUT_MS = 20;   // short: timeout ≤ ECONNREFUSED on Samsung
    private static final int    RTL0_TIMEOUT_MS    = 5_000; // dongle init can take 3-5 s
    private static final int    SWEEP_WAIT_S       = 8;    // loopback connect-sweep deadline
    private static final int    WIFI_WAIT_S        = 20;   // WiFi connect-sweep deadline

    public static final class Result {
        public final String host;
        public final int    port;
        Result(String host, int port) { this.host = host; this.port = port; }
    }

    /**
     * Find an rtl_tcp server on this device.
     * Blocks the calling thread; run on a background thread.
     */
    public static Result find() {
        return find(0);
    }

    /**
     * Find an rtl_tcp server on this device.
     * @param hintPort Port to probe first before any scanning (0 = no hint).
     * Blocks the calling thread; run on a background thread.
     */
    public static Result find(int hintPort) {
        // ── Hint port: try the caller's known port before anything else ─────
        if (hintPort > 0 && hintPort != RtlTcpClient.DEFAULT_PORT) {
            Log.d(TAG, "Trying hint port 127.0.0.1:" + hintPort);
            if (probeRtl0("127.0.0.1", hintPort))
                return new Result("127.0.0.1", hintPort);
        }

        // ── Method 1: read the kernel socket table ──────────────────────────
        List<Integer> procPorts = readProcNetListeningPorts();
        if (!procPorts.isEmpty()) {
            Log.d(TAG, "proc/net found " + procPorts.size()
                    + " listening port(s): " + procPorts);
            for (int port : procPorts) {
                if (probeRtl0("127.0.0.1", port)) {
                    Log.d(TAG, "Found rtl_tcp via proc/net on 127.0.0.1:" + port);
                    return new Result("127.0.0.1", port);
                }
            }
            Log.d(TAG, "proc/net ports probed — no rtl_tcp found");
        } else {
            Log.d(TAG, "proc/net unavailable or empty — falling back to port scan");
        }

        // ── Method 2: two-phase port scan fallback ───────────────────────────
        Result r = scanHost("127.0.0.1", SWEEP_WAIT_S);
        if (r != null) return r;

        List<String> wifiHosts = getNonLoopbackHosts();
        if (wifiHosts.isEmpty()) {
            Log.d(TAG, "No non-loopback interfaces; giving up");
            return null;
        }
        Log.d(TAG, "Scanning non-loopback hosts: " + wifiHosts);
        for (String host : wifiHosts) {
            r = scanHost(host, WIFI_WAIT_S);
            if (r != null) return r;
        }

        Log.d(TAG, "No rtl_tcp server found");
        return null;
    }

    // ── /proc/net parsing ────────────────────────────────────────────────────

    /**
     * Read /proc/net/tcp6 and /proc/net/tcp and return all ports in LISTEN
     * state (st == 0A).  Returns an empty list if the files are unreadable.
     */
    private static List<Integer> readProcNetListeningPorts() {
        List<Integer> ports = new ArrayList<>();
        String[] files = {"/proc/net/tcp6", "/proc/net/tcp"};
        for (String path : files) {
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    // Fields are whitespace-separated; index 0 = sl, 1 = local_addr, 3 = state
                    String[] f = line.split("\\s+");
                    if (f.length < 4) continue;
                    if (!"0A".equalsIgnoreCase(f[3])) continue; // not LISTEN
                    // local_addr = <hex_ip>:<hex_port>
                    int colon = f[1].lastIndexOf(':');
                    if (colon < 0) continue;
                    int port = Integer.parseInt(f[1].substring(colon + 1), 16);
                    if (port > 0 && !ports.contains(port)) ports.add(port);
                }
            } catch (Exception e) {
                Log.d(TAG, path + " unreadable: " + e.getMessage());
            }
        }
        return ports;
    }

    // ── Two-phase port scan ──────────────────────────────────────────────────

    private static Result scanHost(String host, int sweepWaitSeconds) {
        Log.d(TAG, "Trying " + host + ":1234 (fast path)");
        if (probeRtl0(host, 1234)) {
            Log.d(TAG, "Found rtl_tcp on " + host + ":1234");
            return new Result(host, 1234);
        }

        List<Integer> listening = connectSweep(host, sweepWaitSeconds);
        Log.d(TAG, host + ": sweep found " + listening.size()
                + " listening port(s): " + listening);

        for (int port : listening) {
            if (port == 1234) continue;
            if (probeRtl0(host, port)) {
                Log.d(TAG, "Found rtl_tcp on " + host + ":" + port);
                return new Result(host, port);
            }
        }
        return null;
    }

    /** Connect-only sweep: collect ports that accept TCP connections. */
    private static List<Integer> connectSweep(String host, int waitSeconds) {
        CopyOnWriteArrayList<Integer> found = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int port = 1; port <= 65535; port++) {
            if (port == 1234) continue;
            final int p = port;
            pool.submit(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, p), CONNECT_TIMEOUT_MS);
                    found.add(p);
                } catch (Exception ignored) {}
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        return new ArrayList<>(found);
    }

    /** Returns all active non-loopback IPv4 addresses on this device. */
    private static List<String> getNonLoopbackHosts() {
        List<String> hosts = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                for (NetworkInterface iface : Collections.list(ifaces)) {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    for (java.net.InetAddress addr :
                            Collections.list(iface.getInetAddresses())) {
                        if (addr instanceof java.net.Inet4Address
                                && !addr.isLoopbackAddress()) {
                            String ip = addr.getHostAddress();
                            if (!hosts.contains(ip)) hosts.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Error enumerating network interfaces: " + e.getMessage());
        }
        return hosts;
    }

    /**
     * Fast probe: tries only the hint port and the default port 1234.
     * Uses a shorter RTL0 read timeout (2 s) and no full port sweep.
     * Completes in milliseconds when the server is not running.
     */
    public static Result findQuick(int hintPort) {
        int[] ports = (hintPort > 0 && hintPort != 1234)
                ? new int[]{hintPort, 1234}
                : new int[]{1234};
        for (int port : ports) {
            if (probeRtl0("127.0.0.1", port, 2_000))
                return new Result("127.0.0.1", port);
        }
        return null;
    }

    /**
     * Diagnostic: try connecting to a specific port with increasing timeouts
     * (20 ms → 200 ms → 2000 ms) and log the outcome.  Call this once when
     * the Driver is known to be streaming to understand what connect timeout
     * is needed on this device.
     */
    public static void diagnose(int port) {
        int[] timeouts = {20, 200, 2_000};
        for (int t : timeouts) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), t);
                s.setSoTimeout(3_000);
                InputStream in = s.getInputStream();
                byte[] buf = new byte[4];
                int got = 0;
                while (got < 4) {
                    int n = in.read(buf, got, 4 - got);
                    if (n < 0) break;
                    got += n;
                }
                Log.d(TAG, "DIAG 127.0.0.1:" + port + " timeout=" + t
                        + "ms → CONNECTED, magic='"
                        + (got >= 4 ? "" + (char)buf[0]+(char)buf[1]+(char)buf[2]+(char)buf[3]
                                    : "only " + got + " bytes") + "'");
                return;   // success — no need to try larger timeouts
            } catch (Exception e) {
                Log.d(TAG, "DIAG 127.0.0.1:" + port + " timeout=" + t
                        + "ms → " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Connect to host:port and wait up to RTL0_TIMEOUT_MS for the 4-byte
     * "RTL0" magic header sent by rtl_tcp on every new connection.
     */
    private static boolean probeRtl0(String host, int port) {
        return probeRtl0(host, port, RTL0_TIMEOUT_MS);
    }

    private static boolean probeRtl0(String host, int port, int readTimeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(readTimeoutMs);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4];
            int got = 0;
            while (got < 4) {
                int n = in.read(buf, got, 4 - got);
                if (n < 0) return false;
                got += n;
            }
            boolean match = buf[0] == 'R' && buf[1] == 'T' && buf[2] == 'L' && buf[3] == '0';
            if (!match) {
                Log.d(TAG, host + ":" + port + " magic='"
                        + (char)buf[0] + (char)buf[1] + (char)buf[2] + (char)buf[3] + "'");
            }
            return match;
        } catch (Exception e) {
            return false;
        }
    }
}
