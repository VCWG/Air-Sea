/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import android.content.Context;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.PreferenceScreen;
import android.text.InputType;

import com.atakmap.android.preference.PluginPreferenceFragment;

public class AirSeaPreferenceFragment extends PluginPreferenceFragment {

    public AirSeaPreferenceFragment(Context pluginContext) {
        super(pluginContext, R.xml.airsea_preferences);
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
        CharSequence[] gainLabels  = pluginContext.getResources()
                .getTextArray(R.array.rtl_gain_labels);
        CharSequence[] gainValues  = pluginContext.getResources()
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

        // ── RTL-SDR Gain ─────────────────────────────────────────────────
        ListPreference gainPref = new ListPreference(getActivity());
        gainPref.setKey(AirSeaTool.PREF_RTL_GAIN);
        gainPref.setTitle("RTL-SDR Gain");
        gainPref.setEntries(gainLabels);
        gainPref.setEntryValues(gainValues);
        gainPref.setDefaultValue(
                String.valueOf(RtlTcpClient.DEFAULT_GAIN_TENTHS_DB));
        gainPref.setOnPreferenceChangeListener((pref, newVal) -> {
            setListSummary((ListPreference) pref,
                    (String) newVal, gainLabels, gainValues);
            return true;
        });
        screen.addPreference(gainPref);

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

        // Commit screen — after this, getValue()/getText() return saved or default values
        setPreferenceScreen(screen);

        // Set initial summaries now that preferences are bound to SharedPreferences
        setListSummary(affilPref, affilPref.getValue(), affilLabels, affilValues);
        setListSummary(gainPref, gainPref.getValue(), gainLabels, gainValues);
        setRangeSummary(rangePref, rangePref.getText());
        setStaleSummary(airStalePref, airStalePref.getText(), "s");
        setStaleSummary(shipStalePref, shipStalePref.getText(), "s");
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

    @Override
    public String getSubTitle() {
        return "Air+Sea Plugin";
    }
}
