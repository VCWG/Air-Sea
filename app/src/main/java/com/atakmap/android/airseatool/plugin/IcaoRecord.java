/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

/** A trimmed record from the ADS-B Exchange basic aircraft database. */
public class IcaoRecord {
    /** True if this aircraft is flagged as military (mil=Y). */
    public final boolean mil;
    /** Aircraft model name, e.g. "F-16C" or "737 MAX 8". May be empty. */
    public final String model;
    /** Owner/operator name. May be empty. */
    public final String ownop;
    /** Short type code, e.g. "L1J" (landplane, 1 engine, jet). May be empty. */
    public final String shortType;

    public IcaoRecord(boolean mil, String model, String ownop, String shortType) {
        this.mil      = mil;
        this.model    = model    != null ? model    : "";
        this.ownop    = ownop    != null ? ownop    : "";
        this.shortType = shortType != null ? shortType : "";
    }
}
