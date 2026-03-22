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
 * Decodes ADS-B (Mode-S) messages from raw 2 Msps IQ samples.
 *
 * The RTL-SDR streams unsigned 8-bit interleaved I/Q pairs at 2,000,000 sps.
 * At this rate each chip (0.5 µs) is exactly 1 sample pair.
 *
 * Preamble (8 µs = 16 chips at 2 Msps):
 *   HIGH, LOW, HIGH, LOW, LOW, LOW, LOW, HIGH, LOW, HIGH, LOW×6
 *   (chips 0,2,7,9 are HIGH)
 *
 * Data (PPM): each 1 µs bit = 2 chips.
 *   bit=1: first chip HIGH, second LOW
 *   bit=0: first chip LOW,  second HIGH
 *
 * Short frame (DF 0–10): 56 bits  → 16 + 112  = 128 samples
 * Long frame  (DF 11–24): 112 bits → 16 + 224 = 240 samples
 *
 * Position uses the ICAO CPR global algorithm (even+odd pair required).
 * Per-aircraft state is cached to combine callsign, altitude, velocity and
 * position from separate message types before calling the callback.
 */
public class RtlSdrAdsbDecoder {

    private static final String TAG = "RtlSdrAdsbDecoder";

    // ADS-B sample rate and geometry
    private static final int PREAMBLE_CHIPS = 16;
    private static final int BITS_LONG      = 112;

    // Maximum age of a CPR frame to be paired with its counterpart (10 s)
    private static final long CPR_MAX_AGE_MS = 10_000;
    // Evict aircraft state not updated in 5 minutes
    private static final long STATE_TTL_MS   = 5 * 60_000L;

    // CRC-24 lookup table (ICAO polynomial: 0xFFF409)
    private static final int[] CRC24_TABLE = buildCrc24Table();

    public interface Callback {
        void onAircraft(Aircraft a);
    }

    // ─── Per-aircraft accumulated state ────────────────────────────────────

    private static class AircraftState {
        String callsign = "";
        String squawk = "";
        String category = "";
        double altitudeFt;
        double groundSpeedKnots;
        double trackDeg;
        double verticalRateFpm;

        // CPR even/odd frames
        int  rawLatEven, rawLonEven;
        long timeEven;
        boolean hasEven;

        int  rawLatOdd, rawLonOdd;
        long timeOdd;
        boolean hasOdd;

        // Last decoded position
        double lat, lon;
        boolean hasPosition;
        long lastPositionTime;

        long lastSeen;
    }

    private final Callback callback;
    private final Map<String, AircraftState> states = new HashMap<>();
    private byte[] carryBuf = new byte[0];
    private long   nextEvict = 0;

    // Rate tracking: log update interval per aircraft
    private final Map<String, Long> lastFireTime = new HashMap<>();

    public RtlSdrAdsbDecoder(Callback cb) {
        this.callback = cb;
    }

    // ─── Entry point ───────────────────────────────────────────────────────

