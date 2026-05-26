/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.coremap.log.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Decodes AIS messages from raw 960 ksps IQ samples received at 162.0 MHz.
 *
 * Processing chain (dual-channel channelizer):
 *   1. NCO mixing    → rotate IQ by ±25 kHz to place each AIS channel at DC
 *                      Ch88B (162.025 MHz) mixed by −25 kHz
 *                      Ch87B (161.975 MHz) mixed by +25 kHz
 *   2. Channel filter → 1-pole IIR LPF for each AIS channel.
 *   3. FM discriminator → instantaneous phase Δφ per channel
 *   4. DC removal    → IIR (cutoff ~75 Hz) removes crystal frequency offset
 *   5. Post-FM LPF   → 2-pole IIR at ~9600 Hz, final baseband cleanup
 *   6. Multi-phase symbol sync → 5 candidate phases per channel (9600 baud)
 *   7. NRZI decode   → NRZ bits (per phase per channel)
 *   8. HDLC framing  → locate 0x7E flags, remove bit stuffing
 *   9. AIS parse     → MMSI, lat, lon, COG, SOG, etc.
 *
 * Auto-PPM acquisition:
 *   PPM is estimated from the FM discriminator mean at CRC-valid frame times.
 *   The estimate is forwarded to the caller so {@link RtlTcpClient} can apply
 *   hardware-level frequency correction on the next rtl_tcp connection.
 *
 * Running N_PHASES=5 independent symbol-timing candidates (spaced 20 samples
 * apart) means one always lands within ±10 samples of the true symbol centre.
 * Each phase runs two HDLC decoders (normal + inverted polarity) → 10 decoders
 * per channel, 20 total.
 *
 * MMSI-based deduplication (500 ms) suppresses duplicate callbacks.
 *
 * CRC: CRC-16/CCITT, LSB-first (polynomial 0x8408), result XOR 0xFFFF.
 * AIS HDLC frames contain no address/control fields (ITU-R M.1371 §3.3.2).
 */
public class RtlSdrAisDecoder {

    private static final String TAG = "RtlSdrAisDecoder";

    // 960 ksps: SAMPLES_PER_SYMBOL = 960 000 / 9 600 = 100 (exact integer).
    // Center 162.000 MHz is the midpoint between:
    //   Ch87B  161.975 MHz  (−25 kHz offset)
    //   Ch88B  162.025 MHz  (+25 kHz offset)
    public static final int  SAMPLE_RATE        = 960_000;
    public static final long CENTER_FREQ        = 162_000_000L;
    private static final int BAUD_RATE          = 9_600;
    private static final int SAMPLES_PER_SYMBOL = SAMPLE_RATE / BAUD_RATE; // = 100

    // 5 phase candidates, 20 samples apart → covers the full symbol period.
    private static final int N_PHASES   = 5;
    private static final int PHASE_STEP = SAMPLES_PER_SYMBOL / N_PHASES; // = 20

    // AIS channel offset from centre (Hz)
    private static final int CHANNEL_OFFSET_HZ = 25_000;

    // Suppress duplicate callbacks from multiple phases/channels decoding the same frame.
    private static final long DEDUP_MS = 500;
    private final Map<Integer, Long> dedupMap = new HashMap<>();

    /** Called for each successfully decoded, CRC-valid, deduplicated AIS position. */
    public interface Callback {
        void onPosition(int mmsi, String shipName, double lat, double lon,
                        double cog, double sog, int rot, int heading,
                        int navStatus, int shipType, double draught,
                        String destination, String eta, int imoNumber);

        default void onShipName(int mmsi, String shipName) {}
    }

    /**
     * Called once after the decoder has acquired an AIS carrier and estimated the
     * dongle's crystal PPM offset.  The caller should persist this value so
     * {@link RtlTcpClient} can apply hardware-level correction on the next connection.
     */
    public interface PpmCallback {
        void onPpmEstimated(int ppm);
    }

    private final Callback    callback;
    private final PpmCallback ppmCallback; // nullable
    private final Map<Integer, StaticData> staticDataMap = new HashMap<>();

    private static class StaticData {
        String name = "";
        int imoNumber = -1;
        int shipType = -1;
        double draught = -1;
        String destination = "";
        String eta = "";
    }

    // ─── NCO: mix each AIS channel to baseband ────────────────────────────────
    // Phase increment Δθ = 2π × ncoFreqHz / SAMPLE_RATE
    // Fixed at 2π × 25000 / 960000 → cos = 0.98667, sin = 0.16279.
    // NOT adjusted during auto-PPM — see applyNcoCorrection() for rationale.
    private float ncoRe    = 1.0f, ncoIm = 0.0f;
    private float ncoCosInc = (float) Math.cos(2.0 * Math.PI * CHANNEL_OFFSET_HZ / SAMPLE_RATE);
    private float ncoSinInc = (float) Math.sin(2.0 * Math.PI * CHANNEL_OFFSET_HZ / SAMPLE_RATE);

