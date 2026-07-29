/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * AIS decoder for rtl_tcp IQ samples.
 *
 * <p>This follows the host-validated rtl_ais/GNUAIS-style path: edge-tuned
 * 1.6 Msps IQ, dual-channel decimation, FM discrimination, Gaussian filtering,
 * transition-assisted PLL clock recovery, NRZI, HDLC, FCS validation, and AIS
 * payload parsing. The rtl_tcp center is intentionally below the channel
 * midpoint so the receiver avoids a large DC spur between the two AIS channels.
 */
public class RtlSdrAisDecoder {

    private static final String TAG = "RtlSdrAisDecoder";

    public static final int SAMPLE_RATE = 1_600_000;
    public static final long CENTER_FREQ = 161_988_000L;

    private static final int AUDIO_RATE = 48_000;
    private static final int CHANNEL_RATE = 25_000;
    private static final int BAUD_RATE = 9_600;
    private static final int PLL_INC = 0x10000 / (AUDIO_RATE / BAUD_RATE); // 5 samples/symbol
    private static final int PLL_ADJUST_DIVISOR = 16;

    private static final long DEDUP_MS = 500;
    private static final long LOG_INTERVAL_MS = 10_000;
    private static final long CACHE_PRUNE_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long DEDUP_MAX_AGE_MS = 10 * 60 * 1000L;
    private static final long STATIC_MAX_AGE_MS = 2 * 60 * 60 * 1000L;

    private static final float[] GAUSSIAN_FIR = {
            0f, 0f, 1.4741e-43f, 3.2462e-38f,
            3.1480e-33f, 1.3443e-28f, 2.5280e-24f, 2.0934e-20f,
            7.6339e-17f, 1.2259e-13f, 8.6690e-11f, 2.6996e-08f,
            3.7020e-06f, 2.2355e-04f, 5.9448e-03f, 6.9616e-02f,
            3.5899e-01f, 8.1522e-01f, 8.1522e-01f, 3.5899e-01f,
            6.9616e-02f, 5.9448e-03f, 2.2355e-04f, 3.7020e-06f,
            2.6996e-08f, 8.6690e-11f, 1.2259e-13f, 7.6339e-17f,
            2.0934e-20f, 2.5280e-24f, 1.3443e-28f, 3.1480e-33f,
            3.2462e-38f, 1.4741e-43f, 0f, 0f
    };

    private static final int[] BIT_REVERSE = new int[256];
    static {
        for (int i = 0; i < BIT_REVERSE.length; i++) {
            int v = i;
            int r = 0;
            for (int b = 0; b < 8; b++) {
                r = (r << 1) | (v & 1);
                v >>>= 1;
            }
            BIT_REVERSE[i] = r;
        }
    }

    public interface Callback {
        void onPosition(int mmsi, String shipName, double lat, double lon,
                        double cog, double sog, int rot, int heading,
                        int navStatus, int shipType, double draught,
                        String destination, String eta, int imoNumber);

        default void onShipName(int mmsi, String shipName) {}
    }

    public interface PpmCallback {
        void onPpmEstimated(int ppm);
    }

    private static class StaticData {
        String name = "";
        int imoNumber = -1;
        int shipType = -1;
        double draught = -1;
        String destination = "";
        String eta = "";
        long lastSeenMs;
    }

    private final Callback callback;
    private final Map<Integer, StaticData> staticDataMap = new HashMap<>();
    private final Map<Integer, Long> dedupMap = new HashMap<>();

    private final ComplexDecimator dec1 = new ComplexDecimator(4);
    private final ChannelDecoder channelA = new ChannelDecoder('A', true);
    private final ChannelDecoder channelB = new ChannelDecoder('B', false);

    private int rotateState = 0;
    private long totalSamples = 0;
    private long lastLogTime = 0;
    private long lastCachePruneTime = 0;
    private int crcValid = 0;
    private int crcFail = 0;
    private int flags = 0;
    private int type123Count = 0;
    private int type5Count = 0;
    private int type18Count = 0;
    private int type19Count = 0;
    private int type24ACount = 0;
    private int type24BCount = 0;
    private int otherTypeCount = 0;
    private double ampSum = 0;
    private long clipCount = 0;

