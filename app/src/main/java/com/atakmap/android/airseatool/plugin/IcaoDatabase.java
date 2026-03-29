/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.util.JsonReader;
import android.util.JsonToken;
import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Manages a local trimmed copy of the ADS-B Exchange basic aircraft database.
 *
 * <p>Download: https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz
 *
 * <p>The full database is downloaded, decompressed, and trimmed to only
 * ICAO, MIL, MODEL, OWNOP, SHORT_TYPE fields, then stored as a compact
 * tab-separated file for fast loading. Lookups are O(1) via a HashMap.
 *
 * <p>Thread-safe: the in-memory map is replaced atomically after each load.
 */
public class IcaoDatabase {

    private static final String TAG = "IcaoDatabase";
    private static final String DB_FILENAME  = "icao_db.tsv";
    private static final String TMP_FILENAME = "icao_db_new.tsv";
    private static final String DL_FILENAME  = "icao_db_dl.gz";
    private static final String DB_URL =
            "https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz";

    /** Called when a download+rebuild completes (on a background thread). */
    public interface UpdateCallback {
        void onComplete(boolean success, String message);
    }

    /**
     * Called on a background thread to report download/parse progress.
     * @param stage  Human-readable stage label.
     * @param percent 0-100 during download, -1 for indeterminate stages.
     */
    public interface ProgressCallback {
        void onProgress(String stage, int percent);
    }

    private final File dbDir;
    /** Volatile so reads from the main thread always see the latest map. */
    private volatile Map<String, IcaoRecord> records = null;
    /** True while a background download+parse is in progress. */
    private volatile boolean downloading = false;
    /** Set to true by cancelDownload() to abort an in-progress operation. */
    private volatile boolean cancelled = false;
    /** The active HTTP connection, held so cancelDownload() can disconnect it. */
    private volatile HttpURLConnection activeConn = null;