    public void process(byte[] raw, int len) {
        // Evict stale aircraft state periodically
        long now = System.currentTimeMillis();
        if (now > nextEvict) {
            evictStale(now);
            nextEvict = now + 60_000L;
        }

        // Compute magnitude array: mag[i] = |I[i]| + |Q[i]|
        int samples = len / 2;
        float[] mag = new float[samples];
        for (int i = 0; i < samples; i++) {
            float I = (raw[2 * i]     & 0xff) - 127.4f;
            float Q = (raw[2 * i + 1] & 0xff) - 127.4f;
            mag[i] = Math.abs(I) + Math.abs(Q);
        }

        // Prepend carry-over from previous chunk
        float[] buf;
        int off;
        if (carryBuf.length > 0) {
            int carryMag = carryBuf.length / 2;
            buf = new float[carryMag + samples];
            for (int i = 0; i < carryMag; i++) {
                float I = (carryBuf[2 * i]     & 0xff) - 127.4f;
                float Q = (carryBuf[2 * i + 1] & 0xff) - 127.4f;
                buf[i] = Math.abs(I) + Math.abs(Q);
            }
            System.arraycopy(mag, 0, buf, carryMag, samples);
            off = carryMag;
        } else {
            buf = mag;
            off = 0;
        }

        int totalSamples = buf.length;
        int maxStart = totalSamples - PREAMBLE_CHIPS - BITS_LONG * 2;

        for (int i = 0; i < maxStart; i++) {
            if (!detectPreamble(buf, i)) continue;

            float sig   = (buf[i] + buf[i+2] + buf[i+7] + buf[i+9]) / 4f;
            float noise = (buf[i+1] + buf[i+3] + buf[i+4]
                         + buf[i+5] + buf[i+6] + buf[i+8]) / 6f;
            if (sig < noise * 2.5f) continue;

            int dataStart = i + PREAMBLE_CHIPS;
            int[] header  = decodeBits(buf, dataStart, 8);
            int df        = bitsToInt(header, 0, 5);
            int msgLen    = (df >= 11) ? BITS_LONG : 56;

            if (dataStart + msgLen * 2 > totalSamples) {
                int carryBytes = Math.min(len, (PREAMBLE_CHIPS + BITS_LONG * 2) * 2);
                int srcStart   = Math.max(0, len - carryBytes);
                carryBuf = new byte[len - srcStart];
                System.arraycopy(raw, srcStart, carryBuf, 0, carryBuf.length);
                return;
            }

            int[] bits = decodeBits(buf, dataStart, msgLen);
            if (!checkCrc(bits, msgLen)) continue;

            if (df == 17 || df == 18) {
                parseAdsb(bits, now);
            } else if (df == 4 || df == 5 || df == 20 || df == 21) {
                parseSquawk(bits, df, now);
            }
        }

        carryBuf = new byte[0];
    }

    // ─── ADS-B message parsing ─────────────────────────────────────────────