    public RtlSdrAisDecoder(Callback cb, PpmCallback ppmCb) {
        this.callback = cb;
    }

    public RtlSdrAisDecoder(Callback cb) {
        this(cb, null);
    }

    public void process(byte[] raw, int len) {
        int samples = len / 2;
        for (int i = 0; i < samples; i++) {
            float iVal = (raw[2 * i] & 0xff) - 127.0f;
            float qVal = (raw[2 * i + 1] & 0xff) - 127.0f;
            float mag = (float) Math.sqrt(iVal * iVal + qVal * qVal);
            ampSum += mag;
            if (Math.abs(iVal) > 120 || Math.abs(qVal) > 120) clipCount++;

            if (dec1.accept(iVal, qVal)) {
                float baseI = dec1.outI;
                float baseQ = dec1.outQ;

                // At 100 ksps, +/-90 degree rotation shifts each AIS channel to
                // approximately a 12 kHz IF, matching the validated rtl_ais path.
                float leftI;
                float leftQ;
                float rightI;
                float rightQ;
                switch (rotateState) {
                    case 1:
                        leftI = -baseQ; leftQ = baseI;
                        rightI = baseQ; rightQ = -baseI;
                        break;
                    case 2:
                        leftI = -baseI; leftQ = -baseQ;
                        rightI = -baseI; rightQ = -baseQ;
                        break;
                    case 3:
                        leftI = baseQ; leftQ = -baseI;
                        rightI = -baseQ; rightQ = baseI;
                        break;
                    default:
                        leftI = baseI; leftQ = baseQ;
                        rightI = baseI; rightQ = baseQ;
                        break;
                }
                rotateState = (rotateState + 1) & 3;

                channelA.accept(leftI, leftQ);
                channelB.accept(rightI, rightQ);
            }
        }
        totalSamples += samples;
        logStats();
    }

    private void logStats() {
        long now = System.currentTimeMillis();
        if (now - lastLogTime < LOG_INTERVAL_MS) return;
        lastLogTime = now;
        double avgAmp = totalSamples > 0 ? ampSum / totalSamples : 0;
        long clipPct = totalSamples > 0 ? clipCount * 100 / totalSamples : 0;
        Log.d(TAG, "Stats: samples=" + totalSamples
                + " avgAmp=" + String.format("%.1f", avgAmp)
                + " clip=" + clipPct + "%"
                + " flags=" + flags
                + " crcOK=" + crcValid
                + " crcFail=" + crcFail
                + " types{1-3=" + type123Count
                + ",5=" + type5Count
                + ",18=" + type18Count
                + ",19=" + type19Count
                + ",24A=" + type24ACount
                + ",24B=" + type24BCount
                + ",other=" + otherTypeCount + "}");
        pruneCaches(now);
    }

    private final class ChannelDecoder {
        private final char channel;
        private final ComplexDecimator dec = new ComplexDecimator(2);
        private final FmDemod demod = new FmDemod();
        private final Resampler resampler = new Resampler();
        private final Receiver receiver;

        ChannelDecoder(char channel, boolean left) {
            this.channel = channel;
            this.receiver = new Receiver(channel);
        }

        void accept(float iVal, float qVal) {
            if (!dec.accept(iVal, qVal)) return;
            Float sample = demod.accept(dec.outI, dec.outQ);
            if (sample == null) return;
            resampler.accept(sample, receiver);
        }
    }

    private static final class ComplexDecimator {
        private final HalfDecimator[] stages;
        float outI;
        float outQ;

        ComplexDecimator(int stageCount) {
            stages = new HalfDecimator[stageCount];
            for (int i = 0; i < stageCount; i++) stages[i] = new HalfDecimator();
        }

