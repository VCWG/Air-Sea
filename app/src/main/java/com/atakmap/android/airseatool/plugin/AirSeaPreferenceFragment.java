/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.widget.Toast;

import com.atakmap.android.preference.PluginPreferenceFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AirSeaPreferenceFragment extends PluginPreferenceFragment {

    private final IcaoDatabase icaoDatabase;
    // Held as fields so lambdas defined before the other pref is created can still reference them
    private Preference icaoUpdatePref;
    private Preference icaoDeletePref;

    public AirSeaPreferenceFragment(Context pluginContext, IcaoDatabase icaoDatabase) {
        super(pluginContext, R.xml.airsea_preferences);
        this.icaoDatabase = icaoDatabase;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // super initialises the PreferenceManager; the XML screen it creates
        // is discarded and replaced with a programmatic one below so that
        // ListPreference / EditTextPreference dialogs use the Activity context
        // (required for dialogs to be shown from an ATAK plugin).
        super.onCreate(savedInstanceState);

        PreferenceScreen screen = getPreferenceManager()
                .createPreferenceScreen(getActivity());

        // Load string arrays from plugin resources
        CharSequence[] affilLabels = pluginContext.getResources()
                .getTextArray(R.array.affiliation_labels);
        CharSequence[] affilValues = pluginContext.getResources()
                .getTextArray(R.array.affiliation_values);
        CharSequence[] airGainLabels = pluginContext.getResources()
                .getTextArray(R.array.rtl_gain_air_labels);
        CharSequence[] seaGainLabels = pluginContext.getResources()
                .getTextArray(R.array.rtl_gain_sea_labels);
        CharSequence[] gainValues = pluginContext.getResources()
                .getTextArray(R.array.rtl_gain_values);

        // ── Default Track Affiliation ────────────────────────────────────
        ListPreference affilPref = new ListPreference(getActivity());
        affilPref.setKey(AirSeaTool.PREF_AFFILIATION);
        affilPref.setTitle("Default Track Affiliation");
        affilPref.setEntries(affilLabels);
        affilPref.setEntryValues(affilValues);
        affilPref.setDefaultValue("n");
        affilPref.setOnPreferenceChangeListener((pref, newVal) -> {
            setListSummary((ListPreference) pref,
                    (String) newVal, affilLabels, affilValues);
            return true;
        });
        screen.addPreference(affilPref);

        // ── RTL-SDR Gain (Air) ────────────────────────────────────────────
        ListPreference airGainPref = new ListPreference(getActivity());
        airGainPref.setKey(AirSeaTool.PREF_RTL_GAIN_AIR);
        airGainPref.setTitle("RTL-SDR Gain (Air)");
        airGainPref.setEntries(airGainLabels);
        airGainPref.setEntryValues(gainValues);
        airGainPref.setDefaultValue(
                String.valueOf(RtlTcpClient.DEFAULT_GAIN_TENTHS_DB));
        airGainPref.setOnPreferenceChangeListener((pref, newVal) -> {
            setListSummary((ListPreference) pref,
                    (String) newVal, airGainLabels, gainValues);
            return true;
        });
        screen.addPreference(airGainPref);

        // ── RTL-SDR Gain (Sea) ────────────────────────────────────────────
        ListPreference seaGainPref = new ListPreference(getActivity());
        seaGainPref.setKey(AirSeaTool.PREF_RTL_GAIN_SEA);
        seaGainPref.setTitle("RTL-SDR Gain (Sea)");
        seaGainPref.setEntries(seaGainLabels);
        seaGainPref.setEntryValues(gainValues);
        seaGainPref.setDefaultValue(
                String.valueOf(AirSeaTool.DEFAULT_RTL_SEA_GAIN_TENTHS_DB));
        seaGainPref.setOnPreferenceChangeListener((pref, newVal) -> {
            setListSummary((ListPreference) pref,
                    (String) newVal, seaGainLabels, gainValues);
            return true;
        });
        screen.addPreference(seaGainPref);

        // ── RTL-SDR Range Limit ──────────────────────────────────────────
        EditTextPreference rangePref = new EditTextPreference(getActivity());
        rangePref.setKey(AirSeaTool.PREF_RTL_RANGE_KM);
        rangePref.setTitle("RTL-SDR Range Limit");
        rangePref.setDialogTitle("RTL-SDR Range Limit");
        rangePref.setDialogMessage("Maximum range for RTL-SDR contacts (km)");
        rangePref.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER);
        rangePref.setDefaultValue("150");
        rangePref.setOnPreferenceChangeListener((pref, newVal) -> {
            setRangeSummary((EditTextPreference) pref, (String) newVal);
            return true;
        });
        screen.addPreference(rangePref);

        // ── RTL-SDR Crystal PPM Correction ──────────────────────────────
        EditTextPreference ppmPref = new EditTextPreference(getActivity());
        ppmPref.setKey(AirSeaTool.PREF_RTL_PPM);
        ppmPref.setTitle("RTL-SDR Crystal PPM Correction");
        ppmPref.setDialogTitle("RTL-SDR Crystal PPM Correction");
        ppmPref.setDialogMessage(
                "Frequency error of your dongle's crystal (e.g. +72 or -45).\n"
                + "Find it using rtl_test, SDR#, or SDR Touch — each shows the PPM offset.\n"
                + "Positive = crystal runs fast; negative = crystal runs slow.");
        ppmPref.getEditText().setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        ppmPref.setDefaultValue("0");
        ppmPref.setOnPreferenceChangeListener((pref, newVal) -> {
            setPpmSummary((EditTextPreference) pref, (String) newVal);
            return true;
        });
        screen.addPreference(ppmPref);

        // ── Air Contact Stale Timeout ────────────────────────────────────
        EditTextPreference airStalePref = new EditTextPreference(getActivity());
        airStalePref.setKey(AirSeaTool.PREF_AIR_STALE_SEC);
        airStalePref.setTitle("Air Contact Stale Timeout");
        airStalePref.setDialogTitle("Air Contact Stale Timeout");
        airStalePref.setDialogMessage("Seconds before an aircraft contact is removed");
        airStalePref.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER);
        airStalePref.setDefaultValue(
                String.valueOf(AirMarkerManager.DEFAULT_staleOffsetMs / 1000));
        airStalePref.setOnPreferenceChangeListener((pref, newVal) -> {
            setStaleSummary((EditTextPreference) pref, (String) newVal, "s");
            return true;
        });
        screen.addPreference(airStalePref);

        // ── Ship Contact Stale Timeout ───────────────────────────────────
        EditTextPreference shipStalePref = new EditTextPreference(getActivity());
        shipStalePref.setKey(AirSeaTool.PREF_SHIP_STALE_SEC);
        shipStalePref.setTitle("Ship Contact Stale Timeout");
        shipStalePref.setDialogTitle("Ship Contact Stale Timeout");
        shipStalePref.setDialogMessage("Seconds before a ship contact is removed");
        shipStalePref.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER);
        shipStalePref.setDefaultValue(
                String.valueOf(ShipMarkerManager.DEFAULT_staleOffsetMs / 1000));
        shipStalePref.setOnPreferenceChangeListener((pref, newVal) -> {
            setStaleSummary((EditTextPreference) pref, (String) newVal, "s");
            return true;
        });
        screen.addPreference(shipStalePref);

        // ── ICAO Database / Military Affiliation ─────────────────────────
        icaoUpdatePref = new Preference(getActivity());
        icaoUpdatePref.setTitle("ICAO Database / Military Affiliation");
        updateIcaoDbSummary(icaoUpdatePref, icaoDatabase);
        icaoUpdatePref.setOnPreferenceClickListener(pref -> {
            if (icaoDatabase == null) return true;
            if (icaoDatabase.isDownloading()) {
                Toast.makeText(getActivity(), "Database update already in progress",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            // Show progress dialog
            ProgressDialog pd = new ProgressDialog(getActivity());
            pd.setTitle("ICAO Database Update");
            pd.setMessage("Connecting\u2026");
            pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            pd.setMax(100);
            pd.setProgress(0);
            pd.setIndeterminate(false);
            pd.setCancelable(false);
            pd.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel",
                    (dialog, which) -> icaoDatabase.cancelDownload());
            pd.show();

            icaoDatabase.downloadAndUpdate(
                (success, msg) -> {
                    android.app.Activity act = getActivity();
                    if (act == null || act.isFinishing()) return;
                    act.runOnUiThread(() -> {
                        pd.dismiss();
                        if (getActivity() == null) return;
                        updateIcaoDbSummary(icaoUpdatePref, icaoDatabase);
                        updateIcaoDeleteSummary(icaoDeletePref, icaoDatabase);
                        if ("Download cancelled".equals(msg)) return;
                        Toast.makeText(act,
                                success ? "ICAO database updated" : "Update failed: " + msg,
                                Toast.LENGTH_LONG).show();
                    });
                },
                (stage, percent) -> {
                    android.app.Activity act = getActivity();
                    if (act == null || act.isFinishing()) return;
                    act.runOnUiThread(() -> {
                        if (percent >= 0) {
                            pd.setIndeterminate(false);
                            pd.setProgress(percent);
                        } else {
                            pd.setIndeterminate(true);
                        }
                        pd.setMessage(stage);
                    });
                }
            );
            return true;
        });
        screen.addPreference(icaoUpdatePref);

        // ── Delete ICAO Database ──────────────────────────────────────────
        icaoDeletePref = new Preference(getActivity());
        icaoDeletePref.setTitle("Delete ICAO Database");
        updateIcaoDeleteSummary(icaoDeletePref, icaoDatabase);
        icaoDeletePref.setOnPreferenceClickListener(pref -> {
            if (icaoDatabase == null || !icaoDatabase.isFilePresent()) return true;
            new AlertDialog.Builder(getActivity())
                    .setTitle("Delete ICAO Database")
                    .setMessage("Remove the local ICAO database file? Military type "
                            + "identification and operator information will be unavailable "
                            + "until the database is re-downloaded.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        icaoDatabase.deleteDatabase();
                        updateIcaoDbSummary(icaoUpdatePref, icaoDatabase);
                        updateIcaoDeleteSummary(icaoDeletePref, icaoDatabase);
                        Toast.makeText(getActivity(), "ICAO database deleted",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        screen.addPreference(icaoDeletePref);

        // Commit screen — after this, getValue()/getText() return saved or default values
        setPreferenceScreen(screen);

        // Set initial summaries now that preferences are bound to SharedPreferences
        setListSummary(affilPref, affilPref.getValue(), affilLabels, affilValues);
        setListSummary(airGainPref, airGainPref.getValue(), airGainLabels, gainValues);
        setListSummary(seaGainPref, seaGainPref.getValue(), seaGainLabels, gainValues);
        setRangeSummary(rangePref, rangePref.getText());
        setPpmSummary(ppmPref, ppmPref.getText());
        setStaleSummary(airStalePref, airStalePref.getText(), "s");
        setStaleSummary(shipStalePref, shipStalePref.getText(), "s");
        updateIcaoDeleteSummary(icaoDeletePref, icaoDatabase);
    }

    private static void updateIcaoDbSummary(Preference pref, IcaoDatabase db) {
        if (pref == null) return;
        if (db == null || !db.isFilePresent()) {
            pref.setSummary("Not downloaded — tap to download");
            return;
        }
        long ts = db.getLastUpdatedMs();
        if (ts == 0) {
            pref.setSummary("Not downloaded — tap to download");
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            pref.setSummary("Last updated: " + sdf.format(new Date(ts)) + " — tap to update");
        }
    }

    private static void updateIcaoDeleteSummary(Preference pref, IcaoDatabase db) {
        if (pref == null) return;
        if (db == null || !db.isFilePresent()) {
            pref.setSummary("No database file present");
            pref.setEnabled(false);
        } else {
            pref.setSummary("Tap to remove local database file");
            pref.setEnabled(true);
        }
    }

    private static void setListSummary(ListPreference pref, String value,
            CharSequence[] labels, CharSequence[] values) {
        if (value == null) return;
        for (int i = 0; i < values.length; i++) {
            if (value.equals(values[i].toString())) {
                pref.setSummary(labels[i]);
                return;
            }
        }
    }

    private static void setRangeSummary(EditTextPreference pref, String value) {
        try {
            int km = Integer.parseInt(value == null ? "" : value.trim());
            pref.setSummary(km + " km");
        } catch (NumberFormatException e) {
            pref.setSummary("150 km (default)");
        }
    }

    private static void setStaleSummary(EditTextPreference pref, String value, String unit) {
        try {
            int sec = Integer.parseInt(value == null ? "" : value.trim());
            pref.setSummary(sec + " " + unit);
        } catch (NumberFormatException e) {
            pref.setSummary("(default)");
        }
    }

    private static void setPpmSummary(EditTextPreference pref, String value) {
        try {
            int ppm = Integer.parseInt(value == null ? "" : value.trim());
            if (ppm == 0) {
                pref.setSummary("0 ppm (no correction)");
            } else {
                pref.setSummary(ppm + " ppm");
            }
        } catch (NumberFormatException e) {
            pref.setSummary("0 ppm (no correction)");
        }
    }

    @Override
    public String getSubTitle() {
        return "Air+Sea Plugin";
    }
}
