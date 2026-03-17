/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

/**
 * ADS-B data source: adsb.fi (https://opendata.adsb.fi)
 * Free, no API key required. readsb/tar1090 JSON format.
 */
public class AdsbFiSource extends AbstractReadsbSource {

    @Override
    public String getName() {
        return "adsb.fi";
    }

    @Override
    String getBaseUrl() {
        return "https://opendata.adsb.fi/api";
    }
}