    private void parseAdsb(int[] bits, long now) {
        int icao     = bitsToInt(bits, 8, 24);
        int typeCode = bitsToInt(bits, 32, 5);
        String icao24 = String.format("%06x", icao);

        AircraftState st = states.get(icao24);
        if (st == null) { st = new AircraftState(); states.put(icao24, st); }
        st.lastSeen = now;

        if (typeCode >= 1 && typeCode <= 4) {
            // Identification / callsign + category
            st.callsign = decodeCallsign(bits);
            st.category = decodeCategory(typeCode, bitsToInt(bits, 37, 3));

        } else if ((typeCode >= 9 && typeCode <= 18)
                || (typeCode >= 20 && typeCode <= 22)) {
            // Airborne position
            boolean odd  = (bitsToInt(bits, 53, 1) == 1);
            int rawLat   = bitsToInt(bits, 54, 17);
            int rawLon   = bitsToInt(bits, 71, 17);
            int altCode  = bitsToInt(bits, 40, 12);
            double alt   = decodeAlt(altCode);

            if (odd) {
                st.rawLatOdd = rawLat; st.rawLonOdd = rawLon;
                st.timeOdd = now; st.hasOdd = true;
            } else {
                st.rawLatEven = rawLat; st.rawLonEven = rawLon;
                st.timeEven = now; st.hasEven = true;
            }
            if (alt > 0) st.altitudeFt = alt;

            double[] pos = null;

            // Local decode: if we already have a position fix, use it as
            // reference to decode this single frame immediately (no pairing needed).
            if (st.hasPosition) {
                pos = cprLocalDecode(rawLat, rawLon, odd, st.lat, st.lon);
            }

            // Fall back to global decode using recent even+odd pair.
            if (pos == null && st.hasEven && st.hasOdd
                    && Math.abs(st.timeEven - st.timeOdd) <= CPR_MAX_AGE_MS) {
                boolean oddIsNewer = st.timeOdd >= st.timeEven;
                pos = cprGlobalDecode(
                        st.rawLatEven, st.rawLonEven,
                        st.rawLatOdd,  st.rawLonOdd,
                        oddIsNewer);
            }

            if (pos != null) {
                st.lat = pos[0];
                st.lon = pos[1];
                st.hasPosition = true;
                st.lastPositionTime = now;
                fireCallback(icao24, st, st.lat, st.lon);
            }

        } else if (typeCode == 19) {
            // Airborne velocity
            int subType = bitsToInt(bits, 37, 3);
            if (subType == 1 || subType == 2) {
                int ewDir = bitsToInt(bits, 45, 1);
                int ewSpd = bitsToInt(bits, 46, 10) - 1;
                int nsDir = bitsToInt(bits, 56, 1);
                int nsSpd = bitsToInt(bits, 57, 10) - 1;
                if (ewSpd >= 0 && nsSpd >= 0) {
                    if (ewDir == 1) ewSpd = -ewSpd;
                    if (nsDir == 1) nsSpd = -nsSpd;
                    st.groundSpeedKnots = Math.sqrt((double)ewSpd*ewSpd + (double)nsSpd*nsSpd);
                    st.trackDeg = Math.toDegrees(Math.atan2(ewSpd, nsSpd));
                    if (st.trackDeg < 0) st.trackDeg += 360;
                    // Vertical rate: bits 68-77 (sign bit + 9-bit magnitude)
                    int vrSign = bitsToInt(bits, 68, 1);
                    int vrVal  = bitsToInt(bits, 69, 9);
                    if (vrVal > 0) {
                        st.verticalRateFpm = (vrVal - 1) * 64;
                        if (vrSign == 1) st.verticalRateFpm = -st.verticalRateFpm;
                    }
                    // Only fire on velocity if we have a reasonably fresh position
                    // (within 10 s) and it was fired more than 500 ms ago.
                    if (st.hasPosition && (now - st.lastPositionTime) <= 10_000) {
                        Long prev = lastFireTime.get(icao24);
                        if (prev == null || (now - prev) >= 500) {
                            fireCallback(icao24, st, st.lat, st.lon);
                        }
                    }
                }
            }
        }
    }

    /**
     * DF4/5 (short) and DF20/21 (long) carry a 13-bit Mode A identity code.
     * The ICAO address is recovered from the CRC remainder (AP field).
     */
    private void parseSquawk(int[] bits, int df, long now) {
        // For DF4/5/20/21 the ICAO address is XORed into the CRC parity;
        // we recover it by computing CRC over the message-minus-parity and
        // XORing with the received parity (AP field).
        int msgLen = (df >= 11) ? BITS_LONG : 56;
        byte[] msg = new byte[msgLen / 8];
        for (int i = 0; i < msg.length; i++) msg[i] = (byte) bitsToInt(bits, i * 8, 8);
        int crcLen = msg.length - 3;
        int computed = crc24(msg, 0, crcLen);
        int ap = ((msg[crcLen] & 0xff) << 16)
               | ((msg[crcLen + 1] & 0xff) << 8)
               | (msg[crcLen + 2] & 0xff);
        int icao = computed ^ ap;
        String icao24 = String.format("%06x", icao);

        // Only accept if we already track this aircraft (confirms ICAO recovery)
        AircraftState st = states.get(icao24);
        if (st == null) return;

        // DF5/DF21 carry identity (squawk); DF4/DF20 carry altitude
        if (df == 5 || df == 21) {
            int id13 = bitsToInt(bits, 19, 13);
            st.squawk = decodeSquawk(id13);
            st.lastSeen = now;
        }
    }