    public IcaoDatabase(File dbDir) {
        this.dbDir = dbDir;
        dbDir.mkdirs();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns the record for the given ICAO hex address, or null if not found. */
    public IcaoRecord lookup(String icao) {
        if (icao == null || icao.isEmpty()) return null;
        Map<String, IcaoRecord> r = records;
        if (r == null) return null;
        return r.get(icao.toLowerCase());
    }

    /** True once the database has been loaded into memory. */
    public boolean isAvailable() {
        Map<String, IcaoRecord> r = records;
        return r != null && !r.isEmpty();
    }

    /** True if the trimmed TSV file exists on disk. */
    public boolean isFilePresent() {
        return getDbFile().exists();
    }

    /** True while a download is already running — callers should not start another. */
    public boolean isDownloading() {
        return downloading;
    }

    /** Cancels an in-progress download. The update callback will be called with success=false. */
    public void cancelDownload() {
        cancelled = true;
        HttpURLConnection conn = activeConn;
        if (conn != null) conn.disconnect();
    }

    /** Last-modified timestamp of the local TSV file, or 0 if not present. */
    public long getLastUpdatedMs() {
        File f = getDbFile();
        return f.exists() ? f.lastModified() : 0L;
    }

    /**
     * Loads the database from disk into memory on a background thread.
     * Safe to call multiple times; subsequent calls are a no-op if already loaded.
     */
    public void loadAsync() {
        if (records != null) return;
        new Thread(this::loadFromDisk, "ICAO-DB-Load").start();
    }

    /**
     * Downloads the full database from ADS-B Exchange, trims it to the
     * five required fields, saves it locally, then loads it into memory.
     * Both callbacks are invoked on the background thread.
     */
    public void downloadAndUpdate(UpdateCallback callback) {
        downloadAndUpdate(callback, null);
    }

    /**
     * Same as {@link #downloadAndUpdate(UpdateCallback)} but also reports
     * progress via {@code progress} during the download and parse stages.
     */
    public void downloadAndUpdate(UpdateCallback callback, ProgressCallback progress) {
        if (downloading) {
            if (callback != null) callback.onComplete(false, "Download already in progress");
            return;
        }
        downloading = true;
        cancelled = false;
        new Thread(() -> {
            try {
                int count = downloadAndProcess(progress);
                if (progress != null) progress.onProgress("Loading into memory\u2026", -1);
                loadFromDisk();
                String msg = "Loaded " + count + " records";
                Log.i(TAG, "ICAO DB update complete: " + msg);
                if (callback != null) callback.onComplete(true, msg);
            } catch (Exception e) {
                Log.e(TAG, "ICAO DB update failed", e);
                if (callback != null) callback.onComplete(false, e.getMessage());
            } finally {
                downloading = false;
            }
        }, "ICAO-DB-Download").start();
    }

    /** Deletes the local database file and clears the in-memory records. */
    public void deleteDatabase() {
        getDbFile().delete();
        records = null;
        Log.i(TAG, "ICAO DB deleted");
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private File getDbFile() {
        return new File(dbDir, DB_FILENAME);
    }

    /** Loads the local TSV file into a new HashMap, then atomically replaces {@code records}. */
    private void loadFromDisk() {
        File f = getDbFile();
        if (!f.exists()) {
            Log.w(TAG, "ICAO DB file not found: " + f.getAbsolutePath());
            return;
        }
        Map<String, IcaoRecord> newRecords = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Format: icao \t mil \t model \t ownop \t short_type
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                boolean mil = "Y".equals(p[1]);
                newRecords.put(p[0], new IcaoRecord(mil, p[2], p[3], p[4]));
            }
            records = newRecords;
            Log.i(TAG, "ICAO DB loaded: " + newRecords.size() + " records from " + f.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ICAO database from disk", e);
        }
    }

    /**
     * Downloads the .gz file, streams it through a JSON parser, and writes
     * the trimmed TSV to a temporary file before atomically renaming it.
     *
     * @return number of records written
     */
    private int downloadAndProcess(ProgressCallback progress) throws Exception {
        File dlFile  = new File(dbDir, DL_FILENAME);
        File tmpFile = new File(dbDir, TMP_FILENAME);
        File dbFile  = getDbFile();

        // ── Step 1: download ───────────────────────────────────────────────
        Log.i(TAG, "ICAO DB: downloading from " + DB_URL);
        if (progress != null) progress.onProgress("Connecting\u2026", 0);
        URL url = new URL(DB_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConn = conn;
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);  // large file; allow 5 min
        conn.setRequestProperty("User-Agent", "AirSeaTool/1.1.0");
        try {
            int code = conn.getResponseCode();
            if (cancelled) throw new Exception("Download cancelled");
            if (code != 200)
                throw new Exception("HTTP " + code + " from ICAO database server");
            long totalBytes = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(dlFile)) {
                byte[] buf = new byte[65536];
                int n;
                long readBytes = 0;
                int lastPct = -1;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled) throw new Exception("Download cancelled");
                    fos.write(buf, 0, n);
                    readBytes += n;
                    if (progress != null && totalBytes > 0) {
                        int pct = (int) (readBytes * 100L / totalBytes);
                        if (pct != lastPct) {
                            progress.onProgress("Downloading\u2026", pct);
                            lastPct = pct;
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
            activeConn = null;
        }
        Log.i(TAG, "ICAO DB: download complete (" + dlFile.length() + " bytes compressed)");
        if (progress != null) progress.onProgress("Parsing records\u2026", -1);

        // ── Step 2: parse JSON and write trimmed TSV ───────────────────────
        // The file may be a JSON array, a single top-level keyed object, or JSONL
        // (newline-delimited JSON objects with an "icao" field each).
        // setLenient(true) allows reading multiple top-level values for JSONL.
        int count = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(new FileInputStream(dlFile));
             JsonReader reader = new JsonReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new FileWriter(tmpFile))) {

            reader.setLenient(true);

            JsonToken root = reader.peek();
            if (root == JsonToken.BEGIN_ARRAY) {
                // JSON array: [{icao:..., mil:..., ...}, ...]
                reader.beginArray();
                while (reader.hasNext()) {
                    if (cancelled) throw new Exception("Download cancelled");
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
                    String[] h = {null};
                    IcaoRecord rec = parseRecord(reader, h);
                    if (rec != null && h[0] != null && hasUsefulData(rec)) {
                        writeRecord(writer, h[0], rec);
                        count++;
                    }
                }
                reader.endArray();
            } else {
                // JSONL or single keyed-object: consume top-level tokens until EOF.
                // Each BEGIN_OBJECT is treated as one aircraft record containing an "icao" field.
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    if (cancelled) throw new Exception("Download cancelled");
                    JsonToken tok = reader.peek();
                    if (tok != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
                    String[] h = {null};
                    IcaoRecord rec = parseRecord(reader, h);
                    if (rec != null && h[0] != null && hasUsefulData(rec)) {
                        writeRecord(writer, h[0], rec);
                        count++;
                    }
                }
            }
        }
        Log.i(TAG, "ICAO DB: parsed " + count + " useful records");

        // ── Step 3: atomic rename ──────────────────────────────────────────
        dlFile.delete();
        if (dbFile.exists()) dbFile.delete();
        if (!tmpFile.renameTo(dbFile))
            throw new Exception("Failed to rename temp DB file to " + dbFile.getName());

        return count;
    }

