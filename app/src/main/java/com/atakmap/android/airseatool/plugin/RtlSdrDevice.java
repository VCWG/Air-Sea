/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.atakmap.coremap.log.Log;

import java.io.IOException;
import java.util.Map;

/**
 * USB driver for RTL2832U + R820T/R820T2 SDR dongles.
 *
 * Control transfer protocol (from the public librtlsdr source, GPL-2.0):
 *
 *   Block indices (librtlsdr BLOCK_* constants):
 *     BLOCK_USB = 0  → wIndex = (0<<8)|0x10 = 0x0010
 *     BLOCK_SYS = 1  → wIndex = (1<<8)|0x10 = 0x0110
 *     BLOCK_IIC = 6  → wIndex = (6<<8)|0x10 = 0x0610 (write) / 0x0600 (read)
 *
 *   Write reg:   controlTransfer(0x40, 0, addr,       (block<<8)|0x10, data, len, t)
 *   Read  reg:   controlTransfer(0xC0, 0, addr,        block<<8,       data, len, t)
 *   Write demod: controlTransfer(0x40, 0, (reg<<8)|0x20, 0x10|page,   data, len, t)
 *   I2C write:   controlTransfer(0x40, 0, i2c_addr,   (6<<8)|0x10,    data, len, t)
 *   I2C read:    controlTransfer(0xC0, 0, i2c_addr,    6<<8,          data, len, t)
 *
 * IQ samples arrive on bulk endpoint 0x81 as unsigned 8-bit interleaved I, Q pairs
 * (centre = 127.  Subtract 127.4 before processing.)
 */
public class RtlSdrDevice {

    private static final String TAG = "RtlSdrDevice";

    // Realtek USB vendor ID (0x0BDA)
    public static final int VENDOR_ID = 0x0BDA;
    // Supported RTL2832U product IDs
    public static final int[] PRODUCT_IDS = {0x2832, 0x2837, 0x2838, 0x2839, 0x283A};

    // Register block indices — must match librtlsdr BLOCK_* (NOT off-by-one)
    private static final int BLK_USB  = 0;   // USB control block
    private static final int BLK_SYS  = 1;   // System / demod-power block
    private static final int BLK_IIC  = 6;   // I2C block

    // USB block register addresses
    private static final int USB_SYSCTL     = 0x2000;
    private static final int USB_EPA_CTL    = 0x2148;
    private static final int USB_EPA_MAXPKT = 0x2158;

    // SYS block register addresses
    private static final int DEMOD_CTL   = 0x3000;  // main demod control
    private static final int DEMOD_CTL_1 = 0x300b;  // demod power control

    // R820T2 I2C address (7-bit, 0x34 = 52 decimal)
    private static final int R820T_ADDR = 0x34;

    // Crystal frequency (Hz)
    private static final long XTAL_FREQ = 28_800_000L;

    // USB transfer timeout (ms)
    private static final int TIMEOUT = 500;

    // R820T2 initial register values for regs 0x05–0x1F (27 bytes).
    // Source: librtlsdr r820t.c (GPL-2.0, steve-m/librtlsdr).
    private static final int[] R820T_INIT = {
        0x83, 0x32, 0x75,        // 0x05, 0x06, 0x07
        0xc0, 0x40, 0xd6, 0x6c,  // 0x08, 0x09, 0x0a, 0x0b
        0xf5, 0x63, 0x75, 0x68,  // 0x0c, 0x0d, 0x0e, 0x0f
        0x6c, 0x83, 0x80, 0x00,  // 0x10, 0x11, 0x12, 0x13
        0x0f, 0x00, 0xc0, 0x30,  // 0x14, 0x15, 0x16, 0x17
        0x48, 0xcc, 0x60, 0x00,  // 0x18, 0x19, 0x1a, 0x1b
        0x54, 0xae, 0x4a, 0xc0   // 0x1c, 0x1d, 0x1e, 0x1f
    };

