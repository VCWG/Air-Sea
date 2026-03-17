/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

/**
 * ADS-B data source: adsb.lol (https://api.adsb.lol)
 * Free, no API key required. readsb/tar1090 JSON format.
 */
public class AdsbLolSource extends AbstractReadsbSource {

    @Override
    public String getName() {
        return "adsb.lol";
    }

    @Override
    String getBaseUrl() {
        return "https://api.adsb.lol";
    }
}