    /**
     * Parses a single aircraft JSON object.
     * If {@code icaoOut} is non-null, populates {@code icaoOut[0]} with the "icao" field value.
     */
    private static IcaoRecord parseRecord(JsonReader reader, String[] icaoOut) {
        String icao      = null;
        String model     = "";
        String ownop     = "";
        String shortType = "";
        boolean isMil    = false;
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                JsonToken valToken = reader.peek();
                if (valToken == JsonToken.NULL) {
                    reader.nextNull();
                    continue;
                }
                switch (name) {
                    case "icao":
                        icao = (valToken == JsonToken.STRING)
                                ? reader.nextString().toLowerCase() : null;
                        if (icao == null) reader.skipValue();
                        break;
                    case "mil":
                        // Stored as "Y"/""/bool/int depending on database version
                        if (valToken == JsonToken.BOOLEAN) {
                            isMil = reader.nextBoolean();
                        } else if (valToken == JsonToken.STRING) {
                            String v = reader.nextString();
                            isMil = "Y".equalsIgnoreCase(v) || "YES".equalsIgnoreCase(v)
                                    || "true".equalsIgnoreCase(v) || "1".equals(v);
                        } else if (valToken == JsonToken.NUMBER) {
                            isMil = reader.nextInt() != 0;
                        } else {
                            reader.skipValue();
                        }
                        break;
                    case "model":
                        if (valToken == JsonToken.STRING) model = reader.nextString();
                        else reader.skipValue();
                        break;
                    case "ownop":
                        if (valToken == JsonToken.STRING) ownop = reader.nextString();
                        else reader.skipValue();
                        break;
                    case "short_type":
                        if (valToken == JsonToken.STRING) shortType = reader.nextString();
                        else reader.skipValue();
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        } catch (Exception e) {
            Log.w(TAG, "Skipping malformed record: " + e.getMessage());
            return null;
        }
        if (icaoOut != null) icaoOut[0] = icao;
        return new IcaoRecord(isMil,
                model     != null ? model     : "",
                ownop     != null ? ownop     : "",
                shortType != null ? shortType : "");
    }

    /** A record is worth keeping if it has military status, model, ownop, or short_type. */
    private static boolean hasUsefulData(IcaoRecord rec) {
        return rec.mil || !rec.model.isEmpty() || !rec.ownop.isEmpty() || !rec.shortType.isEmpty();
    }

    /** Writes one TSV record: icao \t mil \t model \t ownop \t short_type */
    private static void writeRecord(BufferedWriter writer, String icao, IcaoRecord rec)
            throws Exception {
        writer.write(icao);
        writer.write('\t');
        writer.write(rec.mil ? "Y" : "");
        writer.write('\t');
        writer.write(sanitize(rec.model));
        writer.write('\t');
        writer.write(sanitize(rec.ownop));
        writer.write('\t');
        writer.write(sanitize(rec.shortType));
        writer.newLine();
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