    /**
     * Decode a 13-bit Mode A identity code into a 4-digit octal squawk string.
     * Bit mapping (ICAO Annex 10): C1 A1 C2 A2 C4 A4 _ B1 D1 B2 D2 B4 D4
     */
    private static String decodeSquawk(int id13) {
        int c1 = (id13 >> 12) & 1; int a1 = (id13 >> 11) & 1;
        int c2 = (id13 >> 10) & 1; int a2 = (id13 >>  9) & 1;
        int c4 = (id13 >>  8) & 1; int a4 = (id13 >>  7) & 1;
        // bit 6 is the M bit (not used in squawk)
        int b1 = (id13 >>  5) & 1; int d1 = (id13 >>  4) & 1;
        int b2 = (id13 >>  3) & 1; int d2 = (id13 >>  2) & 1;
        int b4 = (id13 >>  1) & 1; int d4 =  id13        & 1;

        int a = a4 * 4 + a2 * 2 + a1;
        int b = b4 * 4 + b2 * 2 + b1;
        int c = c4 * 4 + c2 * 2 + c1;
        int d = d4 * 4 + d2 * 2 + d1;
        return "" + a + b + c + d;
    }

    /** Decode ADS-B emitter category from TypeCode (1-4) and CA sub-field. */
    private static String decodeCategory(int typeCode, int ca) {
        // TypeCode 4 = Category Set A, 3 = B, 2 = C, 1 = D
        // ca = 0 means "no category info"
        if (ca == 0) return "";
        switch (typeCode) {
            case 4: // Set A
                switch (ca) {
                    case 1: return "Light";
                    case 2: return "Small";
                    case 3: return "Large";
                    case 4: return "High Vortex Large";
                    case 5: return "Heavy";
                    case 6: return "High Performance";
                    case 7: return "Rotorcraft";
                    default: return "";
                }
            case 3: // Set B
                switch (ca) {
                    case 1: return "Glider/Sailplane";
                    case 2: return "Lighter-than-Air";
                    case 3: return "Parachutist/Skydiver";
                    case 4: return "Ultralight/Hang-glider";
                    case 5: return "Reserved";
                    case 6: return "UAV";
                    case 7: return "Space Vehicle";
                    default: return "";
                }
            case 2: // Set C
                switch (ca) {
                    case 1: return "Emergency Vehicle";
                    case 2: return "Service Vehicle";
                    case 3: return "Ground Obstruction";
                    default: return "Surface";
                }
            case 1: // Set D
                return ""; // reserved
            default:
                return "";
        }
    }

    private void fireCallback(String icao24, AircraftState st,
                              double lat, double lon) {
        long now = System.currentTimeMillis();
        Long prev = lastFireTime.put(icao24, now);
        if (prev != null) {
            Log.d(TAG, icao24 + " pos update interval: " + (now - prev) + " ms"
                    + " lat=" + String.format("%.4f", lat)
                    + " lon=" + String.format("%.4f", lon));
        }

        Aircraft a = new Aircraft();
        a.icao24           = icao24;
        a.callsign         = st.callsign;
        a.lat              = lat;
        a.lon              = lon;
        a.altitudeFt       = st.altitudeFt;
        a.groundSpeedKnots = st.groundSpeedKnots;
        a.trackDeg         = st.trackDeg;
        a.squawk           = st.squawk;
        a.verticalRateFpm  = st.verticalRateFpm;
        a.category         = st.category;
        callback.onAircraft(a);
    }

    // ─── CPR global position decode (ICAO Annex 10 Vol IV) ─────────────────

