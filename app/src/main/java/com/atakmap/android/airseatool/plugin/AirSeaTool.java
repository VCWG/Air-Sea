/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.view.LayoutInflater;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

import java.util.Locale;

public class AirSeaTool implements IPlugin,
        AisStreamClient.Listener,
        AdsbStreamClient.Listener {

    private static final String TAG = "AirSeaTool";
    private static final String PREFS_NAME = "ais_tool_prefs";

    // Maritime prefs
    private static final String PREF_MARITIME_ENABLED  = "maritime_enabled";
    private static final String PREF_API_KEY            = "api_key";
    private static final String PREF_MIN_LAT            = "min_lat";
    private static final String PREF_MAX_LAT            = "max_lat";
    private static final String PREF_MIN_LON            = "min_lon";
    private static final String PREF_MAX_LON            = "max_lon";
    private static final String PREF_FREQUENCY_INDEX    = "frequency_index";
    private static final String PREF_BROADCAST_ALL      = "broadcast_all";

    // Air prefs
    private static final String PREF_AIR_ENABLED        = "air_enabled";
    private static final String PREF_AIR_API_KEY        = "air_api_key_";  // + index suffix
    private static final String PREF_AIR_SOURCE_INDEX   = "air_source_index";
    private static final String PREF_AIR_BROADCAST_ALL  = "air_broadcast_all";

    private static final int[] FREQUENCY_VALUES = {5, 10, 30, 60, 300, 900};
    private static final String[] FREQUENCY_LABELS = {
            "5 seconds", "10 seconds", "30 seconds", "60 seconds",
            "5 minutes", "15 minutes"
    };
    private static final int DEFAULT_FREQUENCY_INDEX = 2; // 30 seconds

    private static final String[] AIR_SOURCE_LABELS =
            {"adsb.fi", "airplanes.live", "adsb.lol", "OpenSky"};

    private final android.content.Context pluginContext;
    private final IHostUIService uiService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ToolbarItem toolbarItem;
    private Pane pane;

    // Maritime
    private AisStreamClient aisClient;
    private final ShipMarkerManager shipMarkerManager;
    private String apiKey = "";
    private String lastApiKey;
    private double[][] lastBoundingBox;

    // Air
    private AdsbStreamClient adsbClient;
    private final AirMarkerManager airMarkerManager;
    private final String[] airApiKeys = new String[]{"", "", "", ""};

    private boolean syncing = false;

    // Views
    private View paneView;
    private View maritimeContent;
    private View airContent;

    // Maritime views
    private CheckBox maritimeEnableCheckbox;
    private TextView apiKeyDisplay;
    private CheckBox broadcastCheckbox;
    private Spinner dataSourceSpinner;

    // Air views
    private CheckBox airEnableCheckbox;
    private Spinner airDataSourceSpinner;
    private TextView airApiKeyDisplay;
    private CheckBox airBroadcastCheckbox;

    // Shared views
    private EditText minLatInput, maxLatInput, minLonInput, maxLonInput;
    private Spinner frequencySpinner;
    private Button syncBtn;
    private Button useMapViewBtn;
    private TextView maritimeStatusText;
    private TextView airStatusText;

    @SuppressWarnings("deprecation")
    public AirSeaTool(IServiceController serviceController) {
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        } else {
            pluginContext = null;
        }

        uiService = serviceController.getService(IHostUIService.class);
        shipMarkerManager = new ShipMarkerManager(
                FREQUENCY_VALUES[DEFAULT_FREQUENCY_INDEX]);
        airMarkerManager = new AirMarkerManager(
                FREQUENCY_VALUES[DEFAULT_FREQUENCY_INDEX]);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService != null) uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        stopSync();
        if (uiService != null) uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        if (pane == null) {
            paneView = LayoutInflater.from(pluginContext)
                    .inflate(R.layout.main_layout, null);
            setupUI(paneView);
            loadPreferences();
            updateSyncButton();
            updateApiKeyDisplay();
            updateAirApiKeyDisplay();

            pane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Right)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .build();
        }

        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void setupUI(View view) {
        maritimeContent = view.findViewById(R.id.maritime_content);
        airContent      = view.findViewById(R.id.air_content);

        // Maritime section
        maritimeEnableCheckbox = view.findViewById(R.id.maritime_enable_checkbox);
        maritimeEnableCheckbox.setOnClickListener(v -> {
            boolean checked = maritimeEnableCheckbox.isChecked();
            maritimeContent.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (syncing) {
                if (checked) {
                    startMaritimeSync();
                } else {
                    stopMaritimeSync();
                }
            }
        });

        dataSourceSpinner = view.findViewById(R.id.data_source_spinner);
        ArrayAdapter<String> dsAdapter = new ArrayAdapter<>(pluginContext,
                R.layout.spinner_item_black,
                new String[]{"aisstream.io"});
        dsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        dataSourceSpinner.setAdapter(dsAdapter);

        view.findViewById(R.id.data_source_tooltip)
                .setOnClickListener(v -> showMaritimeTooltip());

        apiKeyDisplay = view.findViewById(R.id.api_key_display);
        apiKeyDisplay.setOnClickListener(v -> showApiKeyDialog());

        broadcastCheckbox = view.findViewById(R.id.broadcast_all_checkbox);
        broadcastCheckbox.setOnClickListener(v ->
                shipMarkerManager.setBroadcastAll(broadcastCheckbox.isChecked()));

        // Air section
        airEnableCheckbox = view.findViewById(R.id.air_enable_checkbox);
        airEnableCheckbox.setOnClickListener(v -> {
            boolean checked = airEnableCheckbox.isChecked();
            airContent.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (syncing) {
                if (checked) {
                    startAirSync();
                } else {
                    stopAirSync();
                }
            }
        });

        airDataSourceSpinner = view.findViewById(R.id.air_data_source_spinner);
        ArrayAdapter<String> airDsAdapter = new ArrayAdapter<>(pluginContext,
                R.layout.spinner_item_black, AIR_SOURCE_LABELS);
        airDsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        airDataSourceSpinner.setAdapter(airDsAdapter);
        airDataSourceSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v,
                    int position, long id) {
                updateAirApiKeyDisplay();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        view.findViewById(R.id.air_data_source_tooltip)
                .setOnClickListener(v -> showAirTooltip());

        airApiKeyDisplay = view.findViewById(R.id.air_api_key_display);
        airApiKeyDisplay.setOnClickListener(v -> showAirApiKeyDialog());

        airBroadcastCheckbox = view.findViewById(R.id.air_broadcast_all_checkbox);
        airBroadcastCheckbox.setOnClickListener(v ->
                airMarkerManager.setBroadcastAll(airBroadcastCheckbox.isChecked()));

        // Shared
        minLatInput = view.findViewById(R.id.min_lat_input);
        maxLatInput = view.findViewById(R.id.max_lat_input);
        minLonInput = view.findViewById(R.id.min_lon_input);
        maxLonInput = view.findViewById(R.id.max_lon_input);

        useMapViewBtn = view.findViewById(R.id.use_map_view_btn);
        useMapViewBtn.setOnClickListener(v -> useCurrentMapView());

        frequencySpinner = view.findViewById(R.id.frequency_spinner);
        ArrayAdapter<String> freqAdapter = new ArrayAdapter<>(pluginContext,
                R.layout.spinner_item_black, FREQUENCY_LABELS);
        freqAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        frequencySpinner.setAdapter(freqAdapter);
        frequencySpinner.setSelection(DEFAULT_FREQUENCY_INDEX);
        frequencySpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v,
                    int position, long id) {
                int secs = FREQUENCY_VALUES[position];
                shipMarkerManager.setUpdateFrequency(secs);
                airMarkerManager.setUpdateFrequency(secs);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        syncBtn = view.findViewById(R.id.sync_btn);
        syncBtn.setOnClickListener(v -> {
            if (syncing) {
                stopSync();
            } else {
                startSync();
            }
            updateSyncButton();
        });

        maritimeStatusText = view.findViewById(R.id.maritime_status_text);
        airStatusText      = view.findViewById(R.id.air_status_text);
    }

    // ─── Tooltips ──────────────────────────────────────────────────────────

    private void showMaritimeTooltip() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        new AlertDialog.Builder(mv.getContext())
                .setTitle("Maritime Data Source")
                .setMessage("aisstream.io provides real-time AIS vessel "
                        + "position data. A free API key "
                        + "is required — register at aisstream.io")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAirTooltip() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        new AlertDialog.Builder(mv.getContext())
                .setTitle("Air Traffic Data Source")
                .setMessage("adsb.fi, airplanes.live, and adsb.lol provide "
                        + "free real-time ADS-B aircraft positions — no API "
                        + "key required.\n\nOpenSky Network is free with "
                        + "anonymous access (400 requests/day) and supports "
                        + "an optional bearer token for higher limits.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ─── API key dialogs ───────────────────────────────────────────────────

    private void showApiKeyDialog() {
        if (syncing) return;
        showKeyDialog("Enter Maritime API Key", apiKey, key -> {
            apiKey = key;
            updateApiKeyDisplay();
        });
    }

    private void showAirApiKeyDialog() {
        if (syncing) return;
        int idx = airDataSourceSpinner != null
                ? airDataSourceSpinner.getSelectedItemPosition() : 0;
        String current = airApiKeys[idx] != null ? airApiKeys[idx] : "";
        showKeyDialog("Enter Air Traffic API Key", current, key -> {
            airApiKeys[idx] = key;
            updateAirApiKeyDisplay();
        });
    }

    private void showKeyDialog(String title, String currentKey,
                                KeyCallback callback) {
        MapView mv = MapView.getMapView();
        if (mv == null) return;

        final EditText input = new EditText(mv.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(currentKey);
        input.setHint("Paste or type API key");
        input.setTextSize(16);
        if (currentKey != null) input.setSelection(currentKey.length());

        LinearLayout container = new LinearLayout(mv.getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = 40;
        container.setPadding(pad, pad, pad, pad);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(mv.getContext())
                .setTitle(title)
                .setView(container)
                .setPositiveButton("OK", (d, which) ->
                        callback.onKey(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .create();
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private interface KeyCallback {
        void onKey(String key);
    }

    private void updateApiKeyDisplay() {
        updateKeyDisplay(apiKeyDisplay, apiKey);
    }

    private void updateAirApiKeyDisplay() {
        int idx = airDataSourceSpinner != null
                ? airDataSourceSpinner.getSelectedItemPosition() : 0;
        String key = (airApiKeys[idx] != null) ? airApiKeys[idx] : "";
        updateKeyDisplay(airApiKeyDisplay, key);
        if (airApiKeyDisplay != null) {
            // OpenSky (index 3) has an optional key; all others don't need one
            airApiKeyDisplay.setHint(idx == 3 ? "Optional" : "Not required");
        }
    }

    private static void updateKeyDisplay(TextView view, String key) {
        if (view == null) return;
        if (key != null && !key.isEmpty()) {
            int len = Math.min(key.length(), 24);
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < len; i++) dots.append('\u2022');
            view.setText(dots.toString());
        } else {
            view.setText("");
        }
    }

    // ─── Preferences ──────────────────────────────────────────────────────

    private void loadPreferences() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;

        SharedPreferences prefs = mv.getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        boolean maritimeEnabled = prefs.getBoolean(PREF_MARITIME_ENABLED, true);
        maritimeEnableCheckbox.setChecked(maritimeEnabled);
        if (maritimeContent != null)
            maritimeContent.setVisibility(maritimeEnabled ? View.VISIBLE : View.GONE);

        apiKey = prefs.getString(PREF_API_KEY, "");
        minLatInput.setText(prefs.getString(PREF_MIN_LAT, ""));
        maxLatInput.setText(prefs.getString(PREF_MAX_LAT, ""));
        minLonInput.setText(prefs.getString(PREF_MIN_LON, ""));
        maxLonInput.setText(prefs.getString(PREF_MAX_LON, ""));

        int freqIndex = prefs.getInt(PREF_FREQUENCY_INDEX, DEFAULT_FREQUENCY_INDEX);
        if (freqIndex >= 0 && freqIndex < FREQUENCY_VALUES.length)
            frequencySpinner.setSelection(freqIndex);

        boolean broadcast = prefs.getBoolean(PREF_BROADCAST_ALL, false);
        broadcastCheckbox.setChecked(broadcast);
        shipMarkerManager.setBroadcastAll(broadcast);

        boolean airEnabled = prefs.getBoolean(PREF_AIR_ENABLED, true);
        airEnableCheckbox.setChecked(airEnabled);
        if (airContent != null)
            airContent.setVisibility(airEnabled ? View.VISIBLE : View.GONE);

        for (int i = 0; i < airApiKeys.length; i++)
            airApiKeys[i] = prefs.getString(PREF_AIR_API_KEY + i, "");

        int airSourceIndex = prefs.getInt(PREF_AIR_SOURCE_INDEX, 0);
        if (airSourceIndex >= 0 && airSourceIndex < AIR_SOURCE_LABELS.length)
            airDataSourceSpinner.setSelection(airSourceIndex);

        boolean airBroadcast = prefs.getBoolean(PREF_AIR_BROADCAST_ALL, false);
        airBroadcastCheckbox.setChecked(airBroadcast);
        airMarkerManager.setBroadcastAll(airBroadcast);
    }

    private void savePreferences() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;

        SharedPreferences.Editor editor = mv.getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MARITIME_ENABLED,
                        maritimeEnableCheckbox.isChecked())
                .putString(PREF_API_KEY, apiKey)
                .putString(PREF_MIN_LAT,
                        minLatInput.getText().toString().trim())
                .putString(PREF_MAX_LAT,
                        maxLatInput.getText().toString().trim())
                .putString(PREF_MIN_LON,
                        minLonInput.getText().toString().trim())
                .putString(PREF_MAX_LON,
                        maxLonInput.getText().toString().trim())
                .putInt(PREF_FREQUENCY_INDEX,
                        frequencySpinner.getSelectedItemPosition())
                .putBoolean(PREF_BROADCAST_ALL, broadcastCheckbox.isChecked())
                .putBoolean(PREF_AIR_ENABLED, airEnableCheckbox.isChecked())
                .putInt(PREF_AIR_SOURCE_INDEX,
                        airDataSourceSpinner.getSelectedItemPosition())
                .putBoolean(PREF_AIR_BROADCAST_ALL,
                        airBroadcastCheckbox.isChecked());
        for (int i = 0; i < airApiKeys.length; i++)
            editor.putString(PREF_AIR_API_KEY + i,
                    airApiKeys[i] != null ? airApiKeys[i] : "");
        editor.apply();
    }

    // ─── Sync control ──────────────────────────────────────────────────────

    private void startSync() {
        boolean doMaritime = maritimeEnableCheckbox.isChecked();
        boolean doAir = airEnableCheckbox.isChecked();

        if (!doMaritime && !doAir) {
            showToast("Enable at least one tracking source");
            return;
        }

        double minLat, maxLat, minLon, maxLon;
        try {
            minLat = Double.parseDouble(minLatInput.getText().toString().trim());
            maxLat = Double.parseDouble(maxLatInput.getText().toString().trim());
            minLon = Double.parseDouble(minLonInput.getText().toString().trim());
            maxLon = Double.parseDouble(maxLonInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            showToast("Please enter valid bounding box coordinates");
            return;
        }

        if (minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180) {
            showToast("Coordinates out of range (lat: -90..90, lon: -180..180)");
            return;
        }
        if (minLat >= maxLat || minLon >= maxLon) {
            showToast("Min values must be less than max values");
            return;
        }

        savePreferences();

        int freqSeconds = FREQUENCY_VALUES[frequencySpinner.getSelectedItemPosition()];
        shipMarkerManager.setUpdateFrequency(freqSeconds);
        airMarkerManager.setUpdateFrequency(freqSeconds);

        lastBoundingBox = new double[][]{{minLat, minLon}, {maxLat, maxLon}};

        syncing = true;
        setInputsEnabled(false);

        if (doMaritime) startMaritimeSync();
        if (doAir) startAirSync();
    }

    private void stopSync() {
        syncing = false;
        stopMaritimeSync();
        stopAirSync();
        setInputsEnabled(true);
    }

    private void startMaritimeSync() {
        if (aisClient != null) return;

        if (apiKey == null || apiKey.isEmpty()) {
            updateStatus("Maritime: API Key required.");
            return;
        }

        lastApiKey = apiKey;
        aisClient = new AisStreamClient(this);
        aisClient.connect(lastApiKey, lastBoundingBox);
        updateStatus("Maritime: Connecting...");
    }

    private void stopMaritimeSync() {
        if (aisClient != null) {
            aisClient.disconnect();
            aisClient = null;
        }
        shipMarkerManager.removeAllMarkers();
        updateStatus(null);
    }

    private void startAirSync() {
        if (adsbClient != null) return;

        int sourceIdx = airDataSourceSpinner.getSelectedItemPosition();
        AdsbSource source = createAirSource(sourceIdx);
        adsbClient = new AdsbStreamClient(source, this);

        String key = airApiKeys[sourceIdx] != null ? airApiKeys[sourceIdx] : "";
        int freqSeconds = FREQUENCY_VALUES[
                frequencySpinner.getSelectedItemPosition()];
        adsbClient.start(key,
                lastBoundingBox[0][0], lastBoundingBox[1][0],
                lastBoundingBox[0][1], lastBoundingBox[1][1],
                freqSeconds);
        updateAirStatus("Air: Connecting...");
    }

    private void stopAirSync() {
        if (adsbClient != null) {
            adsbClient.stop();
            adsbClient = null;
        }
        airMarkerManager.removeAllMarkers();
        updateAirStatus(null);
    }

    private AdsbSource createAirSource(int index) {
        switch (index) {
            case 1: return new AirplanesLiveSource();
            case 2: return new AdsbLolSource();
            case 3: return new OpenSkySource();
            default: return new AdsbFiSource();
        }
    }

    // ─── UI helpers ────────────────────────────────────────────────────────

    private void setInputsEnabled(boolean enabled) {
        if (dataSourceSpinner != null) dataSourceSpinner.setEnabled(enabled);
        if (apiKeyDisplay != null) apiKeyDisplay.setEnabled(enabled);
        if (airDataSourceSpinner != null) airDataSourceSpinner.setEnabled(enabled);
        if (airApiKeyDisplay != null) airApiKeyDisplay.setEnabled(enabled);
        if (minLatInput != null) minLatInput.setEnabled(enabled);
        if (maxLatInput != null) maxLatInput.setEnabled(enabled);
        if (minLonInput != null) minLonInput.setEnabled(enabled);
        if (maxLonInput != null) maxLonInput.setEnabled(enabled);
        if (useMapViewBtn != null) useMapViewBtn.setEnabled(enabled);
    }

    private void updateSyncButton() {
        if (syncBtn == null) return;
        if (syncing) {
            syncBtn.setText(R.string.stop_sync);
            syncBtn.setBackgroundColor(Color.parseColor("#994444"));
        } else {
            syncBtn.setText(R.string.start_sync);
            syncBtn.setBackgroundColor(Color.parseColor("#555555"));
        }
    }

    private void updateStatus(String message) {
        mainHandler.post(() -> {
            if (maritimeStatusText == null) return;
            if (message == null || message.isEmpty()) {
                maritimeStatusText.setVisibility(View.GONE);
            } else {
                maritimeStatusText.setVisibility(View.VISIBLE);
                maritimeStatusText.setText(message);
            }
        });
    }

    private void updateAirStatus(String message) {
        mainHandler.post(() -> {
            if (airStatusText == null) return;
            if (message == null || message.isEmpty()) {
                airStatusText.setVisibility(View.GONE);
            } else {
                airStatusText.setVisibility(View.VISIBLE);
                airStatusText.setText(message);
            }
        });
    }

    private void useCurrentMapView() {
        MapView mv = MapView.getMapView();
        if (mv == null) {
            showToast("Map not available");
            return;
        }
        try {
            double centerLat = mv.getLatitude();
            double centerLon = mv.getLongitude();
            double mapRes = mv.getMapResolution();
            double latRange, lonRange;

            if (mapRes > 0) {
                latRange = (mapRes * mv.getHeight() / 2.0) / 111320.0;
                lonRange = (mapRes * mv.getWidth() / 2.0)
                        / (111320.0 * Math.cos(Math.toRadians(centerLat)));
            } else {
                latRange = 1.0;
                lonRange = 1.0;
            }

            minLatInput.setText(String.format(Locale.US, "%.6f",
                    centerLat - latRange));
            maxLatInput.setText(String.format(Locale.US, "%.6f",
                    centerLat + latRange));
            minLonInput.setText(String.format(Locale.US, "%.6f",
                    centerLon - lonRange));
            maxLonInput.setText(String.format(Locale.US, "%.6f",
                    centerLon + lonRange));
        } catch (Exception e) {
            Log.e(TAG, "Error reading map bounds", e);
            showToast("Could not read map bounds");
        }
    }

    private void showToast(String message) {
        MapView mv = MapView.getMapView();
        if (mv != null)
            mainHandler.post(() ->
                    Toast.makeText(mv.getContext(), message,
                            Toast.LENGTH_SHORT).show());
    }

    // ─── AisStreamClient.Listener ─────────────────────────────────────────

    @Override
    public void onConnected() {
        updateStatus("Maritime: Connected — waiting for data...");
    }

    @Override
    public void onShipName(int mmsi, String name) {
        shipMarkerManager.updateShipName(mmsi, name);
    }

    @Override
    public void onShipPosition(int mmsi, String shipName, double lat,
            double lon, double cog, double sog, int rot, int trueHeading,
            int navStatus, int shipType, double draught,
            String destination, String eta, int imoNumber) {
        shipMarkerManager.updateShip(mmsi, shipName, lat, lon, cog, sog,
                rot, trueHeading, navStatus, shipType, draught,
                destination, eta, imoNumber);
        updateStatus("Maritime: Tracking "
                + shipMarkerManager.getShipCount() + " ships");
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "AIS error: " + error);
        updateStatus("Maritime: Error — " + error);
    }

    @Override
    public void onDisconnected() {
        if (syncing && maritimeEnableCheckbox != null
                && maritimeEnableCheckbox.isChecked()
                && lastApiKey != null && lastBoundingBox != null) {
            updateStatus("Maritime: Reconnecting...");
            Log.d(TAG, "AIS auto-reconnecting...");
            mainHandler.postDelayed(() -> {
                if (!syncing) return;
                aisClient = new AisStreamClient(this);
                aisClient.connect(lastApiKey, lastBoundingBox);
            }, 3000);
        }
    }

    // ─── AdsbStreamClient.Listener ────────────────────────────────────────

    @Override
    public void onConnected(String sourceName) {
        updateAirStatus("Air: Connected to " + sourceName);
    }

    @Override
    public void onAircraft(Aircraft aircraft) {
        airMarkerManager.updateAircraft(aircraft);
    }

    @Override
    public void onPollComplete(String sourceName, int count) {
        updateAirStatus("Air: Tracking "
                + airMarkerManager.getAircraftCount() + " aircraft");
    }

    @Override
    public void onError(String sourceName, String error) {
        Log.e(TAG, "ADS-B error (" + sourceName + "): " + error);
        updateAirStatus("Air: Error — " + error);
    }

    @Override
    public void onDisconnected(String sourceName) {
        // AdsbStreamClient stops cleanly; reconnect is not needed
        // since the next poll cycle handles it automatically when
        // the source recovers. For a deliberate stop, syncing=false.
    }
}