    // ─── Per-channel IQ filter ────────────────────────────────────────────────
    // Two filter constants used by the auto-PPM state machine.
    // α = 2π·f_c / (2π·f_c + f_s)
    private static final float CH_ALPHA_WIDE   = 0.0756f; // 2π·12500/(2π·12500+960000)
    private static final float CH_ALPHA_NARROW = 0.08939f; // 2π·15000/(2π·15000+960000)
    private float chAlpha = CH_ALPHA_WIDE;     // starts wide; narrows after PPM lock

    // Ch88B state (I and Q, 1 pole)
    private float c88I1 = 0, c88Q1 = 0;
    // Ch87B state
    private float c87I1 = 0, c87Q1 = 0;

    // ─── Per-channel FM state ─────────────────────────────────────────────────
    // DC-removal IIR: cutoff ≈ 15 Hz removes crystal frequency offset.
    private static final float FM_ALPHA = 2e-3f;
    // Post-FM LPF: 2-pole IIR at ~9600 Hz for final baseband cleanup.
    // α = 2π×9600 / (2π×9600 + 960000) = 0.0592 → use 0.06 for implementation
    private static final float LPF_ALPHA = 0.06f;

    // Ch88B FM + DC + LPF state
    private float pFI88 = 0, pFQ88 = 0, fmMean88 = 0, lp1_88 = 0, lp2_88 = 0;
    // Ch87B FM + DC + LPF state
    private float pFI87 = 0, pFQ87 = 0, fmMean87 = 0, lp1_87 = 0, lp2_87 = 0;

    // ─── Per-channel symbol timing ────────────────────────────────────────────
    private final float[] s88Prev = new float[SAMPLES_PER_SYMBOL];
    private final float[] s88Curr = new float[SAMPLES_PER_SYMBOL];
    private final float[] s87Prev = new float[SAMPLES_PER_SYMBOL];
    private final float[] s87Curr = new float[SAMPLES_PER_SYMBOL];
    private int symIdx = 0;

    // ─── Per-channel NRZI + HDLC decoders ────────────────────────────────────
    // 5 phases × 2 polarities × 2 channels = 20 HDLC decoders total.
    private final int[] nrz88 = new int[N_PHASES];
    private final int[] nrz87 = new int[N_PHASES];
    private final HdlcDecoder[] hdN88 = new HdlcDecoder[N_PHASES]; // Ch88B normal
    private final HdlcDecoder[] hdI88 = new HdlcDecoder[N_PHASES]; // Ch88B inverted
    private final HdlcDecoder[] hdN87 = new HdlcDecoder[N_PHASES]; // Ch87B normal
    private final HdlcDecoder[] hdI87 = new HdlcDecoder[N_PHASES]; // Ch87B inverted

    // ─── Auto-PPM state machine ───────────────────────────────────────────────
    // AIS is a burst protocol: 26 ms frames every 2–180 seconds. Epoch-averaged
    // fmDev is always dominated by the noise floor between transmissions, so it
    // cannot be used to detect a carrier. Instead, PPM is estimated from the
    // fmMean at the moment a CRC-valid frame is decoded — at that point fmMean
    // has had at least one full frame duration (~26 ms = 2.5 time constants) to
    // converge onto the carrier offset.
    private static final int PPM_LOCK_INTERVALS = 5; // CRC-valid position frames needed to lock

    private boolean ppmLocked         = false;
    private int     ppmLockCount      = 0;   // CRC-valid frames accumulated
    private double  ppmCarrierSum     = 0;   // sum of fmMean readings at frame times
    private int     ppmSampleCount    = 0;

    // Per-epoch (per-10s-window) FM deviation accumulators — reset each interval.
    private double epochFmAbs88 = 0, epochFmAbs87 = 0;
    private long   epochSig88   = 0, epochSig87   = 0;

    // ─── Diagnostics ─────────────────────────────────────────────────────────
    private static final long LOG_INTERVAL_MS = 10_000;
    private long lastLogTime  = 0;
    private long totalSamples = 0;
    private int  flagsTotal   = 0;
    private int  crcValid     = 0;
    private int  crcFail      = 0;
    private int  crcDiagCount = 0;
    private double ampSum     = 0;
    private long   clipCount  = 0;
    // Cumulative FM deviation (for long-run stats only)
    private double fmAbs88 = 0;  private long sig88 = 0;
    private double fmAbs87 = 0;  private long sig87 = 0;
    private float smoothedAmp = 0;
    private int   burstCount  = 0;