    /**
     * Full CPR global decode from an even+odd frame pair.
     * Returns {lat, lon} in degrees, or null if zones are inconsistent.
     */
    private static double[] cprGlobalDecode(int rawLatE, int rawLonE,
                                            int rawLatO, int rawLonO,
                                            boolean oddIsNewer) {
        // Latitude
        double dLatE = 360.0 / 60.0;
        double dLatO = 360.0 / 59.0;

        int j = (int) Math.floor((59.0 * rawLatE - 60.0 * rawLatO) / 131072.0 + 0.5);

        double latE = dLatE * (cprMod(j, 60) + rawLatE / 131072.0);
        double latO = dLatO * (cprMod(j, 59) + rawLatO / 131072.0);
        if (latE >= 270) latE -= 360;
        if (latO >= 270) latO -= 360;

        // NL zone consistency check
        if (cprNL(latE) != cprNL(latO)) return null;

        double lat = oddIsNewer ? latO : latE;
        int    nl  = cprNL(lat);

        // Longitude
        int    ni  = Math.max(oddIsNewer ? nl - 1 : nl, 1);
        double dLon = 360.0 / ni;

        int m = (int) Math.floor(
                ((double) rawLonE * (nl - 1) - (double) rawLonO * nl) / 131072.0 + 0.5);

        double lon = dLon * (cprMod(m, ni) + (oddIsNewer ? rawLonO : rawLonE) / 131072.0);
        if (lon >= 180) lon -= 360;

        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    /**
     * CPR local decode: resolves a single position frame (even or odd) using
     * a known reference position. Much faster than global decode (no pairing
     * required) and accurate as long as the aircraft hasn't moved more than
     * ~180 km since the reference was established.
     * Returns {lat, lon} or null if the result is implausible.
     */
    private static double[] cprLocalDecode(int rawLat, int rawLon, boolean odd,
                                           double refLat, double refLon) {
        double nz  = odd ? 59.0 : 60.0;
        double dLat = 360.0 / nz;

        int j = (int) Math.floor(refLat / dLat)
                + (int) Math.floor(0.5 + cprMod(refLat, dLat) / dLat
                        - rawLat / 131072.0);
        double lat = dLat * (j + rawLat / 131072.0);

        int    nl   = cprNL(lat);
        int    ni   = Math.max(odd ? nl - 1 : nl, 1);
        double dLon = 360.0 / ni;

        int m = (int) Math.floor(refLon / dLon)
                + (int) Math.floor(0.5 + cprMod(refLon, dLon) / dLon
                        - rawLon / 131072.0);
        double lon = dLon * (m + rawLon / 131072.0);

        // Sanity: result must stay within half a zone of the reference
        if (Math.abs(lat - refLat) > dLat / 2.0
                || Math.abs(lon - refLon) > dLon / 2.0) return null;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    /** CPR modulo: always returns non-negative result. */
    private static double cprMod(double x, double y) {
        double r = x - y * Math.floor(x / y);
        return r < 0 ? r + y : r;
    }

    private static int cprNL(double lat) {
        if (lat < 0) lat = -lat;
        if (lat < 10.47047130) return 59;
        if (lat < 14.82817437) return 58;
        if (lat < 18.18626357) return 57;
        if (lat < 21.02939493) return 56;
        if (lat < 23.54504487) return 55;
        if (lat < 25.82924707) return 54;
        if (lat < 27.93898710) return 53;
        if (lat < 29.91135686) return 52;
        if (lat < 31.77209708) return 51;
        if (lat < 33.53993436) return 50;
        if (lat < 35.22899598) return 49;
        if (lat < 36.85025108) return 48;
        if (lat < 38.41241892) return 47;
        if (lat < 39.92256684) return 46;
        if (lat < 41.38651832) return 45;
        if (lat < 42.80914012) return 44;
        if (lat < 44.19454951) return 43;
        if (lat < 45.54626723) return 42;
        if (lat < 46.86733252) return 41;
        if (lat < 48.16039128) return 40;
        if (lat < 49.42776439) return 39;
        if (lat < 50.67150166) return 38;
        if (lat < 51.89342469) return 37;
        if (lat < 53.09516153) return 36;
        if (lat < 54.27817955) return 35;
        if (lat < 55.44378444) return 34;
        if (lat < 56.59318756) return 33;
        if (lat < 57.72747354) return 32;
        if (lat < 58.84763776) return 31;
        if (lat < 59.95459277) return 30;
        if (lat < 61.04917774) return 29;
        if (lat < 62.13216659) return 28;
        if (lat < 63.20427479) return 27;
        if (lat < 64.26616523) return 26;
        if (lat < 65.31845310) return 25;
        if (lat < 66.36171008) return 24;
        if (lat < 67.39646774) return 23;
        if (lat < 68.42322022) return 22;
        if (lat < 69.44242631) return 21;
        if (lat < 70.45451075) return 20;
        if (lat < 71.45986473) return 19;
        if (lat < 72.45884545) return 18;
        if (lat < 73.45177442) return 17;
        if (lat < 74.43893416) return 16;
        if (lat < 75.42056257) return 15;
        if (lat < 76.39684391) return 14;
        if (lat < 77.36789461) return 13;
        if (lat < 78.33374083) return 12;
        if (lat < 79.29428225) return 11;
        if (lat < 80.24923213) return 10;
        if (lat < 81.19801349) return  9;
        if (lat < 82.13956981) return  8;
        if (lat < 83.07199445) return  7;
        if (lat < 83.99173563) return  6;
        if (lat < 84.89166191) return  5;
        if (lat < 85.75541621) return  4;
        if (lat < 86.53536998) return  3;
        if (lat < 87.00000000) return  2;
        return 1;
    }

    // ─── Altitude decode (Q-bit / 25 ft encoding) ─────────────────────────

    private static double decodeAlt(int altCode) {
        int qBit = (altCode >> 4) & 1;
        if (qBit == 1) {
            int n = ((altCode & 0xFE0) >> 1) | (altCode & 0x0F);
            return 25 * n - 1000;
        }
        return 0; // Gillham (Gray) code fallback — omitted for brevity
    }

    // ─── Preamble / bit decode ─────────────────────────────────────────────

    private boolean detectPreamble(float[] mag, int start) {
        float hi = mag[start] + mag[start+2] + mag[start+7] + mag[start+9];
        float lo = mag[start+1] + mag[start+3] + mag[start+4]
                 + mag[start+5] + mag[start+6] + mag[start+8];
        return hi > lo;
    }

    private int[] decodeBits(float[] mag, int start, int numBits) {
        int[] bits = new int[numBits];
        for (int i = 0; i < numBits; i++) {
            int s = start + i * 2;
            bits[i] = (mag[s] > mag[s + 1]) ? 1 : 0;
        }
        return bits;
    }

    private int bitsToInt(int[] bits, int from, int len) {
        int val = 0;
        for (int i = 0; i < len; i++) val = (val << 1) | bits[from + i];
        return val;
    }

    // ─── CRC-24 ────────────────────────────────────────────────────────────

    private boolean checkCrc(int[] bits, int msgLen) {
        byte[] msg = new byte[msgLen / 8];
        for (int i = 0; i < msg.length; i++) msg[i] = (byte) bitsToInt(bits, i * 8, 8);
        int crcLen   = msg.length - 3;
        int computed = crc24(msg, 0, crcLen);
        int received = ((msg[crcLen]   & 0xff) << 16)
                     | ((msg[crcLen+1] & 0xff) <<  8)
                     |  (msg[crcLen+2] & 0xff);
        return computed == received;
    }

    private int crc24(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc = (crc << 8) ^ CRC24_TABLE[((crc >> 16) ^ (data[i] & 0xff)) & 0xff];
            crc &= 0xFFFFFF;
        }
        return crc;
    }

    private static int[] buildCrc24Table() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i << 16;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x800000) != 0) crc = (crc << 1) ^ 0xFFF409;
                else                        crc <<= 1;
            }
            table[i] = crc & 0xFFFFFF;
        }
        return table;
    }

    // ─── Callsign decode ───────────────────────────────────────────────────

    private String decodeCallsign(int[] bits) {
        final String CHARSET = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####"
                             + " ###############0123456789######";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int c = bitsToInt(bits, 40 + i * 6, 6);
            if (c < CHARSET.length()) sb.append(CHARSET.charAt(c));
        }
        return sb.toString().trim();
    }

    // ─── State eviction ────────────────────────────────────────────────────

    private void evictStale(long now) {
        Iterator<Map.Entry<String, AircraftState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeen > STATE_TTL_MS) it.remove();
        }
    }
}