        boolean accept(float iVal, float qVal) {
            float ci = iVal;
            float cq = qVal;
            for (HalfDecimator stage : stages) {
                if (!stage.accept(ci, cq)) return false;
                ci = stage.outI;
                cq = stage.outQ;
            }
            outI = ci;
            outQ = cq;
            return true;
        }
    }

    private static final class HalfDecimator {
        private final float[] histI = new float[6];
        private final float[] histQ = new float[6];
        private int count = 0;
        float outI;
        float outQ;

        boolean accept(float iVal, float qVal) {
            for (int i = 0; i < 5; i++) {
                histI[i] = histI[i + 1];
                histQ[i] = histQ[i + 1];
            }
            histI[5] = iVal;
            histQ[5] = qVal;
            count++;
            if ((count & 1) != 0) return false;
            outI = fir(histI);
            outQ = fir(histQ);
            return true;
        }

        private static float fir(float[] h) {
            return (h[0] + 5f * (h[1] + h[4]) + 10f * (h[2] + h[3]) + h[5]) / 32f;
        }
    }

    private static final class FmDemod {
        private boolean hasPrev = false;
        private float prevI;
        private float prevQ;
        private float dc = 0;

        Float accept(float iVal, float qVal) {
            if (!hasPrev) {
                prevI = iVal;
                prevQ = qVal;
                hasPrev = true;
                return null;
            }
            float cross = iVal * prevQ - qVal * prevI;
            float dot = iVal * prevI + qVal * prevQ;
            float fm = (float) Math.atan2(cross, dot);
            prevI = iVal;
            prevQ = qVal;
            dc += 0.01f * (fm - dc);
            return fm - dc;
        }
    }

    private static final class Resampler {
        private static final float STEP = (float) CHANNEL_RATE / AUDIO_RATE;
        private boolean hasLast = false;
        private float last;
        private float nextOut = 0;

        void accept(float sample, Receiver receiver) {
            if (!hasLast) {
                last = sample;
                hasLast = true;
                return;
            }
            while (nextOut <= 1.0f) {
                float y = last + (sample - last) * nextOut;
                receiver.accept(y);
                nextOut += STEP;
            }
            nextOut -= 1.0f;
            last = sample;
        }
    }

    private final class Receiver {
        private final HdlcCollector hdlcNormal;
        private final HdlcCollector hdlcInverted;
        private final float[] firHist = new float[GAUSSIAN_FIR.length];
        private int firPos = 0;
        private int pll = 0;
        private int prev = 0;
        private int lastBit = 0;

        Receiver(char channel) {
            hdlcNormal = new HdlcCollector(channel);
            hdlcInverted = new HdlcCollector(channel);
        }

        void accept(float sample) {
            firHist[firPos] = sample;
            firPos = (firPos + 1) % firHist.length;
            float filtered = 0;
            int idx = firPos;
            for (int i = 0; i < GAUSSIAN_FIR.length; i++) {
                filtered += firHist[idx] * GAUSSIAN_FIR[i];
                idx = (idx + 1) % firHist.length;
            }

            int bit = filtered > 0 ? 1 : 0;
            if ((bit ^ prev) != 0) {
                if (pll < 0x8000) pll += PLL_INC / PLL_ADJUST_DIVISOR;
                else pll -= PLL_INC / PLL_ADJUST_DIVISOR;
            }
            prev = bit;
            pll += PLL_INC;
            if (pll > 0xffff) {
                int nrzi = ((bit ^ lastBit) == 0) ? 1 : 0;
                hdlcNormal.accept(nrzi);
                hdlcInverted.accept(nrzi ^ 1);
                lastBit = bit;
                pll &= 0xffff;
            }
        }
    }

    private final class HdlcCollector {
        private final char channel;
        private final byte[] rawBits = new byte[1024];
        private final byte[] frameBits = new byte[1024];
        private int shift = 0;
        private boolean inFrame = false;
        private int rawLen = 0;

        HdlcCollector(char channel) {
            this.channel = channel;
        }

