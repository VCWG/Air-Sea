/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Client for the rtl_tcp IQ-streaming protocol.
 *
 * Protocol summary:
 *   1. Connect to host:port (default 127.0.0.1:1234).
 *   2. Server sends a 12-byte magic header:
 *        "RTL0" (4 bytes) | tuner_type (uint32 BE) | gain_count (uint32 BE)
 *   3. Client sends 5-byte commands:
 *        [cmd (1 byte)] [param (uint32 BE)]
 *      Key commands: 0x01=SET_FREQ, 0x02=SET_SAMPLE_RATE, 0x03=SET_GAIN_MODE, 0x08=SET_AGC_MODE
 *   4. Server streams unsigned 8-bit interleaved IQ samples continuously.
 *
 * Typical server: "RTL-SDR Driver" app (Play Store) or rtl_tcp on a companion device.
 */
public class RtlTcpClient {

    private static final String TAG          = "RtlTcpClient";
    public  static final int    DEFAULT_PORT = 1234;

    private final String host;
    private final int    port;

    private static final int CMD_SET_FREQ        = 0x01;
    private static final int CMD_SET_SAMPLE_RATE = 0x02;
    private static final int CMD_SET_GAIN_MODE   = 0x03;  // 0=auto, 1=manual
    private static final int CMD_SET_GAIN        = 0x04;  // tenths of dB
    private static final int CMD_SET_AGC_MODE    = 0x08;  // 1=enabled

    /** Default gain: 40.2 dB (402 tenths) — best compromise for ADS-B with R820T/R820T2. */
    public  static final int DEFAULT_GAIN_TENTHS_DB = 402;
    private final        int gainTenthsDb;
    private final        int ppmOffset; // crystal correction: positive = crystal runs fast
    private final        boolean tunerAutoGain;
    private final        boolean rtlAgcEnabled;

    /** IQ sample callback; called on the streaming thread. */
    @FunctionalInterface
    public interface IqCallback {
        void onSamples(byte[] buf, int len);
    }

    public RtlTcpClient(String host, int port, int gainTenthsDb, int ppmOffset,
            boolean tunerAutoGain, boolean rtlAgcEnabled) {
        this.host          = host;
        this.port          = port;
        this.gainTenthsDb  = gainTenthsDb;
        this.ppmOffset     = ppmOffset;
        this.tunerAutoGain = tunerAutoGain;
        this.rtlAgcEnabled = rtlAgcEnabled;
    }

    public RtlTcpClient(String host, int port, int gainTenthsDb, int ppmOffset) {
        this(host, port, gainTenthsDb, ppmOffset, false, false);
    }

    public RtlTcpClient(String host, int port, int gainTenthsDb) {
        this(host, port, gainTenthsDb, 0, false, false);
    }

    public RtlTcpClient(String host, int port) {
        this(host, port, DEFAULT_GAIN_TENTHS_DB, 0, false, false);
    }

    public RtlTcpClient(int port) {
        this("127.0.0.1", port, DEFAULT_GAIN_TENTHS_DB, 0, false, false);
    }

    public RtlTcpClient() {
        this("127.0.0.1", DEFAULT_PORT, DEFAULT_GAIN_TENTHS_DB, 0, false, false);
    }

    private Socket          socket;
    private OutputStream    out;
    private InputStream     in;
    private volatile boolean running;

    /**
     * Connect to the rtl_tcp server and configure frequency and sample rate.
     * Reads the 12-byte handshake; throws if the server is unavailable or not rtl_tcp.
     */
    public void connect(long freqHz, int sampleRateHz) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(3000);

        out = socket.getOutputStream();
        in  = socket.getInputStream();

        // Read 12-byte magic header
        byte[] magic = readExact(12);
        String magicStr = new String(magic, 0, 4, "US-ASCII");
        if (!magicStr.equals("RTL0")) {
            throw new IOException("Not an rtl_tcp server (magic='" + magicStr + "')");
        }
        int tunerType  = toInt(magic, 4);
        int gainCount  = toInt(magic, 8);
        Log.d(TAG, "rtl_tcp handshake OK: tuner=" + tunerType + " gains=" + gainCount);

