/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

/**
 * Decodes AIS messages from raw 288 ksps IQ samples received at 162.0 MHz
 * (centre between AIS channels 87B at 161.975 MHz and 88B at 162.025 MHz).
 *
 * Processing chain:
 *   1. FM discriminator  → baseband bit stream at 288 ksps
 *   2. Symbol sync       → sample at 9600 baud (30 samples/symbol)
 *   3. NRZI decode       → NRZ bits
 *   4. HDLC framing      → locate 0x7E flags, remove bit stuffing
 *   5. AIS message parse → extract MMSI, lat, lon, COG, SOG, etc.
 *
 * Two HDLC decoders run in parallel — one for each bit polarity — so the
 * decoder works regardless of whether the RTL-SDR driver delivers I/Q in
 * normal or inverted orientation.
 *
 * CRC: CRC-16/CCITT, LSB-first (polynomial 0x8408), result XOR 0xFFFF.
 */
public class RtlSdrAisDecoder {

    private static final String TAG = "RtlSdrAisDecoder";

    // 960 ksps is the lowest reliably stable RTL-SDR rate (hardware minimum is ~225 ksps
    // but rates below ~900 ksps are often rounded up internally by the driver, which would
    // make SAMPLES_PER_SYMBOL wrong and corrupt all symbol decisions).
    // 960000 = 9600 * 100, giving exactly 100 samples/symbol.
    public static final int  SAMPLE_RATE        = 960_000;      // sps
    public static final long CENTER_FREQ        = 162_000_000L; // Hz
    private static final int BAUD_RATE          = 9_600;
    private static final int SAMPLES_PER_SYMBOL = SAMPLE_RATE / BAUD_RATE; // = 100

    // Diagnostic stats — logged every LOG_INTERVAL_MS
    private static final long LOG_INTERVAL_MS = 10_000;
    private long lastLogTime  = 0;
    private long totalSamples = 0;
    private long signalSamples = 0;   // samples with signal above noise floor
    private int  flagsNormal  = 0;    // 0x7E flags seen on normal polarity
    private int  flagsInverted = 0;   // 0x7E flags seen on inverted polarity
    private int  crcValid     = 0;    // CRC-passing frames
    private int  crcFail      = 0;    // frames that started but failed CRC
    private int  crcDiagCount = 0;    // number of CRC mismatches logged so far
    private double fmAbsSum   = 0;    // running sum of |fmDC| for SNR diagnosis

    /** Called for each successfully decoded, CRC-valid AIS message. */
    public interface Callback {
        void onPosition(int mmsi, String shipName, double lat, double lon,
                        double cog, double sog, int heading, int navStatus);
    }

    private final Callback callback;

    // FM demodulation state
    private float prevI  = 0, prevQ  = 0;
    // Running mean of FM output — tracks RTL-SDR crystal frequency offset.
    // Alpha = 1e-4 → time constant ~10 000 samples / 9 600 baud ≈ 100 symbols,
    // long enough to pass the 2400 Hz AIS deviation but removes any DC bias.
    private float fmMean = 0;
    private static final float FM_ALPHA = 1e-4f;

    // Symbol sync state
    private final float[] symbolBuf = new float[SAMPLES_PER_SYMBOL];
    private int symbolPhase = 0;

    // NRZI state
    private int prevBit = 0;

    // Two HDLC decoders: normal and bit-inverted polarity
    private final HdlcDecoder hdlcNormal   = new HdlcDecoder();
    private final HdlcDecoder hdlcInverted = new HdlcDecoder();

    public RtlSdrAisDecoder(Callback cb) {
        this.callback = cb;
    }