        void accept(int bit) {
            bit &= 1;
            shift = ((shift >>> 1) | (bit << 7)) & 0xff;
            if (!inFrame) {
                if (shift == 0x7e) {
                    inFrame = true;
                    rawLen = 0;
                    flags++;
                }
                return;
            }

            if (rawLen >= rawBits.length) {
                rawLen = 0;
                inFrame = false;
                return;
            }
            rawBits[rawLen++] = (byte) bit;
            if (shift != 0x7e) return;

            flags++;
            int candidateLen = rawLen - 8;
            if (candidateLen > 0) {
                int frameLen = unstuff(rawBits, candidateLen, frameBits);
                if (frameLen > 0) tryDecodeFrame(frameBits, frameLen, channel);
            }
            rawLen = 0;
        }
    }

    private static int unstuff(byte[] in, int inLen, byte[] out) {
        int outLen = 0;
        int ones = 0;
        for (int i = 0; i < inLen; i++) {
            int bit = in[i] & 1;
            if (bit != 0) {
                ones++;
                if (ones > 5) return -1;
                out[outLen++] = 1;
            } else {
                if (ones == 5) {
                    ones = 0;
                    continue;
                }
                ones = 0;
                out[outLen++] = 0;
            }
        }
        return outLen;
    }

    private void tryDecodeFrame(byte[] bits, int bitLen, char channel) {
        if ((bitLen & 7) != 0) return;
        int byteCount = bitLen / 8;
        if (byteCount < 22 || byteCount > 64) return;

        byte[] frame = new byte[byteCount];
        for (int i = 0; i < bitLen; i++) {
            if ((bits[i] & 1) != 0) frame[i >> 3] |= (byte) (1 << (i & 7));
        }

        int dataLen = byteCount - 2;
        int computedCrc = crc16(frame, 0, dataLen) ^ 0xffff;
        int receivedCrc = (frame[dataLen] & 0xff) | ((frame[dataLen + 1] & 0xff) << 8);
        if (computedCrc != receivedCrc) {
            crcFail++;
            return;
        }

        byte[] payload = new byte[dataLen];
        for (int i = 0; i < dataLen; i++) payload[i] = (byte) BIT_REVERSE[frame[i] & 0xff];

        int msgType = readBits(payload, 0, 0, 6);
        if (msgType < 1 || msgType > 27) return;
        crcValid++;
        Log.d(TAG, "AIS CRC-valid ch=" + channel + " type=" + msgType
                + " bytes=" + byteCount);
        parseAisPayload(payload, 0, dataLen);
    }