        // Apply PPM correction: if crystal runs fast by +N ppm, we request a lower centre
        // frequency so the dongle's actual tuning lands at the nominal frequency.
        //   corrected = nominal × (1 − ppm/1e6)
        long correctedFreq = freqHz - Math.round(freqHz * ppmOffset / 1e6);

        // Configure the device
        sendCmd(CMD_SET_SAMPLE_RATE, sampleRateHz);
        sendCmd(CMD_SET_FREQ,        (int) correctedFreq);
        sendCmd(CMD_SET_GAIN_MODE,   tunerAutoGain ? 0 : 1);
        if (!tunerAutoGain) {
            sendCmd(CMD_SET_GAIN, gainTenthsDb);
        }
        sendCmd(CMD_SET_AGC_MODE,    rtlAgcEnabled ? 1 : 0);

        Log.d(TAG, "rtl_tcp configured: freq=" + freqHz
                + (ppmOffset != 0 ? " (corrected→" + correctedFreq + " ppm=" + ppmOffset + ")" : "")
                + " rate=" + sampleRateHz
                + " gain=" + (tunerAutoGain ? "AUTO" : ((gainTenthsDb / 10.0) + " dB"))
                + " agc=" + (rtlAgcEnabled ? "on" : "off"));
    }

    /**
     * Block and stream IQ samples to the callback until {@link #disconnect()} is called
     * or the server closes the connection.
     */
    public void stream(IqCallback cb) throws IOException {
        running = true;
        // 30 s timeout: detects a truly stalled connection without false-firing
        // during brief RTL-SDR Driver pauses (dongle power management, etc.)
        socket.setSoTimeout(30_000);
        byte[] buf = new byte[65536];
        byte[] aligned = new byte[65537];
        int pendingByte = -1;
        while (running) {
            int n = in.read(buf, 0, buf.length);
            if (n < 0) throw new IOException("rtl_tcp server closed the stream");
            if (n <= 0) continue;

            byte[] out = buf;
            int outLen = n;

            // Preserve I/Q byte alignment across arbitrary TCP read boundaries.
            // rtl_tcp is a raw byte stream, so a read can end on an odd byte count.
            if (pendingByte >= 0) {
                aligned[0] = (byte) pendingByte;
                System.arraycopy(buf, 0, aligned, 1, n);
                out = aligned;
                outLen = n + 1;
                pendingByte = -1;
            }

            if ((outLen & 1) != 0) {
                pendingByte = out[outLen - 1] & 0xff;
                outLen--;
            }

            if (outLen > 0) cb.onSamples(out, outLen);
        }
    }

    /** Retune center frequency using the current rtl_tcp connection. */
    public void retuneFrequency(long freqHz, int ppm) throws IOException {
        long correctedFreq = freqHz - Math.round(freqHz * ppm / 1e6);
        sendCmd(CMD_SET_FREQ, (int) correctedFreq);
        Log.d(TAG, "rtl_tcp retune: freq=" + freqHz
                + " corrected→" + correctedFreq + " ppm=" + ppm);
    }

    /** Close the connection; causes any blocking {@link #stream} call to return. */
    public void disconnect() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private synchronized void sendCmd(int cmd, int param) throws IOException {
        byte[] b = {
            (byte) cmd,
            (byte) (param >> 24),
            (byte) (param >> 16),
            (byte) (param >> 8),
            (byte)  param
        };
        out.write(b);
        out.flush();
    }

    private byte[] readExact(int len) throws IOException {
        byte[] buf = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(buf, got, len - got);
            if (n < 0) throw new IOException("EOF reading rtl_tcp header");
            got += n;
        }
        return buf;
    }

    private static int toInt(byte[] b, int off) {
        return ((b[off]     & 0xff) << 24)
             | ((b[off + 1] & 0xff) << 16)
             | ((b[off + 2] & 0xff) << 8)
             |  (b[off + 3] & 0xff);
    }
}