    public RtlSdrAisDecoder(Callback cb, PpmCallback ppmCb) {
        this.callback    = cb;
        this.ppmCallback = ppmCb;
        for (int p = 0; p < N_PHASES; p++) {
            hdN88[p] = new HdlcDecoder(); hdI88[p] = new HdlcDecoder();
            hdN87[p] = new HdlcDecoder(); hdI87[p] = new HdlcDecoder();
        }
    }

    public RtlSdrAisDecoder(Callback cb) {
        this(cb, null);
    }

    /** Process a chunk of raw unsigned 8-bit IQ samples (interleaved I, Q). */
    public void process(byte[] raw, int len) {
        int samples = len / 2;
        long now = System.currentTimeMillis();

        for (int i = 0; i < samples; i++) {
            float I = (raw[2 * i]     & 0xff) - 127.4f;
            float Q = (raw[2 * i + 1] & 0xff) - 127.4f;

            // Raw amplitude diagnostics (use unfiltered I/Q)
            float rawMag = (float) Math.sqrt(I * I + Q * Q);
            ampSum += rawMag;
            if (Math.abs(I) > 120 || Math.abs(Q) > 120) clipCount++;
            // Amplitude burst detection: log when a sample exceeds 3x the rolling noise floor
            if (smoothedAmp == 0) smoothedAmp = rawMag;
            else smoothedAmp += 0.001f * (rawMag - smoothedAmp);
            if (rawMag > smoothedAmp * 3.0f && smoothedAmp > 5) burstCount++;

            // ── 1. Advance NCO phasor ─────────────────────────────────────
            float nRe = ncoRe * ncoCosInc - ncoIm * ncoSinInc;
            float nIm = ncoRe * ncoSinInc + ncoIm * ncoCosInc;
            ncoRe = nRe; ncoIm = nIm;

            // ── 2. Mix each AIS channel to DC ─────────────────────────────
            // Ch88B at +25 kHz: multiply by conj(phasor) = e^{-jθ}
            float I88 = I * ncoRe + Q * ncoIm;
            float Q88 = Q * ncoRe - I * ncoIm;
            // Ch87B at −25 kHz: multiply by phasor = e^{+jθ}
            float I87 = I * ncoRe - Q * ncoIm;
            float Q87 = Q * ncoRe + I * ncoIm;

            // ── 3. IQ filter per channel ──────────────────────────────────
            c88I1 += chAlpha * (I88 - c88I1);
            c88Q1 += chAlpha * (Q88 - c88Q1);
            c87I1 += chAlpha * (I87 - c87I1);
            c87Q1 += chAlpha * (Q87 - c87Q1);

            // ── 4. FM discriminator per channel ───────────────────────────
            float fI88 = c88I1, fQ88 = c88Q1;
            float mag88 = fI88 * fI88 + fQ88 * fQ88;
            float fm88;
            if (mag88 > 0.01f) {
                fm88 = (fI88 * pFQ88 - fQ88 * pFI88) / mag88;
                sig88++; epochSig88++;
            } else {
                fm88 = 0f;
            }
            pFI88 = fI88; pFQ88 = fQ88;
            fmMean88 += FM_ALPHA * (fm88 - fmMean88);
            float fmDC88 = fm88 - fmMean88;
            if (mag88 > 0.01f) {
                float absDC88 = Math.abs(fmDC88);
                fmAbs88 += absDC88;
                epochFmAbs88 += absDC88;
            }
            lp1_88 += LPF_ALPHA * (fmDC88 - lp1_88);
            lp2_88 += LPF_ALPHA * (lp1_88  - lp2_88);

            float fI87 = c87I1, fQ87 = c87Q1;
            float mag87 = fI87 * fI87 + fQ87 * fQ87;
            float fm87;
            if (mag87 > 0.01f) {
                fm87 = (fI87 * pFQ87 - fQ87 * pFI87) / mag87;
                sig87++; epochSig87++;
            } else {
                fm87 = 0f;
            }
            pFI87 = fI87; pFQ87 = fQ87;
            fmMean87 += FM_ALPHA * (fm87 - fmMean87);
            float fmDC87 = fm87 - fmMean87;
            if (mag87 > 0.01f) {
                float absDC87 = Math.abs(fmDC87);
                fmAbs87 += absDC87;
                epochFmAbs87 += absDC87;
            }
            lp1_87 += LPF_ALPHA * (fmDC87 - lp1_87);
            lp2_87 += LPF_ALPHA * (lp1_87  - lp2_87);

            // ── 5. Accumulate into per-channel symbol buffers ─────────────
            s88Curr[symIdx] = lp2_88;
            s87Curr[symIdx] = lp2_87;
            symIdx++;
            if (symIdx < SAMPLES_PER_SYMBOL) continue;
            symIdx = 0;

            // Full symbol period ready — make decisions for both channels.
            processSymbols(s88Prev, s88Curr, nrz88, hdN88, hdI88);
            processSymbols(s87Prev, s87Curr, nrz87, hdN87, hdI87);

            System.arraycopy(s88Curr, 0, s88Prev, 0, SAMPLES_PER_SYMBOL);
            System.arraycopy(s87Curr, 0, s87Prev, 0, SAMPLES_PER_SYMBOL);

            // Renormalize NCO every symbol period to prevent magnitude drift
            float nMag2 = ncoRe * ncoRe + ncoIm * ncoIm;
            if (nMag2 < 0.9995f || nMag2 > 1.0005f) {
                float inv = 1.0f / (float) Math.sqrt(nMag2);
                ncoRe *= inv; ncoIm *= inv;
            }
        }

        totalSamples += samples;

        // ─── Periodic diagnostics + auto-PPM state machine ───────────────────
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            lastLogTime = now;

            double avgAmp  = totalSamples > 0 ? ampSum / totalSamples : 0;
            long clipPct   = totalSamples > 0 ? clipCount * 100 / totalSamples : 0;

            // Epoch-based fmDev (this 10 s window only — reset below)
            double eDev88 = epochSig88 > 0
                    ? epochFmAbs88 / epochSig88 * SAMPLE_RATE / (2 * Math.PI) : 0;
            double eDev87 = epochSig87 > 0
                    ? epochFmAbs87 / epochSig87 * SAMPLE_RATE / (2 * Math.PI) : 0;

            double carr88Hz = fmMean88 * SAMPLE_RATE / (2 * Math.PI);
            double carr87Hz = fmMean87 * SAMPLE_RATE / (2 * Math.PI);

            String ppmState = ppmLocked
                    ? "locked"
                    : ("acquiring " + ppmLockCount + "/" + PPM_LOCK_INTERVALS);

            Log.d(TAG, "Stats: samples=" + totalSamples
                    + " avgAmp=" + String.format("%.1f", avgAmp) + " ADCcounts"
                    + " clip=" + clipPct + "%"
                    + " burst=" + burstCount
                    + " epochDev88=" + String.format("%.0f", eDev88) + "Hz"
                    + " carr88=" + String.format("%.0f", carr88Hz) + "Hz"
                    + " filter=" + (chAlpha == CH_ALPHA_WIDE ? "±12.5kHz" : "±15kHz")
                    + " ppm=" + ppmState
                    + " flags=" + flagsTotal
                    + " crcOK=" + crcValid + " crcFail=" + crcFail);

            // ── Periodic diagnostic hint ──────────────────────────────────
            // PPM acquisition is now frame-based (see tryDecodeFrame).
            // This section just provides a periodic noise-floor/saturation hint.
            if (crcValid == 0) {
                String hint;
                if (clipPct > 20) {
                    hint = "ADC SATURATING — reduce gain";
                } else if (avgAmp < 3) {
                    hint = "No signal — check antenna/connection";
                } else {
                    hint = "Noise floor — epochDev88=" + (int)eDev88
                            + "Hz epochDev87=" + (int)eDev87 + "Hz"
                            + " carr88=" + (int)carr88Hz + "Hz carr87=" + (int)carr87Hz + "Hz"
                            + " ppm=" + (ppmLocked ? "locked" : "acquiring " + ppmLockCount + "/" + PPM_LOCK_INTERVALS)
                            + " (filter=" + (chAlpha == CH_ALPHA_WIDE ? "±12.5kHz" : "±15kHz") + ")";
                }
                Log.d(TAG, "Hint: " + hint);
            }

            // Reset epoch accumulators for next window
            epochFmAbs88 = epochFmAbs87 = 0;
            epochSig88   = epochSig87   = 0;
        }
    }

    /**
     * Conditionally switch to narrow filter after auto-PPM lock.
     * Called once when auto-PPM lock is achieved.
     *
     * <p>The NCO is NOT adjusted here. Both AIS channels (Ch88B at +25 kHz,
     * Ch87B at −25 kHz) are mixed simultaneously by the same NCO. Any NCO
     * shift that moves Ch88B toward DC pushes Ch87B equally far in the wrong
     * direction. Instead, hardware PPM correction (applied
     * by {@link RtlTcpClient} at connection time) shifts the centre frequency
     * symmetrically so both channels remain near DC. We only narrow the filter
     * when the residual is small enough that both channels fit within ±15 kHz.
     *
     * @param carrHz  measured residual carrier offset from DC (in Hz)
     * @param ppm     estimated residual crystal PPM error
     */
    private void applyNcoCorrection(double carrHz, int ppm) {
        if (Math.abs(carrHz) < 15_000) {
            // Both channels are within ±15 kHz — switch to narrow filter.
            chAlpha = CH_ALPHA_NARROW;
            // Reset all filter and decode state so they converge cleanly.
            c88I1=c88Q1 = 0;
            c87I1=c87Q1 = 0;
            smoothedAmp = 0; burstCount = 0;
            pFI88=pFQ88=fmMean88=lp1_88=lp2_88 = 0;
            pFI87=pFQ87=fmMean87=lp1_87=lp2_87 = 0;
            Arrays.fill(s88Prev, 0); Arrays.fill(s88Curr, 0);
            Arrays.fill(s87Prev, 0); Arrays.fill(s87Curr, 0);
            symIdx = 0;
            Arrays.fill(nrz88, 0); Arrays.fill(nrz87, 0);
            for (int p = 0; p < N_PHASES; p++) {
                hdN88[p] = new HdlcDecoder(); hdI88[p] = new HdlcDecoder();
                hdN87[p] = new HdlcDecoder(); hdI87[p] = new HdlcDecoder();
            }
            dedupMap.clear();
            Log.d(TAG, "Auto-PPM: residual " + (int) carrHz + " Hz within ±15 kHz"
                    + " — switching to narrow filter, est. ppm=" + (ppm >= 0 ? "+" : "") + ppm);
        } else {
            // Residual too large for the narrow filter. Stay on wide filter for
            // the remainder of this connection. The estimated PPM will be stored
            // (via ppmCallback in the caller) and applied as hardware correction
            // on the next rtl_tcp connection.
            Log.d(TAG, "Auto-PPM: residual " + (int) carrHz + " Hz exceeds ±15 kHz"
                    + " — staying on wide filter, est. ppm=" + (ppm >= 0 ? "+" : "") + ppm);
        }
    }

    // ─── Symbol-level diagnostic ────────────────────────────────────────────
    private final float[] diagSums = new float[256];
    private int diagSumIdx = 0;
    // ─── Multi-phase symbol decisions for one channel ──────────────────────────

    private void processSymbols(float[] prev, float[] curr,
                                int[] nrzPrev,
                                HdlcDecoder[] hdlcN, HdlcDecoder[] hdlcI) {
        for (int p = 0; p < N_PHASES; p++) {
            int offset = p * PHASE_STEP;  // 0, 20, 40, 60, 80
            float sum = 0;
            for (int k = 0; k < SAMPLES_PER_SYMBOL; k++) {
                int src = offset + k;
                sum += (src < SAMPLES_PER_SYMBOL) ? prev[src] : curr[src - SAMPLES_PER_SYMBOL];
            }
            int rxBit = (sum >= 0) ? 1 : 0;
            int nrziBit = (rxBit == nrzPrev[p]) ? 1 : 0;
            nrzPrev[p] = rxBit;

            // Record sum for diagnostics (only phase 0, Channel 88B)
            if (p == 0) {
                diagSums[diagSumIdx & 0xff] = sum;
                diagSumIdx++;
            }

            int flags = hdlcN[p].receiveBit(nrziBit);
            if (flags != 0) {
                flagsTotal++;
                if ((flags & HdlcDecoder.FRAME_READY) != 0) tryDecodeFrame(hdlcN[p]);
            }
            flags = hdlcI[p].receiveBit(nrziBit ^ 1);
            if (flags != 0) {
                flagsTotal++;
                if ((flags & HdlcDecoder.FRAME_READY) != 0) tryDecodeFrame(hdlcI[p]);
            }
        }
    }

    // ─── Frame decode ─────────────────────────────────────────────────────────

    private void tryDecodeFrame(HdlcDecoder hdlc) {
        int byteCount = hdlc.lastFrameBitCount / 8;
        // AIS type 24A is 20 payload bytes + 2 FCS; position frames are larger.
        if (byteCount < 22 || byteCount > 64) return;

        byte[] fb = hdlc.frameBytes;
        int dataLen     = byteCount - 2;
        int computedCrc = crc16(fb, 0, dataLen) ^ 0xFFFF;
        int receivedCrc = (fb[dataLen] & 0xff) | ((fb[dataLen + 1] & 0xff) << 8);

        // Symbol-level signal quality: average |sum| over recent symbols
        int diagCount = Math.min(64, diagSumIdx);
        float diagAbsAvg = 0;
        int diagOnes = 0;
        for (int si = Math.max(0, diagSumIdx - 64); si < diagSumIdx; si++) {
            float s = diagSums[si & 0xff];
            diagAbsAvg += Math.abs(s);
            if (s >= 0) diagOnes++;
        }
        if (diagCount > 0) diagAbsAvg /= diagCount;

        if (computedCrc != receivedCrc) {
            crcFail++;
            if (crcDiagCount < 20) {
                crcDiagCount++;
                int diagType = readBits(fb, 0, 0, 6);
                int diagMmsi = (byteCount >= 5) ? readBits(fb, 0, 8, 30) : 0;
                String diagLabel;
                boolean logPos = false;
                double diagLat = 0, diagLon = 0;
                if (diagType >= 1 && diagType <= 3) {
                    diagLabel = "class A pos";
                    if (byteCount >= 21) {
                        int lonRaw = readBitsSigned(fb, 0, 61, 28);
                        int latRaw = readBitsSigned(fb, 0, 89, 27);
                        diagLat = latRaw / 600000.0;
                        diagLon = lonRaw / 600000.0;
                        logPos = true;
                    }
                } else if (diagType == 4) diagLabel = "base station";
                else if (diagType == 18) {
                    diagLabel = "class B pos";
                    if (byteCount >= 21) {
                        int lonRaw = readBitsSigned(fb, 0, 57, 28);
                        int latRaw = readBitsSigned(fb, 0, 85, 27);
                        diagLat = latRaw / 600000.0;
                        diagLon = lonRaw / 600000.0;
                        logPos = true;
                    }
                } else {
                    diagLabel = "unhandled";
                }
                String posStr = logPos
                        ? String.format(" lat=%.4f lon=%.4f", diagLat, diagLon)
                        : "";
                // Raw bits of first 8 bytes in AIS field order.
                StringBuilder bitStr = new StringBuilder();
                int dumpBytes = Math.min(8, byteCount);
                for (int bi = 0; bi < dumpBytes * 8 && bitStr.length() < 80; bi++) {
                    int byteIdx = bi / 8;
                    int bitIdx  = 7 - (bi % 8);
                    bitStr.append((char)('0' + ((fb[byteIdx] >> bitIdx) & 1)));
                }
                Log.d(TAG, "CRC fail #" + crcDiagCount
                        + " type=" + diagType + " " + diagLabel
                        + " mmsi=" + diagMmsi
                        + posStr
                        + " bits=" + bitStr.toString()
                        + " avg|sum|=" + String.format("%.3f", diagAbsAvg)
                        + " ones%=" + (diagCount > 0 ? diagOnes * 100 / diagCount : 0));
            }
            return;
        }
        crcValid++;
        int msgType = readBits(fb, 0, 0, 6);
        if (msgType < 1 || msgType > 27) {
            Log.d(TAG, "AIS reject: invalid type=" + msgType
                    + " bytes=" + byteCount);
            return;
        }
        Log.d(TAG, "CRC-valid frame: " + byteCount + " bytes"
                + " data[0..3]=" + String.format("%02X %02X %02X %02X",
                    fb[0] & 0xff, fb[1] & 0xff, fb[2] & 0xff, fb[3] & 0xff)
                + " fmMean88=" + String.format("%.0f", fmMean88 * SAMPLE_RATE / (2 * Math.PI)) + "Hz"
                + " avg|sum|=" + String.format("%.3f", diagAbsAvg)
                + " ones%=" + (diagCount > 0 ? diagOnes * 100 / diagCount : 0));
        // Frame-based PPM acquisition: fmMean at decode time reflects the carrier
        // offset. AIS bursts (26 ms) give ~2.5 FM_ALPHA time constants of convergence.
        if (!ppmLocked) {
            double frameFmMean = (fmMean88 + fmMean87) * 0.5;
            ppmCarrierSum  += frameFmMean;
            ppmSampleCount++;
            ppmLockCount++;
            double carrHz = frameFmMean * SAMPLE_RATE / (2.0 * Math.PI);
            int    ppm    = (int) Math.round(-carrHz * 1e6 / CENTER_FREQ);
            Log.d(TAG, "Auto-PPM: frame sample " + ppmLockCount + "/" + PPM_LOCK_INTERVALS
                    + " carrHz=" + (int)carrHz + " est_ppm=" + ppm);
            if (ppmLockCount >= PPM_LOCK_INTERVALS) {
                double avgFmMean = ppmCarrierSum / ppmSampleCount;
                double avgCarrHz = avgFmMean * SAMPLE_RATE / (2.0 * Math.PI);
                int    avgPpm    = (int) Math.round(-avgCarrHz * 1e6 / CENTER_FREQ);

                if (Math.abs(avgCarrHz) > 200_000) {
                    Log.d(TAG, "Auto-PPM: estimate " + (int) avgCarrHz + " Hz exceeds "
                            + "±200000 Hz filter limit"
                            + " — noise false-positive, discarding, retry");
                    ppmCarrierSum = 0; ppmSampleCount = 0; ppmLockCount = 0;
                } else {
                    if (Math.abs(avgCarrHz) > 300) {
                        applyNcoCorrection(avgCarrHz, avgPpm);
                    } else {
                        chAlpha = CH_ALPHA_NARROW;
                        Log.d(TAG, "Auto-PPM: carrier within 300 Hz, no correction needed"
                                + " — narrowing filter to ±15 kHz");
                    }
                    ppmLocked = true;
                    if (ppmCallback != null) ppmCallback.onPpmEstimated(avgPpm);
                }
            }
        }
        parseAisPayload(fb, 0, dataLen);
    }

    // ─── AIS payload ──────────────────────────────────────────────────────────

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
            // Class A position report
            mmsi      = readBits(data, off, 8, 30);
            navStatus = readBits(data, off, 38, 4);
            rot       = readBitsSigned(data, off, 42, 8);
            sogRaw    = readBits(data, off, 50, 10);
            lonRaw    = readBitsSigned(data, off, 61, 28);
            latRaw    = readBitsSigned(data, off, 89, 27);
            cogRaw    = readBits(data, off, 116, 12);
            heading   = readBits(data, off, 128, 9);
            Log.d(TAG, "AIS parse type=" + msgType + " mmsi=" + mmsi
                    + " lon=" + (lonRaw / 600000.0) + " lat=" + (latRaw / 600000.0));
        } else if (msgType == 4) {
            // Base station report — contains station position
            if (len < 21) return;
            mmsi      = readBits(data, off, 8, 30);
            navStatus = 15;
            rot       = 0;
            sogRaw    = 0;
            lonRaw    = readBitsSigned(data, off, 79, 28);
            latRaw    = readBitsSigned(data, off, 107, 27);
            cogRaw    = 0;
            heading   = 511;
            Log.d(TAG, "AIS parse type=4 (base station) mmsi=" + mmsi
                    + " lon=" + (lonRaw / 600000.0) + " lat=" + (latRaw / 600000.0));
        } else if (msgType == 18) {
            // Class B simplified position report
            if (len < 21) return;
            mmsi      = readBits(data, off, 8, 30);
            navStatus = 15;
            rot       = 0;
            sogRaw    = readBits(data, off, 46, 10);
            lonRaw    = readBitsSigned(data, off, 57, 28);
            latRaw    = readBitsSigned(data, off, 85, 27);
            cogRaw    = readBits(data, off, 112, 12);
            heading   = readBits(data, off, 124, 9);
            Log.d(TAG, "AIS parse type=18 mmsi=" + mmsi
                    + " lon=" + (lonRaw / 600000.0) + " lat=" + (latRaw / 600000.0));
        } else if (msgType == 19) {
            // Class B extended position report, includes name and ship type.
            if (len * 8 < 312) return;
            mmsi      = readBits(data, off, 8, 30);
            navStatus = 15;
            rot       = 0;
            sogRaw    = readBits(data, off, 46, 10);
            lonRaw    = readBitsSigned(data, off, 57, 28);
            latRaw    = readBitsSigned(data, off, 85, 27);
            cogRaw    = readBits(data, off, 112, 12);
            heading   = readBits(data, off, 124, 9);
            shipName  = readAisString(data, off, 143, 120);
            shipType  = readBits(data, off, 263, 8);
            updateStaticData(mmsi, shipName, shipType, -1, "", "", -1);
            Log.d(TAG, "AIS parse type=19 mmsi=" + mmsi
                    + " lon=" + (lonRaw / 600000.0) + " lat=" + (latRaw / 600000.0));
        } else if (msgType == 5) {
            parseType5StaticData(data, off, len);
            return;
        } else if (msgType == 24) {
            parseType24StaticData(data, off, len);
            return;
        } else if (msgType == 8) {
            // Binary broadcast message (AtoN, safety, environmental)
            mmsi = readBits(data, off, 8, 30);
            Log.d(TAG, "AIS type=8 binary broadcast mmsi=" + mmsi);
            return;
        } else {
            int unhandledMmsi = readBits(data, off, 8, 30);
            StringBuilder hex = new StringBuilder();
            for (int hi = 0; hi < Math.min(len, 8); hi++)
                hex.append(String.format("%02X ", data[off + hi] & 0xff));
            Log.d(TAG, "AIS unhandled type=" + msgType + " mmsi=" + unhandledMmsi
                    + " hex=" + hex.toString().trim());
            return;
        }

        if (mmsi <= 0 || mmsi > 999_999_999) { Log.d(TAG, "AIS reject: mmsi=" + mmsi); return; }

        long now = System.currentTimeMillis();
        Long lastSeen = dedupMap.get(mmsi);
        if (lastSeen != null && now - lastSeen < DEDUP_MS) return;
        dedupMap.put(mmsi, now);

        if (lonRaw == 0x6791AC0) { Log.d(TAG, "AIS reject: lon not available"); return; }
        if (latRaw == 0x3412140) { Log.d(TAG, "AIS reject: lat not available"); return; }

        StaticData sd = staticDataMap.get(mmsi);
        if (sd != null) {
            if (shipName.isEmpty()) shipName = sd.name;
            if (shipType <= 0) shipType = sd.shipType;
            draught = sd.draught;
            destination = sd.destination;
            eta = sd.eta;
            imoNumber = sd.imoNumber;
        }

        double lon = lonRaw / 600000.0;
        double lat = latRaw / 600000.0;
        double sog = sogRaw / 10.0;
        double cog = cogRaw / 10.0;

        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            Log.d(TAG, "AIS reject: coords out of range lat=" + lat + " lon=" + lon); return;
        }
        if (sog > 102.2) sog = 0;
        if (cog >= 360.0) cog = 0;

        Log.d(TAG, "AIS type=" + msgType + " mmsi=" + mmsi
                + " lat=" + lat + " lon=" + lon + " sog=" + sog);
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
            if (len * 8 < 160) return;
            String name = readAisString(data, off, 40, 120);
            updateStaticData(mmsi, name, -1, -1, "", "", -1);
            Log.d(TAG, "AIS static type=24A mmsi=" + mmsi + " name=" + name);
        } else if (partNumber == 1) {
            if (len * 8 < 48) return;
            int shipType = readBits(data, off, 40, 8);
            String callsign = len * 8 >= 132 ? readAisString(data, off, 90, 42) : "";
            updateStaticData(mmsi, "", shipType, -1, "", "", -1);
            Log.d(TAG, "AIS static type=24B mmsi=" + mmsi
                    + " callsign=" + callsign + " shipType=" + shipType);
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
            sb.append(c == '@' ? ' ' : (char) c);
        }
        return sb.toString().trim();
    }

    private static String formatEta(int month, int day, int hour, int minute) {
        if (month > 0 && month <= 12 && day > 0 && day <= 31
                && hour < 24 && minute < 60) {
            return String.format("%02d/%02d %02d:%02d", month, day, hour, minute);
        }
        return "";
    }

    // ─── Bit extraction (AIS fields are MSB-first within the decoded frame bytes) ──

    private int readBits(byte[] data, int byteOff, int bitOff, int numBits) {
        int result = 0;
        for (int i = 0; i < numBits; i++) {
            int absOff  = bitOff + i;
            int byteIdx = byteOff + absOff / 8;
            int bitIdx  = 7 - (absOff % 8);
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

    // ─── CRC-16/CCITT, LSB-first (polynomial 0x8408) ──────────────────────────

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

    // ─── HDLC decoder (one instance per phase per polarity per channel) ────────

    /** Stateful HDLC bit-stream decoder. Feed one bit at a time via receiveBit(). */
    private static class HdlcDecoder {
        static final int FLAG_SEEN   = 1;
        static final int FRAME_READY = 2;

        private int     hdlcShift         = 0;
        boolean         inFrame           = false;
        final byte[]    frameBytes        = new byte[512];
        int             frameBitCount     = 0;
        int             lastFrameBitCount = 0;
        private int     onesCount         = 0;

        int receiveBit(int bit) {
            hdlcShift = ((hdlcShift >>> 1) | (bit << 7)) & 0xFF;
            int result = 0;

            if (inFrame) {
                if (onesCount == 5) {
                    if (bit == 0) {
                        // Bit stuffing: discard this zero
                        onesCount = 0;
                        return 0;
                    } else {
                        // Six consecutive ones = closing flag (or abort)
                        result |= FLAG_SEEN;
                        if (frameBitCount >= 184) { // 23 bytes minimum
                            lastFrameBitCount = frameBitCount;
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
                    if (bit == 1) frameBytes[byteIdx] |=  (byte)(1 << bitIdx);
                    else          frameBytes[byteIdx] &= (byte)~(1 << bitIdx);
                    frameBitCount++;
                } else {
                    // Frame too long: discard
                    inFrame = false; frameBitCount = 0; onesCount = 0;
                }
                return result;
            }

            // Not in frame: watch for opening flag 0x7E
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