    private void parseAisPayload(byte[] data, int off, int len) {
        int msgType = readBits(data, off, 0, 6);
        int mmsi, sogRaw, lonRaw, latRaw, cogRaw, heading, navStatus, rot;
        String shipName = "";
        int shipType = -1;
        double draught = -1;
        String destination = "";
        String eta = "";
        int imoNumber = -1;

        if (msgType >= 1 && msgType <= 3) {
            type123Count++;
            if (len * 8 < 168) return;
            mmsi = readBits(data, off, 8, 30);
            navStatus = readBits(data, off, 38, 4);
            rot = readBitsSigned(data, off, 42, 8);
            sogRaw = readBits(data, off, 50, 10);
            lonRaw = readBitsSigned(data, off, 61, 28);
            latRaw = readBitsSigned(data, off, 89, 27);
            cogRaw = readBits(data, off, 116, 12);
            heading = readBits(data, off, 128, 9);
        } else if (msgType == 18) {
            type18Count++;
            if (len * 8 < 168) return;
            mmsi = readBits(data, off, 8, 30);
            navStatus = 15;
            rot = 0;
            sogRaw = readBits(data, off, 46, 10);
            lonRaw = readBitsSigned(data, off, 57, 28);
            latRaw = readBitsSigned(data, off, 85, 27);
            cogRaw = readBits(data, off, 112, 12);
            heading = readBits(data, off, 124, 9);
        } else if (msgType == 19) {
            type19Count++;
            if (len * 8 < 312) return;
            mmsi = readBits(data, off, 8, 30);
            navStatus = 15;
            rot = 0;
            sogRaw = readBits(data, off, 46, 10);
            lonRaw = readBitsSigned(data, off, 57, 28);
            latRaw = readBitsSigned(data, off, 85, 27);
            cogRaw = readBits(data, off, 112, 12);
            heading = readBits(data, off, 124, 9);
            shipName = readAisString(data, off, 143, 120);
            shipType = readBits(data, off, 263, 8);
            updateStaticData(mmsi, shipName, shipType, -1, "", "", -1);
        } else if (msgType == 5) {
            type5Count++;
            parseType5StaticData(data, off, len);
            return;
        } else if (msgType == 24) {
            parseType24StaticData(data, off, len);
            return;
        } else {
            otherTypeCount++;
            return;
        }

        if (mmsi <= 0 || mmsi > 999_999_999) return;

        if (lonRaw == 0x6791AC0 || latRaw == 0x3412140) {
            Log.d(TAG, "AIS reject: position unavailable mmsi=" + mmsi);
            return;
        }

        long now = System.currentTimeMillis();
        StaticData sd = staticDataMap.get(mmsi);
        if (sd != null) {
            sd.lastSeenMs = now;
            if (shipName.isEmpty()) shipName = sd.name;
            if (shipType <= 0) shipType = sd.shipType;
            draught = sd.draught;
            destination = sd.destination;
            eta = sd.eta;
            imoNumber = sd.imoNumber;
        }

        double lon = lonRaw / 600000.0;
        double lat = latRaw / 600000.0;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            Log.d(TAG, "AIS reject: coords out of range lat=" + lat + " lon=" + lon);
            return;
        }

        Long lastSeen = dedupMap.get(mmsi);
        if (lastSeen != null && now - lastSeen < DEDUP_MS) return;
        dedupMap.put(mmsi, now);

        double sog = sogRaw / 10.0;
        double cog = cogRaw / 10.0;
        if (sog > 102.2) sog = 0;
        if (cog >= 360.0) cog = 0;