    private UsbDevice           device;
    private UsbDeviceConnection conn;
    private UsbInterface        iface;
    private UsbEndpoint         bulkIn;
    private volatile boolean    streaming;

    /** IQ sample callback; called on the streaming thread. */
    @FunctionalInterface
    public interface IqCallback {
        void onSamples(byte[] buf, int len);
    }

    // ─── Device discovery ──────────────────────────────────────────────────

    /** Returns the first RTL-SDR device found, or null. */
    public static UsbDevice findDevice(UsbManager mgr) {
        for (Map.Entry<String, UsbDevice> e : mgr.getDeviceList().entrySet()) {
            UsbDevice d = e.getValue();
            if (d.getVendorId() == VENDOR_ID) {
                for (int pid : PRODUCT_IDS) {
                    if (d.getProductId() == pid) return d;
                }
            }
        }
        return null;
    }

    // ─── Open / close ──────────────────────────────────────────────────────

    /**
     * Opens the given USB device. Requires USB permission to have been granted.
     * @return true on success.
     */
    public boolean open(UsbManager mgr, UsbDevice dev) {
        device = dev;
        conn = mgr.openDevice(dev);
        if (conn == null) {
            Log.e(TAG, "openDevice returned null — permission not granted?");
            return false;
        }

        // Find the vendor-class interface with the most endpoints (alt setting with bulk IN).
        iface = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface candidate = dev.getInterface(i);
            Log.d(TAG, "iface[" + i + "] class=0x"
                    + Integer.toHexString(candidate.getInterfaceClass())
                    + " eps=" + candidate.getEndpointCount());
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint ep = candidate.getEndpoint(j);
                Log.d(TAG, "  ep[" + j + "] addr=0x"
                        + Integer.toHexString(ep.getAddress())
                        + " type=" + ep.getType()
                        + " dir=" + ep.getDirection()
                        + " maxpkt=" + ep.getMaxPacketSize());
            }
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                // Prefer the alternate setting that has the most endpoints (bulk IN active)
                if (iface == null || candidate.getEndpointCount() > iface.getEndpointCount()) {
                    iface = candidate;
                }
            }
        }
        if (iface == null) iface = dev.getInterface(0); // fallback

        boolean claimed = conn.claimInterface(iface, true);
        Log.d(TAG, "claimInterface(force=true): " + claimed);
        if (!claimed) {
            Log.e(TAG, "claimInterface failed");
            conn.close();
            conn = null;
            return false;
        }

        // Settle after claiming — give the device time to stabilise
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        // Find bulk IN endpoint
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                bulkIn = ep;
                break;
            }
        }
        if (bulkIn == null) {
            Log.e(TAG, "No bulk IN endpoint found");
            conn.releaseInterface(iface);
            conn.close();
            conn = null;
            return false;
        }
        Log.d(TAG, "Opened " + dev.getProductName()
                + " VID=" + Integer.toHexString(dev.getVendorId())
                + " PID=" + Integer.toHexString(dev.getProductId()));
        return true;
    }

    public void close() {
        stopStreaming();
        if (conn != null) {
            if (iface != null) conn.releaseInterface(iface);
            conn.close();
            conn = null;
        }
    }

    // ─── Initialization ────────────────────────────────────────────────────

    /**
     * Full device initialization matching librtlsdr rtlsdr_init_baseband().
     * Call after open().
     */
    public void initialize() throws IOException {
        // Probe: try a vendor read to check if control transfers work at all.
        byte[] probe = new byte[1];
        int probRet = conn.controlTransfer(0xC0, 0, USB_SYSCTL, BLK_USB << 8, probe, 1, TIMEOUT);
        Log.d(TAG, "probe read USB_SYSCTL=" + probRet
                + (probRet >= 0 ? " val=0x" + Integer.toHexString(probe[0] & 0xff) : " (ctrl xfer blocked)"));

        if (probRet < 0) {
            // Control transfers are blocked on this Android USB stack.
            // Attempt streaming without initialisation — the device may be usable
            // if it was previously configured (e.g., by the OS or a prior session).
            Log.w(TAG, "Vendor control transfers unavailable; skipping init — streaming anyway");
            return;
        }

        // 1. USB block init
        writeReg(BLK_USB, USB_SYSCTL, 0x09, 1);
        writeReg(BLK_USB, USB_EPA_MAXPKT, 0x0002, 2);
        writeReg(BLK_USB, USB_EPA_CTL, 0x1002, 2);

        // 2. Power on demodulator (SYS block)
        writeReg(BLK_SYS, DEMOD_CTL_1, 0x22, 1);
        writeReg(BLK_SYS, DEMOD_CTL, 0xe8, 1);

        // 3. Reset demodulator — required before any demod reg access
        writeDemodReg(1, 0x01, 0x14, 1);
        writeDemodReg(1, 0x01, 0x00, 1);

        // 4. Disable spectrum inversion / adjacent channel rejection
        writeDemodReg(1, 0xb1, 0x1b, 1);

        // 5. DC correction
        writeDemodReg(1, 0x1a, 0x05, 1);

        // 6. Output format: complex unsigned 8-bit
        writeDemodReg(0, 0x19, 0x05, 1);

        // 7. ADC clock selection
        writeDemodReg(0, 0x17, 0x10, 1);

        // 8. Init R820T2 tuner via I2C
        setI2cGate(true);
        initR820T();
        setI2cGate(false);

        // 9. Set IF frequency to 0 (direct conversion)
        writeDemodReg(1, 0x19, 0x00, 1);
        writeDemodReg(1, 0x1a, 0x00, 1);
        writeDemodReg(1, 0x1b, 0x00, 1);

        Log.d(TAG, "RTL-SDR initialized");
    }

    // ─── Sample rate ───────────────────────────────────────────────────────

    /**
     * Set the IQ sample rate (Hz). Supported range: ~225,000 – 3,200,000.
     * Common values: 2,000,000 (ADS-B), 288,000 (AIS).
     * Matches librtlsdr rtlsdr_set_sample_rate() demod register writes.
     */
    public void setSampleRate(int rateHz) throws IOException {
        long rsamp_ratio = (XTAL_FREQ << 22) / rateHz;
        rsamp_ratio &= 0x0FFFFFFCL;

        // Page 1 regs 0x9f (high word) and 0xa1 (low word)
        try {
            writeDemodReg(1, 0x9f, (int) (rsamp_ratio >> 16), 2);
            writeDemodReg(1, 0xa1, (int) (rsamp_ratio & 0xffff), 2);
            writeDemodReg(1, 0x01, 0x14, 1);
            writeDemodReg(1, 0x01, 0x10, 1);
            double actual = (double) XTAL_FREQ * (1L << 22) / rsamp_ratio;
            Log.d(TAG, "Sample rate: requested=" + rateHz + " actual=" + (int) actual);
        } catch (IOException e) {
            Log.w(TAG, "setSampleRate: " + e.getMessage() + " (ctrl xfer blocked, using default)");
        }
    }

    // ─── Frequency ─────────────────────────────────────────────────────────

    /**
     * Tune to center frequency in Hz.
     * Opens/closes the I2C gate internally.
     */
    public void setFrequency(long freqHz) throws IOException {
        try {
            setI2cGate(true);
            setR820TFrequency(freqHz);
            setI2cGate(false);
            writeDemodReg(1, 0x19, 0x00, 1);
            writeDemodReg(1, 0x1a, 0x00, 1);
            writeDemodReg(1, 0x1b, 0x00, 1);
        } catch (IOException e) {
            Log.w(TAG, "setFrequency: " + e.getMessage() + " (ctrl xfer blocked, freq unset)");
        }
    }

    // ─── Streaming ─────────────────────────────────────────────────────────

    /** Start streaming IQ samples. Fires callback on a dedicated thread. */
    public void startStreaming(IqCallback cb) throws IOException {
        if (streaming) return;

        // Best-effort endpoint reset — skip if control transfers are unavailable.
        try {
            writeReg(BLK_USB, USB_EPA_CTL, 0x1002, 2);
            writeReg(BLK_USB, USB_EPA_CTL, 0x0000, 2);
        } catch (IOException e) {
            Log.w(TAG, "startStreaming: endpoint reset skipped (" + e.getMessage() + ")");
        }

        streaming = true;
        final byte[] buf = new byte[65536];
        new Thread(() -> {
            Log.d(TAG, "Bulk streaming thread started");
            int totalBytes = 0;
            int errors = 0;
            while (streaming) {
                int n = conn.bulkTransfer(bulkIn, buf, buf.length, TIMEOUT);
                if (n > 0) {
                    if (totalBytes == 0)
                        Log.d(TAG, "First bulk data: " + n + " bytes, first4=["
                                + (buf[0]&0xff) + "," + (buf[1]&0xff)
                                + "," + (buf[2]&0xff) + "," + (buf[3]&0xff) + "]");
                    totalBytes += n;
                    cb.onSamples(buf, n);
                } else if (n <= 0 && streaming) {
                    errors++;
                    if (errors <= 5 || errors % 50 == 0)
                        Log.w(TAG, "Bulk transfer error " + errors + ": " + n);
                    if (errors > 20) {
                        Log.e(TAG, "Too many bulk errors, stopping");
                        break;
                    }
                }
            }
            Log.d(TAG, "Bulk streaming stopped. totalBytes=" + totalBytes + " errors=" + errors);
        }, "RTL-SDR-Stream").start();
    }

    public void stopStreaming() {
        streaming = false;
    }

    // ─── RTL2832U register access ──────────────────────────────────────────

    /**
     * Write to a block register.
     * wIndex = (block << 8) | 0x10   (matches librtlsdr rtlsdr_write_array)
     */
    private void writeReg(int block, int addr, int val, int len) throws IOException {
        byte[] data = new byte[len];
        if (len == 1) {
            data[0] = (byte) (val & 0xff);
        } else {
            data[0] = (byte) (val >> 8);
            data[1] = (byte) (val & 0xff);
        }
        int index = (block << 8) | 0x10;
        int r = conn.controlTransfer(0x40, 0, addr, index, data, len, TIMEOUT);
        if (r < 0)
            throw new IOException("writeReg(blk=" + block + ",addr=0x"
                    + Integer.toHexString(addr) + ") failed: " + r);
    }

    /** Write to RTL2832U demodulator page/reg.
     *  wValue = (reg << 8) | 0x20,  wIndex = 0x10 | page
     *  Matches librtlsdr rtlsdr_demod_write_reg(). */
    private void writeDemodReg(int page, int reg, int val, int len) throws IOException {
        byte[] data = new byte[len];
        if (len == 1) {
            data[0] = (byte) (val & 0xff);
        } else {
            data[0] = (byte) (val >> 8);
            data[1] = (byte) (val & 0xff);
        }
        int wValue = (reg << 8) | 0x20;
        int wIndex = 0x10 | page;
        int r = conn.controlTransfer(0x40, 0, wValue, wIndex, data, len, TIMEOUT);
        if (r < 0)
            throw new IOException("writeDemodReg(p=" + page + ",r=" + reg + ") failed: " + r);
    }

    // ─── I2C (tuner) access ────────────────────────────────────────────────

    /** Open or close the RTL2832U I2C repeater gate to the tuner. */
    private void setI2cGate(boolean open) throws IOException {
        writeDemodReg(1, 0x01, open ? 0x18 : 0x10, 1);
    }

    /**
     * Write one byte to an R820T2 register via I2C.
     * data = [reg_addr, value]
     * wValue = R820T I2C address (0x34), wIndex = (BLK_IIC<<8)|0x10 = 0x0610
     */
    private void i2cWriteReg(int reg, int val) throws IOException {
        byte[] data = {(byte) reg, (byte) val};
        int index = (BLK_IIC << 8) | 0x10;
        int r = conn.controlTransfer(0x40, 0, R820T_ADDR, index, data, 2, TIMEOUT);
        if (r < 0)
            throw new IOException("i2cWriteReg(0x" + Integer.toHexString(reg) + ") failed: " + r);
    }

    /**
     * Read one byte from an R820T2 register via I2C burst read.
     * R820T outputs registers 0x00-0x0F on a burst read; we return the requested index.
     * Returns 0 on read error (non-fatal; used only for PLL lock check).
     */
    private int i2cReadReg(int reg) {
        // Send read-address 0x00 to start R820T burst output
        byte[] wr = {0x00};
        conn.controlTransfer(0x40, 0, R820T_ADDR, (BLK_IIC << 8) | 0x10, wr, 1, TIMEOUT);
        // Burst-read 16 bytes (R820T register file)
        byte[] rd = new byte[16];
        int r = conn.controlTransfer(0xC0, 0, R820T_ADDR, BLK_IIC << 8, rd, rd.length, TIMEOUT);
        if (r < 0) return 0;
        return (reg < rd.length) ? (rd[reg] & 0xff) : 0;
    }

    // ─── R820T2 initialization ─────────────────────────────────────────────

    private void initR820T() throws IOException {
        // Write initial register array (regs 0x05 through 0x1F)
        for (int i = 0; i < R820T_INIT.length; i++) {
            i2cWriteReg(0x05 + i, R820T_INIT[i]);
        }
        Log.d(TAG, "R820T2 initialized");
    }

    /**
     * Configure R820T2 PLL for the given frequency.
     * Algorithm from librtlsdr r820t.c (GPL-2.0).
     */
    private void setR820TFrequency(long freqHz) throws IOException {
        final long pll_ref = XTAL_FREQ / 2;  // 14,400,000 Hz

        // Select VCO divider: VCO must be in [1,770 MHz, 3,540 MHz]
        long vco_freq = freqHz;
        int vco_div = 2;
        while (vco_freq < 1_770_000_000L) {
            vco_freq <<= 1;
            vco_div <<= 1;
        }

        // Encode divider into top 3 bits of reg 0x10
        int divRegVal;
        switch (vco_div) {
            case  2: divRegVal = 0x00; break;
            case  4: divRegVal = 0x20; break;
            case  8: divRegVal = 0x40; break;
            case 16: divRegVal = 0x60; break;
            default: divRegVal = 0x80; break;
        }
        int reg10 = (i2cReadReg(0x10) & 0x1f) | divRegVal;
        i2cWriteReg(0x10, reg10);

        // PLL integer and fractional parts
        long nint    = vco_freq / (2 * pll_ref);
        long vco_fra = vco_freq - 2 * pll_ref * nint;

        // Clamp fractional part
        if (vco_fra < pll_ref / 64) {
            vco_fra = 0;
        } else if (vco_fra > pll_ref * 127 / 64) {
            vco_fra = 0;
            nint++;
        }

        // ni and si pack into N register
        int ni = (int) ((nint - 13) / 4);
        int si = (int) (nint - 4 * (ni + 1));
        i2cWriteReg(0x14, (ni + (si << 6)) & 0xff);

        // SDM fractional
        int sdm = (int) (65536L * vco_fra / pll_ref);
        i2cWriteReg(0x16, (sdm >> 8) & 0xff);
        i2cWriteReg(0x15, sdm & 0xff);

        // Enable PLL
        i2cWriteReg(0x12, 0x80);

        // Poll for PLL lock (reg 0x02 bit 6)
        boolean locked = false;
        for (int i = 0; i < 10; i++) {
            int stat = i2cReadReg(0x02);
            if ((stat & 0x40) != 0) { locked = true; break; }
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }
        if (!locked) {
            Log.w(TAG, "R820T2 PLL did not lock for freq=" + freqHz);
        } else {
            Log.d(TAG, "R820T2 locked: freq=" + freqHz
                    + " vco_div=" + vco_div + " nint=" + nint + " sdm=" + sdm);
        }

        // Disable PLL calibration
        i2cWriteReg(0x12, 0x00);
    }
}
