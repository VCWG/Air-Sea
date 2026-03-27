/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.net.Uri;
import android.graphics.Color;
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
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoPoint;
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

    // Global tool preferences (stored in ATAK's default shared prefs; read by AirSeaPreferenceFragment)
    static final String PREF_AFFILIATION      = "airsea_affiliation";
    static final String PREF_RTL_GAIN         = "airsea_rtl_gain";
    static final String PREF_RTL_RANGE_KM     = "airsea_rtl_range_km";
    static final String PREF_AIR_STALE_SEC    = "airsea_air_stale_sec";
    static final String PREF_SHIP_STALE_SEC   = "airsea_ship_stale_sec";

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
    private static final String PREF_AIR_ENABLED           = "air_enabled";
    private static final String PREF_AIR_API_KEY            = "air_api_key_";  // + index suffix (sources 0-3)
    private static final String PREF_AIR_SOURCE_INDEX       = "air_source_index";
    private static final String PREF_AIR_BROADCAST_ALL      = "air_broadcast_all";
    private static final String PREF_OPENSKY_CLIENT_ID      = "opensky_client_id";
    private static final String PREF_OPENSKY_CLIENT_SECRET  = "opensky_client_secret";
    private static final String PREF_RTL_TCP_PORT           = "rtl_tcp_port";

    private static final int[] FREQUENCY_VALUES = {1, 5, 10, 30, 60};
    private static final String[] FREQUENCY_LABELS = {
            "1 second", "5 seconds", "10 seconds", "30 seconds", "60 seconds"
    };
    private static final int DEFAULT_FREQUENCY_INDEX = 3; // 30 seconds

    // Index 0 = RTL-SDR; 1-5 = network sources (air spinner only)
    private static final String[] MARITIME_SOURCE_LABELS =
            {"aisstream.io"};
    private static final String[] AIR_SOURCE_LABELS =
            {"USB: RTL-SDR", "ADS-B Exchange", "adsb.fi", "airplanes.live", "adsb.lol", "OpenSky"};

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
    private RtlSdrAdsbClient rtlAdsbClient;
    private final AirMarkerManager airMarkerManager;
    private final String[] airApiKeys = new String[]{"", "", "", ""};  // network sources 1-4
    private String openSkyClientId = "";
    private String openSkyClientSecret = "";

    // ICAO aircraft database (military flag, model, owner/operator, short type)
    private IcaoDatabase icaoDatabase;

    // Maritime RTL-SDR client
    private RtlSdrAisClient rtlAisClient;
    /** Pending AIS auto-reconnect callback — must be cancelled on stop. */
    private Runnable aisReconnectRunnable;

    private boolean syncing = false;

    // Global preferences (applied from Specific Tool Preferences)
    private int    rtlGainTenthsDb = RtlTcpClient.DEFAULT_GAIN_TENTHS_DB;
    private double rtlRangeM       = 150_000.0;

    private final SharedPreferences.OnSharedPreferenceChangeListener globalPrefListener =
            (sharedPreferences, key) -> {
                if (PREF_AFFILIATION.equals(key) || PREF_RTL_GAIN.equals(key)
                        || PREF_RTL_RANGE_KM.equals(key)
                        || PREF_AIR_STALE_SEC.equals(key)
                        || PREF_SHIP_STALE_SEC.equals(key)) {
                    applyGlobalPreferences();
                }
            };

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
    private View airApiKeySection;
    private TextView airApiKeyDisplay;
    private View openSkySection;
    private TextView openSkyClientIdDisplay;
    private TextView openSkyClientSecretDisplay;
    private CheckBox airBroadcastCheckbox;

    // Maritime views (RTL-SDR hides the API key section)
    private View maritimeApiKeySection;

    // RTL-SDR port section (shown when either spinner is on RTL-SDR)
    private View     rtlTcpSection;
    private EditText rtlTcpPortInput;
    private static final String RTL_TCP_HOST = "127.0.0.1";

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
        // Initialise ICAO database (requires MapView context for storage directory)
        MapView mv = MapView.getMapView();
        if (mv != null) {
            java.io.File dbDir = mv.getContext().getDir("airsea", android.content.Context.MODE_PRIVATE);
            icaoDatabase = new IcaoDatabase(dbDir);
            airMarkerManager.setIcaoDatabase(icaoDatabase);
            if (!icaoDatabase.isFilePresent()) {
                Log.i(TAG, "ICAO database not present — initiating first-time download");
                icaoDatabase.downloadAndUpdate((success, msg) ->
                        Log.i(TAG, "ICAO DB first-time download: "
                                + (success ? "OK (" + msg + ")" : "failed: " + msg)));
            } else {
                icaoDatabase.loadAsync();
            }
            applyGlobalPreferences();
            PreferenceManager.getDefaultSharedPreferences(mv.getContext())
                    .registerOnSharedPreferenceChangeListener(globalPrefListener);
        }

        if (uiService != null) uiService.addToolbarItem(toolbarItem);

        // Register in Specific Tool Preferences
        ToolsPreferenceFragment.register(new ToolsPreferenceFragment.ToolPreference(
                "Air+Sea",
                "Default track affiliation, RTL-SDR gain, range, and ICAO database",
                "com.atakmap.android.airseatool",
                pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                new AirSeaPreferenceFragment(pluginContext, icaoDatabase)));
    }

    @Override
    public void onStop() {
        stopSync();
        if (uiService != null) uiService.removeToolbarItem(toolbarItem);

        ToolsPreferenceFragment.unregister("com.atakmap.android.airseatool");

        MapView mv = MapView.getMapView();
        if (mv != null) {
            PreferenceManager.getDefaultSharedPreferences(mv.getContext())
                    .unregisterOnSharedPreferenceChangeListener(globalPrefListener);
        }

        airMarkerManager.setIcaoDatabase(null);
        icaoDatabase = null;

        // Invalidate pane so it is recreated fresh on next onStart/showPane
        pane = null;
        paneView = null;
        syncBtn = null;
    }

    private void showPane() {
        if (pane == null) {
            paneView = LayoutInflater.from(pluginContext)
                    .inflate(R.layout.main_layout, null);
            setupUI(paneView);
            loadPreferences();
            updateSyncButton();
            updateApiKeyDisplay();
            updateMaritimeApiKeySection();
            updateAirCredentialFields();

            pane = new PaneBuilder(paneView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Right)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .build();
        }

        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
        // Post to ensure button reflects syncing state AFTER ATAK finishes showing the pane
        mainHandler.post(this::updateSyncButton);
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
                R.layout.spinner_item_black, MARITIME_SOURCE_LABELS);
        dsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        dataSourceSpinner.setAdapter(dsAdapter);
        dataSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                updateMaritimeApiKeySection();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        view.findViewById(R.id.data_source_tooltip)
                .setOnClickListener(v -> showMaritimeTooltip());

        maritimeApiKeySection = view.findViewById(R.id.maritime_apikey_section);
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
                updateAirCredentialFields();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        view.findViewById(R.id.air_data_source_tooltip)
                .setOnClickListener(v -> showAirTooltip());

        airApiKeySection = view.findViewById(R.id.air_apikey_section);
        airApiKeyDisplay = view.findViewById(R.id.air_api_key_display);
        airApiKeyDisplay.setOnClickListener(v -> showAirApiKeyDialog());

        openSkySection = view.findViewById(R.id.opensky_credentials_section);
        openSkyClientIdDisplay = view.findViewById(R.id.opensky_client_id_display);
        openSkyClientIdDisplay.setOnClickListener(v -> showOpenSkyClientIdDialog());
        openSkyClientSecretDisplay = view.findViewById(R.id.opensky_client_secret_display);
        openSkyClientSecretDisplay.setOnClickListener(v -> showOpenSkyClientSecretDialog());

        airBroadcastCheckbox = view.findViewById(R.id.air_broadcast_all_checkbox);
        airBroadcastCheckbox.setOnClickListener(v ->
                airMarkerManager.setBroadcastAll(airBroadcastCheckbox.isChecked()));

        // RTL-SDR port
        rtlTcpSection   = view.findViewById(R.id.rtl_tcp_section);
        rtlTcpPortInput = view.findViewById(R.id.rtl_tcp_port_input);
        view.findViewById(R.id.rtl_tcp_auto_btn).setOnClickListener(v -> launchRtlSdrDriver());
        view.findViewById(R.id.rtl_port_tooltip)
                .setOnClickListener(v -> showRtlPortTooltip());

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
                .setMessage("aisstream.io provides real-time AIS vessel position "
                        + "data over the internet. A free API key is required "
                        + "— register at aisstream.io")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showAirTooltip() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        new AlertDialog.Builder(mv.getContext())
                .setTitle("Air Traffic Data Source")
                .setMessage("USB: RTL-SDR requires a connected receiver and "
                        + "antenna. User must install the \"RTL-SDR Driver\" app "
                        + "(free, Play Store) from Signalware. Disable battery "
                        + "optimization for the driver app.\n\n"
                        + "ADS-B Exchange data is provided through RapidAPI. "
                        + "A valid API key is required.\n\n"
                        + "adsb.fi, airplanes.live, and adsb.lol provide "
                        + "free real-time ADS-B aircraft positions — no API "
                        + "key required.\n\nOpenSky provides limited requests "
                        + "without an account. For higher limits, register on "
                        + "OpenSky to obtain API keys.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showRtlPortTooltip() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        new AlertDialog.Builder(mv.getContext())
                .setTitle("RTL-SDR Driver Port")
                .setMessage("Enter the port as running in the RTL-SDR driver app, "
                        + "or click start to launch the RTL-SDR service automatically. "
                        + "The RTL-SDR driver must remain running with the configured "
                        + "port for continuous function.")
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
        // spinner 1-4 → airApiKeys 0-3
        int keyIdx = idx - 1;
        if (keyIdx < 0 || keyIdx >= airApiKeys.length) return;
        String current = airApiKeys[keyIdx] != null ? airApiKeys[keyIdx] : "";
        showKeyDialog("Enter Air Traffic API Key", current, key -> {
            airApiKeys[keyIdx] = key;
            updateAirCredentialFields();
        });
    }

    private void showOpenSkyClientIdDialog() {
        if (syncing) return;
        showKeyDialog("Enter OpenSky Client ID", openSkyClientId, id -> {
            openSkyClientId = id;
            updateAirCredentialFields();
        });
    }

    private void showOpenSkyClientSecretDialog() {
        if (syncing) return;
        showKeyDialog("Enter OpenSky Client Secret", openSkyClientSecret, secret -> {
            openSkyClientSecret = secret;
            updateAirCredentialFields();
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

    /** Show/hide the maritime API key section based on spinner selection. */
    private void updateMaritimeApiKeySection() {
        if (maritimeApiKeySection != null)
            maritimeApiKeySection.setVisibility(View.VISIBLE);
        updateRtlTcpSection();
    }

    private void updateAirCredentialFields() {
        int idx = airDataSourceSpinner != null
                ? airDataSourceSpinner.getSelectedItemPosition() : 0;
        // idx 0 = RTL-SDR, idx 1 = ADS-B Exchange, idx 2-4 = free sources, idx 5 = OpenSky
        boolean isRtlSdr  = (idx == 0);
        boolean isOpenSky = (idx == 5);

        if (airApiKeySection != null)
            airApiKeySection.setVisibility(
                    (isRtlSdr || isOpenSky) ? View.GONE : View.VISIBLE);
        if (openSkySection != null)
            openSkySection.setVisibility(isOpenSky ? View.VISIBLE : View.GONE);

        if (!isRtlSdr && !isOpenSky && idx >= 1 && idx <= 4) {
            int keyIdx = idx - 1;  // airApiKeys[0..3] for spinner positions 1..4
            String key = (airApiKeys[keyIdx] != null) ? airApiKeys[keyIdx] : "";
            updateKeyDisplay(airApiKeyDisplay, key);
            // Show "Required" hint for ADS-B Exchange (idx 1) when no key entered;
            // "Not required" for optional-key sources (idx 2-4)
            if (airApiKeyDisplay != null) {
                airApiKeyDisplay.setHint(
                        (idx == 1 && key.isEmpty()) ? "Required"
                        : (idx != 1 && key.isEmpty()) ? "Not required" : "");
            }
        } else if (isOpenSky) {
            updateKeyDisplay(openSkyClientIdDisplay, openSkyClientId);
            updateKeyDisplay(openSkyClientSecretDisplay, openSkyClientSecret);
        }
        updateRtlTcpSection();
    }

    private void updateRtlTcpSection() {
        if (rtlTcpSection == null) return;
        boolean airRtl = airDataSourceSpinner != null
                && airDataSourceSpinner.getSelectedItemPosition() == 0;
        // Maritime RTL-SDR is currently disabled (only aisstream.io in the
        // labels array), but check for it so the section reappears if it is
        // re-added in the future.
        boolean maritimeRtl = dataSourceSpinner != null
                && MARITIME_SOURCE_LABELS.length > 1
                && dataSourceSpinner.getSelectedItemPosition() == 0
                && "USB: RTL-SDR".equals(MARITIME_SOURCE_LABELS[0]);
        rtlTcpSection.setVisibility((airRtl || maritimeRtl) ? View.VISIBLE : View.GONE);
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

    /** Read and apply the three global tool preferences (affiliation, gain, range). */
    private void applyGlobalPreferences() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        SharedPreferences gp = PreferenceManager.getDefaultSharedPreferences(mv.getContext());

        // Affiliation
        String affilStr = gp.getString(PREF_AFFILIATION, "n");
        char affilChar = (affilStr != null && !affilStr.isEmpty()) ? affilStr.charAt(0) : 'n';
        shipMarkerManager.setAffiliation(affilChar);
        airMarkerManager.setAffiliation(affilChar);

        // RTL-SDR gain
        String gainStr = gp.getString(PREF_RTL_GAIN,
                String.valueOf(RtlTcpClient.DEFAULT_GAIN_TENTHS_DB));
        try {
            rtlGainTenthsDb = Integer.parseInt(gainStr);
        } catch (NumberFormatException e) {
            rtlGainTenthsDb = RtlTcpClient.DEFAULT_GAIN_TENTHS_DB;
        }

        // RTL-SDR range
        String rangeStr = gp.getString(PREF_RTL_RANGE_KM, "150");
        try {
            rtlRangeM = Integer.parseInt(rangeStr) * 1000.0;
        } catch (NumberFormatException e) {
            rtlRangeM = 150_000.0;
        }

        // Air contact stale timeout
        String airStaleStr = gp.getString(PREF_AIR_STALE_SEC,
                String.valueOf(AirMarkerManager.DEFAULT_staleOffsetMs / 1000));
        try {
            airMarkerManager.setStaleOffsetSeconds(Integer.parseInt(airStaleStr));
        } catch (NumberFormatException e) {
            airMarkerManager.setStaleOffsetSeconds(
                    (int) (AirMarkerManager.DEFAULT_staleOffsetMs / 1000));
        }

        // Ship contact stale timeout
        String shipStaleStr = gp.getString(PREF_SHIP_STALE_SEC,
                String.valueOf(ShipMarkerManager.DEFAULT_staleOffsetMs / 1000));
        try {
            shipMarkerManager.setStaleOffsetSeconds(Integer.parseInt(shipStaleStr));
        } catch (NumberFormatException e) {
            shipMarkerManager.setStaleOffsetSeconds(
                    (int) (ShipMarkerManager.DEFAULT_staleOffsetMs / 1000));
        }
    }

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
        openSkyClientId     = prefs.getString(PREF_OPENSKY_CLIENT_ID, "");
        openSkyClientSecret = prefs.getString(PREF_OPENSKY_CLIENT_SECRET, "");

        int airSourceIndex = prefs.getInt(PREF_AIR_SOURCE_INDEX, 0);
        if (airSourceIndex >= 0 && airSourceIndex < AIR_SOURCE_LABELS.length)
            airDataSourceSpinner.setSelection(airSourceIndex);

        boolean airBroadcast = prefs.getBoolean(PREF_AIR_BROADCAST_ALL, false);
        airBroadcastCheckbox.setChecked(airBroadcast);
        airMarkerManager.setBroadcastAll(airBroadcast);

        int savedPort = prefs.getInt(PREF_RTL_TCP_PORT, RtlTcpClient.DEFAULT_PORT);
        if (rtlTcpPortInput != null)
            rtlTcpPortInput.setText(String.valueOf(savedPort));
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
        editor.putString(PREF_OPENSKY_CLIENT_ID, openSkyClientId);
        editor.putString(PREF_OPENSKY_CLIENT_SECRET, openSkyClientSecret);
        editor.putInt(PREF_RTL_TCP_PORT, getRtlTcpPort());
        editor.apply();
    }

    private int getRtlTcpPort() {
        if (rtlTcpPortInput == null) return RtlTcpClient.DEFAULT_PORT;
        try {
            int p = Integer.parseInt(rtlTcpPortInput.getText().toString().trim());
            return (p > 0 && p <= 65535) ? p : RtlTcpClient.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return RtlTcpClient.DEFAULT_PORT;
        }
    }

    /**
     * AUTO button handler — launches the RTL-SDR Driver via its designed
     * external-app API ({@code iqsrc://} URI scheme).
     *
     * The Driver's architecture is caller-driven: WE specify the port in the
     * URI, so there is no need to scan or discover it afterward.  The flow:
     *
     *   1. Read the port from the UI field (default 1234).
     *   2. Send an {@code iqsrc://} VIEW intent to {@code DeviceOpenActivity}
     *      with {@code -p <port>}.  The Driver starts streaming on that port.
     *   3. We already know the port — just save it.
     */
    private void launchRtlSdrDriver() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;

        int port = getRtlTcpPort();   // from field, or DEFAULT_PORT (1234)
        // Ports below 1024 are privileged on Android — the Driver can't bind them.
        if (port < 1024) port = RtlTcpClient.DEFAULT_PORT;

        // Build the iqsrc:// URI.  The Driver parses -a, -p, -s, -f from it.
        // Address MUST be 0.0.0.0 (all interfaces); 127.0.0.1 causes bind failure.
        String uri = "iqsrc://-a 0.0.0.0 -p " + port + " -s 1024000 -f 100000000";

        try {
            Intent iqsrc = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            iqsrc.setClassName("marto.rtl_tcp_andro",
                    "com.sdrtouch.rtlsdr.DeviceOpenActivity");
            iqsrc.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mv.getContext().startActivity(iqsrc);
            Log.d(TAG, "RTL-SDR Driver launched via iqsrc:// on port " + port);

            // Ensure the port field reflects what we told the Driver to use
            if (rtlTcpPortInput != null) rtlTcpPortInput.setText(String.valueOf(port));
            savePreferences();
            showToast("RTL-SDR Driver starting on port " + port);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "RTL-SDR Driver not installed: " + e.getMessage());
            showToast("RTL-SDR Driver app not installed");
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException launching Driver: " + e.getMessage());
            showToast("Cannot open RTL-SDR Driver: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Exception launching Driver (" + e.getClass().getSimpleName()
                    + "): " + e.getMessage());
            showToast("Cannot open RTL-SDR Driver: " + e.getClass().getSimpleName());
        }
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

        // If the ICAO database has never been downloaded, kick off a silent download now
        if (icaoDatabase != null && !icaoDatabase.isFilePresent() && !icaoDatabase.isDownloading()) {
            Log.i(TAG, "ICAO database absent at sync start — initiating background download");
            icaoDatabase.downloadAndUpdate((success, msg) ->
                    Log.i(TAG, "ICAO DB sync-triggered download: "
                            + (success ? "OK (" + msg + ")" : "failed: " + msg)));
        }

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
        updateSyncButton();
    }

    private void startMaritimeSync() {
        // Defensive: stop any existing client to prevent orphan connections
        if (aisClient != null) { aisClient.disconnect(); aisClient = null; }
        if (rtlAisClient != null) { rtlAisClient.disconnect(); rtlAisClient = null; }

        // aisstream.io
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
        if (aisReconnectRunnable != null) {
            mainHandler.removeCallbacks(aisReconnectRunnable);
            aisReconnectRunnable = null;
        }
        if (aisClient != null) {
            aisClient.disconnect();
            aisClient = null;
        }
        if (rtlAisClient != null) {
            rtlAisClient.disconnect();
            rtlAisClient = null;
        }
        shipMarkerManager.removeAllMarkers();
        updateStatus(null);
    }

    private void startAirSync() {
        // Defensive: stop any existing client to prevent orphan threads
        if (adsbClient != null) { adsbClient.stop(); adsbClient = null; }
        if (rtlAdsbClient != null) { rtlAdsbClient.stop(); rtlAdsbClient = null; }

        int sourceIdx = airDataSourceSpinner.getSelectedItemPosition();

        if (sourceIdx == 0) {
            // RTL-SDR: bypass update frequency throttle — update on every CPR fix
            airMarkerManager.setUpdateFrequency(0);
            rtlAdsbClient = new RtlSdrAdsbClient(this, RTL_TCP_HOST, getRtlTcpPort(),
                    rtlGainTenthsDb);
            rtlAdsbClient.start();
            updateAirStatus("Air: Connecting to RTL-SDR Driver...");
            return;
        }

        // Network source: spinner 1-5 → ADS-B Exchange, adsb.fi, airplanes.live, adsb.lol, OpenSky
        AdsbSource source = createAirSource(sourceIdx);
        adsbClient = new AdsbStreamClient(source, this);

        String cred1, cred2;
        if (sourceIdx == 5) {
            // OpenSky: OAuth2 client credentials
            cred1 = openSkyClientId;
            cred2 = openSkyClientSecret;
        } else {
            // airApiKeys[0..3] for spinner positions 1..4
            int keyIdx = sourceIdx - 1;
            cred1 = (keyIdx >= 0 && keyIdx < airApiKeys.length && airApiKeys[keyIdx] != null)
                    ? airApiKeys[keyIdx] : "";
            cred2 = "";
        }
        int freqSeconds = FREQUENCY_VALUES[frequencySpinner.getSelectedItemPosition()];
        adsbClient.start(cred1, cred2,
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
        if (rtlAdsbClient != null) {
            rtlAdsbClient.stop();
            rtlAdsbClient = null;
            // Restore configured update frequency
            int freqSecs = FREQUENCY_VALUES[frequencySpinner.getSelectedItemPosition()];
            airMarkerManager.setUpdateFrequency(freqSecs);
        }
        airMarkerManager.removeAllMarkers();
        updateAirStatus(null);
    }

    private AdsbSource createAirSource(int spinnerIndex) {
        // spinnerIndex: 1=ADS-B Exchange, 2=adsb.fi, 3=airplanes.live, 4=adsb.lol, 5=OpenSky
        switch (spinnerIndex) {
            case 1: return new AdsbExchangeSource();
            case 3: return new AirplanesLiveSource();
            case 4: return new AdsbLolSource();
            case 5: return new OpenSkySource();
            default: return new AdsbFiSource();
        }
    }

    // ─── UI helpers ────────────────────────────────────────────────────────

    private void setInputsEnabled(boolean enabled) {
        if (dataSourceSpinner != null) dataSourceSpinner.setEnabled(enabled);
        if (apiKeyDisplay != null) apiKeyDisplay.setEnabled(enabled);
        if (airDataSourceSpinner != null) airDataSourceSpinner.setEnabled(enabled);
        if (airApiKeyDisplay != null) airApiKeyDisplay.setEnabled(enabled);
        if (openSkyClientIdDisplay != null) openSkyClientIdDisplay.setEnabled(enabled);
        if (openSkyClientSecretDisplay != null) openSkyClientSecretDisplay.setEnabled(enabled);
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
        // Called from the AIS WebSocket thread — marshal everything to the UI thread
        // so that aisClient and syncing are only touched from one thread.
        mainHandler.post(() -> {
            if (!syncing
                    || maritimeEnableCheckbox == null
                    || !maritimeEnableCheckbox.isChecked()
                    || lastApiKey == null || lastBoundingBox == null) return;
            updateStatus("Maritime: Reconnecting...");
            Log.d(TAG, "AIS auto-reconnecting...");
            aisClient = null;
            aisReconnectRunnable = () -> {
                aisReconnectRunnable = null;
                if (!syncing) return;
                aisClient = new AisStreamClient(this);
                aisClient.connect(lastApiKey, lastBoundingBox);
            };
            mainHandler.postDelayed(aisReconnectRunnable, 3000);
        });
    }

    // ─── AdsbStreamClient.Listener ────────────────────────────────────────

    @Override
    public void onConnected(String sourceName) {
        updateAirStatus("Air: Connected to " + sourceName);
    }

    @Override
    public void onAircraft(Aircraft aircraft) {
        // Range cutoff: only for RTL-SDR, and only when we have a valid GPS fix.
        // API sources handle their own geographic filtering via bounding box.
        if (rtlAdsbClient != null) {
            MapView mv = MapView.getMapView();
            Marker self = (mv != null) ? mv.getSelfMarker() : null;
            if (self != null) {
                GeoPoint selfPt = self.getPoint();
                if (selfPt.isValid() && selfPt.getLatitude() != 0.0
                        && selfPt.getLongitude() != 0.0) {
                    GeoPoint acPt = new GeoPoint(aircraft.lat, aircraft.lon);
                    if (selfPt.distanceTo(acPt) > rtlRangeM) return;
                }
            }
        }
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