        callback.onPosition(mmsi, shipName, lat, lon, cog, sog, rot, heading,
                navStatus, shipType, draught, destination, eta, imoNumber);
    }

    private void parseType5StaticData(byte[] data, int off, int len) {
        if (len * 8 < 424) return;
        int mmsi = readBits(data, off, 8, 30);
        int imoNumber = readBits(data, off, 40, 30);
        String name = readAisString(data, off, 112, 120);
        int shipType = readBits(data, off, 232, 8);
        int month = readBits(data, off, 274, 4);
        int day = readBits(data, off, 278, 5);
        int hour = readBits(data, off, 283, 5);
        int minute = readBits(data, off, 288, 6);
        int draughtRaw = readBits(data, off, 294, 8);
        double draught = draughtRaw > 0 ? draughtRaw / 10.0 : -1;
        String destination = readAisString(data, off, 302, 120);
        String eta = formatEta(month, day, hour, minute);

        updateStaticData(mmsi, name, shipType, draught, destination, eta,
                imoNumber > 0 ? imoNumber : -1);
        Log.d(TAG, "AIS static type=5 mmsi=" + mmsi + " name=" + name
                + " shipType=" + shipType);
    }

    private void parseType24StaticData(byte[] data, int off, int len) {
        if (len * 8 < 40) return;
        int mmsi = readBits(data, off, 8, 30);
        int partNumber = readBits(data, off, 38, 2);
        if (partNumber == 0) {
            type24ACount++;
            if (len * 8 < 160) return;
            String name = readAisString(data, off, 40, 120);
            updateStaticData(mmsi, name, -1, -1, "", "", -1);
            Log.d(TAG, "AIS static type=24A mmsi=" + mmsi + " name=" + name);
        } else if (partNumber == 1) {
            type24BCount++;
            if (len * 8 < 48) return;
            int shipType = readBits(data, off, 40, 8);
            String callsign = len * 8 >= 132 ? readAisString(data, off, 90, 42) : "";
            updateStaticData(mmsi, "", shipType, -1, "", "", -1);
            Log.d(TAG, "AIS static type=24B mmsi=" + mmsi
                    + " callsign=" + callsign + " shipType=" + shipType);
        } else {
            otherTypeCount++;
        }
    }

    private void updateStaticData(int mmsi, String name, int shipType,
            double draught, String destination, String eta, int imoNumber) {
        if (mmsi <= 0 || mmsi > 999_999_999) return;
        StaticData sd = staticDataMap.get(mmsi);
        if (sd == null) {
            sd = new StaticData();
            staticDataMap.put(mmsi, sd);
        }
        sd.lastSeenMs = System.currentTimeMillis();
        if (name != null && !name.isEmpty() && !name.equals(sd.name)) {
            sd.name = name;
            callback.onShipName(mmsi, name);
        }
        if (shipType > 0) sd.shipType = shipType;
        if (draught > 0) sd.draught = draught;
        if (destination != null && !destination.isEmpty()) sd.destination = destination;
        if (eta != null && !eta.isEmpty()) sd.eta = eta;
        if (imoNumber > 0) sd.imoNumber = imoNumber;
    }

    private String readAisString(byte[] data, int byteOff, int bitOff, int bitLen) {
        StringBuilder sb = new StringBuilder(bitLen / 6);
        for (int i = 0; i + 5 < bitLen; i += 6) {
            int v = readBits(data, byteOff, bitOff + i, 6);
            int c = v < 32 ? v + 64 : v;
            sb.append(isAisTextPadding(c) ? ' ' : (char) c);
        }
        return collapseSpaces(sb.toString()).trim();
    }

    private static boolean isAisTextPadding(int c) {
        return c == '@' || c == '[' || c == '\\' || c == ']'
                || c == '^' || c == '_';
    }

    private static String collapseSpaces(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                if (!lastWasSpace) out.append(c);
                lastWasSpace = true;
            } else {
                out.append(c);
                lastWasSpace = false;
            }
        }
        return out.toString();
    }

    private void pruneCaches(long now) {
        if (now - lastCachePruneTime < CACHE_PRUNE_INTERVAL_MS) return;
        lastCachePruneTime = now;

        Iterator<Map.Entry<Integer, Long>> dedupIt = dedupMap.entrySet().iterator();
        while (dedupIt.hasNext()) {
            Map.Entry<Integer, Long> entry = dedupIt.next();
            if (now - entry.getValue() > DEDUP_MAX_AGE_MS) dedupIt.remove();
        }

        Iterator<Map.Entry<Integer, StaticData>> staticIt = staticDataMap.entrySet().iterator();
        while (staticIt.hasNext()) {
            StaticData sd = staticIt.next().getValue();
            if (now - sd.lastSeenMs > STATIC_MAX_AGE_MS) staticIt.remove();
        }
    }

    private static String formatEta(int month, int day, int hour, int minute) {
        if (month > 0 && month <= 12 && day > 0 && day <= 31
                && hour < 24 && minute < 60) {
            return String.format("%02d/%02d %02d:%02d", month, day, hour, minute);
        }
        return "";
    }

    private int readBits(byte[] data, int byteOff, int bitOff, int numBits) {
        int result = 0;
        for (int i = 0; i < numBits; i++) {
            int absOff = bitOff + i;
            int byteIdx = byteOff + absOff / 8;
            int bitIdx = absOff % 8;
            if (byteIdx >= data.length) break;
            result = (result << 1) | ((data[byteIdx] >> bitIdx) & 1);
        }
        return result;
    }

    private int readBitsSigned(byte[] data, int byteOff, int bitOff, int numBits) {
        int val = readBits(data, byteOff, bitOff, numBits);
        if ((val & (1 << (numBits - 1))) != 0) val |= (-1 << numBits);
        return val;
    }

    private static int crc16(byte[] data, int off, int len) {
        int crc = 0xffff;
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xff;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) crc = (crc >>> 1) ^ 0x8408;
                else crc >>>= 1;
            }
        }
        return crc & 0xffff;
    }
}