    /** Process a chunk of raw unsigned 8-bit IQ samples (interleaved I, Q). */
    public void process(byte[] raw, int len) {
        int samples = len / 2;
        long now = System.currentTimeMillis();

        for (int i = 0; i < samples; i++) {
            float I = (raw[2 * i]     & 0xff) - 127.4f;
            float Q = (raw[2 * i + 1] & 0xff) - 127.4f;

            // 1. FM discriminator: instantaneous frequency ≈ cross-product / magnitude²
            float disc = I * prevQ - Q * prevI;
            float mag  = I * I + Q * Q;
            float fm   = (mag > 1.0f) ? disc / mag : 0f;
            if (mag > 1.0f) signalSamples++;

            prevI = I;
            prevQ = Q;

            // Remove DC bias caused by RTL-SDR crystal frequency offset.
            // Without this, a 50-100 ppm crystal error at 162 MHz (~8-16 kHz)
            // shifts the FM output by a constant that dwarfs the ±2400 Hz AIS deviation,
            // making every symbol decision the same regardless of data.
            fmMean += FM_ALPHA * (fm - fmMean);
            float fmDC = fm - fmMean;
            if (mag > 1.0f) fmAbsSum += Math.abs(fmDC);

            // 2. Symbol accumulation (fixed-phase)
            symbolBuf[symbolPhase] = fmDC;
            symbolPhase++;
            if (symbolPhase < SAMPLES_PER_SYMBOL) continue;
            symbolPhase = 0;

            // Sample decision: average over the full symbol window.
            // Using all 100 samples maximises SNR for a weak signal.
            // GMSK ISI is mild at BT=0.4 so full-window averaging is fine.
            float sum = 0;
            for (int k = 0; k < SAMPLES_PER_SYMBOL; k++) sum += symbolBuf[k];
            int rxBit = (sum >= 0) ? 1 : 0;

            // 3. NRZI decode: 1=no transition, 0=transition
            int nrziBit = (rxBit == prevBit) ? 1 : 0;
            prevBit = rxBit;

            // 4a. Feed normal polarity into first HDLC decoder
            int flags = hdlcNormal.receiveBit(nrziBit);
            if (flags != 0) {
                flagsNormal++;
                if ((flags & HdlcDecoder.FRAME_READY) != 0) tryDecodeFrame(hdlcNormal);
            }

            // 4b. Feed inverted polarity into second HDLC decoder
            flags = hdlcInverted.receiveBit(nrziBit ^ 1);
            if (flags != 0) {
                flagsInverted++;
                if ((flags & HdlcDecoder.FRAME_READY) != 0) tryDecodeFrame(hdlcInverted);
            }
        }

        totalSamples += samples;

        // Periodic diagnostics
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            long pct = totalSamples > 0 ? signalSamples * 100 / totalSamples : 0;
            double avgFmDev = signalSamples > 0 ? fmAbsSum / signalSamples : 0;
            Log.d(TAG, "Stats: samples=" + totalSamples
                    + " signal=" + pct + "%"
                    + " avgFmDev=" + String.format("%.4f", avgFmDev)
                    + " flags(normal/inv)=" + flagsNormal + "/" + flagsInverted
                    + " crcOK=" + crcValid + " crcFail=" + crcFail);
            lastLogTime = now;
        }
    }

    // ─── Frame decode ─────────────────────────────────────────────────────

    private void tryDecodeFrame(HdlcDecoder hdlc) {
        int byteCount = hdlc.lastFrameBitCount / 8; // frameBitCount is already reset to 0

        // Minimum: address(1) + control(1) + payload(≥14) + FCS(2) = 18 bytes
        if (byteCount < 18) return;

        byte[] fb = hdlc.frameBytes;

        // CRC-16/CCITT LSB-first over all bytes except the 2 FCS bytes
        int dataLen     = byteCount - 2;
        int computedCrc = crc16(fb, 0, dataLen) ^ 0xFFFF;
        int receivedCrc = (fb[dataLen] & 0xff) | ((fb[dataLen + 1] & 0xff) << 8);

        if (computedCrc != receivedCrc) {
            crcFail++;
            if (crcDiagCount < 10) {
                crcDiagCount++;
                StringBuilder sb = new StringBuilder();
                sb.append("CRC mismatch #").append(crcDiagCount)
                  .append(" bytes=").append(byteCount)
                  .append(" computed=").append(String.format("%04X", computedCrc))
                  .append(" received=").append(String.format("%04X", receivedCrc))
                  .append(" data[0..3]=");
                for (int di = 0; di < Math.min(4, byteCount); di++)
                    sb.append(String.format("%02X ", fb[di] & 0xff));
                Log.d(TAG, sb.toString());
            }
            return;
        }
        crcValid++;

        // Skip HDLC address (1 byte) + control (1 byte)
        int payloadStart = 2;
        int payloadLen   = dataLen - payloadStart;
        if (payloadLen < 14) return;

        Log.d(TAG, "CRC-valid frame: " + byteCount + " bytes");
        parseAisPayload(fb, payloadStart, payloadLen);
    }

    // ─── AIS payload ───────────────────────────────────────────────────────

    private void parseAisPayload(byte[] data, int off, int len) {
        int msgType = readBits(data, off, 0, 6);
        if (msgType < 1 || msgType > 3) return; // only class A position reports

        int mmsi = readBits(data, off, 8, 30);
        if (mmsi <= 0) return;

        int navStatus = readBits(data, off, 38, 4);
        int sogRaw    = readBits(data, off, 50, 10);
        int lonRaw    = readBitsSigned(data, off, 61, 28);
        int latRaw    = readBitsSigned(data, off, 89, 27);
        int cogRaw    = readBits(data, off, 116, 12);
        int heading   = readBits(data, off, 128, 9);

        // "not available" sentinel values
        if (lonRaw == 0x6791AC0) return; // 181.0°
        if (latRaw == 0x3412140) return; // 91.0°

        double lon = lonRaw / 600000.0;
        double lat = latRaw / 600000.0;
        double sog = sogRaw / 10.0;
        double cog = cogRaw / 10.0;

        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
        if (sog > 102.2) sog = 0;
        if (cog >= 360.0) cog = 0;

        Log.d(TAG, "AIS type=" + msgType + " mmsi=" + mmsi
                + " lat=" + lat + " lon=" + lon + " sog=" + sog);
        callback.onPosition(mmsi, "", lat, lon, cog, sog, heading, navStatus);
    }

    // ─── Bit extraction (MSB-first — AIS payload convention) ───────────────

    private int readBits(byte[] data, int byteOff, int bitOff, int numBits) {
        int result = 0;
        for (int i = 0; i < numBits; i++) {
            int absOff  = bitOff + i;
            int byteIdx = byteOff + absOff / 8;
            int bitIdx  = absOff % 8;
            if (byteIdx >= data.length) break;
            int bit = (data[byteIdx] >> bitIdx) & 1;
            result = (result << 1) | bit;
        }
        return result;
    }

    private int readBitsSigned(byte[] data, int byteOff, int bitOff, int numBits) {
        int val = readBits(data, byteOff, bitOff, numBits);
        if ((val & (1 << (numBits - 1))) != 0) val |= (-1 << numBits);
        return val;
    }

    // ─── CRC-16/CCITT, LSB-first (polynomial 0x8408) ──────────────────────

    private static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xff);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) crc = (crc >>> 1) ^ 0x8408;
                else                crc >>>= 1;
            }
        }
        return crc & 0xFFFF;
    }

    // ─── HDLC decoder (one instance per polarity) ─────────────────────────

    /** Stateful HDLC bit-stream decoder. Feed one bit at a time via receiveBit(). */
    private static class HdlcDecoder {
        static final int FLAG_SEEN  = 1;
        static final int FRAME_READY = 2;

        private int     hdlcShift        = 0;
        boolean         inFrame          = false;
        final byte[]    frameBytes       = new byte[512];
        int             frameBitCount    = 0;
        int             lastFrameBitCount = 0; // saved before reset so tryDecodeFrame can read it
        private int     onesCount        = 0;

        /**
         * Receive one decoded bit. Returns a bitmask:
         *   FLAG_SEEN   if a 0x7E flag was detected
         *   FRAME_READY if a complete frame (between two flags) is ready in frameBytes
         */
        int receiveBit(int bit) {
            hdlcShift = ((hdlcShift >>> 1) | (bit << 7)) & 0xFF;
            int result = 0;

            if (inFrame) {
                if (onesCount == 5) {
                    if (bit == 0) {
                        // Stuffed zero — discard and continue
                        onesCount = 0;
                        return 0;
                    } else {
                        // 6+ consecutive ones = end-of-frame flag or abort.
                        // The trailing 0 of the 0x7E flag has NOT arrived yet,
                        // so we must NOT check hdlcShift here — just fire FRAME_READY.
                        result |= FLAG_SEEN;
                        if (frameBitCount >= 144) {
                            lastFrameBitCount = frameBitCount; // save BEFORE reset
                            result |= FRAME_READY;
                        }
                        inFrame = false;
                        frameBitCount = 0;
                        onesCount = 0;
                        return result;
                    }
                }
                onesCount = (bit == 1) ? onesCount + 1 : 0;

                if (frameBitCount < frameBytes.length * 8) {
                    int byteIdx = frameBitCount / 8;
                    int bitIdx  = frameBitCount % 8;
                    if (bit == 1) frameBytes[byteIdx] |=  (1 << bitIdx);
                    else          frameBytes[byteIdx] &= ~(1 << bitIdx);
                    frameBitCount++;
                } else {
                    inFrame = false; frameBitCount = 0; onesCount = 0;
                }
                return result;
            }

            // Not in frame: watch for start flag
            if (hdlcShift == 0x7E) {
                inFrame = true;
                frameBitCount = 0;
                onesCount = 0;
                java.util.Arrays.fill(frameBytes, (byte) 0);
                result |= FLAG_SEEN;
            }
            return result;
        }
    }
}
