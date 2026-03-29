/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.android.airseatool.plugin;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AirMarkerManager {

    private static final String TAG = "AirMarkerManager";
    private static final String UID_PREFIX = "ADSB-";
    private static final String COT_TYPE             = "a-n-A";
    private static final String COT_TYPE_FIXED_WING  = "a-n-A-C-F";
    private static final String COT_TYPE_ROTARY      = "a-n-A-C-H";
    private static final String COT_TYPE_LTA         = "a-n-A-C-L";
    private static final String COT_TYPE_GROUND_VEH  = "a-n-G-E-V-U";
    private static final String COT_TYPE_MIL_FIXED   = "a-n-A-M-F";
    private static final String COT_TYPE_MIL_ROTARY  = "a-n-A-M-H";
    private static final String GROUP_NAME = "ADS-B Aircraft";
    public  static final long DEFAULT_staleOffsetMs = 70 * 1000L;
    private volatile long staleOffsetMs = DEFAULT_staleOffsetMs;

    private volatile char affiliation = 'n';
    private volatile IcaoDatabase icaoDatabase;

    private final Map<String, Long> lastUpdateTimes = new ConcurrentHashMap<>();
    private final Map<String, Marker> markers = new ConcurrentHashMap<>();
    /** The CoT type we last programmatically set on each marker (uid → type). */
    private final Map<String, String> lastSetTypes = new ConcurrentHashMap<>();
    /** User-overridden full CoT type (uid → complete type string). */
    private final Map<String, String> userTypeOverrides = new ConcurrentHashMap<>();
    /** Last known non-empty category per ICAO — persists across polls that return category=0. */
    private final Map<String, String> categoryCache = new ConcurrentHashMap<>();
    private MapGroup airGroup;
    private long updateFrequencyMs;
    private volatile boolean broadcastAll = false;
    private volatile boolean militaryOnly = false;
    private long nextEvictMs = 0;

    public AirMarkerManager(int updateFrequencySeconds) {
        this.updateFrequencyMs = updateFrequencySeconds * 1000L;
    }

    public void setUpdateFrequency(int seconds) {
        this.updateFrequencyMs = seconds * 1000L;
    }

    public void setStaleOffsetSeconds(int seconds) {
        this.staleOffsetMs = seconds * 1000L;
    }

    public void setAffiliation(char affiliation) {
        this.affiliation = affiliation;
    }

    public void setIcaoDatabase(IcaoDatabase db) {
        this.icaoDatabase = db;
    }

    /** Replace the affiliation character in a CoT type string (e.g. "a-n-A-C-F" → "a-f-A-C-F"). */
    private String applyAffiliation(String cotType) {
        int firstDash  = cotType.indexOf('-');
        int secondDash = (firstDash >= 0) ? cotType.indexOf('-', firstDash + 1) : -1;
        if (firstDash < 0 || secondDash < 0) return cotType;
        return cotType.substring(0, firstDash + 1) + affiliation + cotType.substring(secondDash);
    }

    public void setBroadcastAll(boolean broadcast) {
        this.broadcastAll = broadcast;
        if (broadcast) broadcastAllExisting();
    }

    public void setMilitaryOnly(boolean militaryOnly) {
        this.militaryOnly = militaryOnly;
        if (militaryOnly) {
            // Evict any currently-displayed aircraft not flagged military in the ICAO database
            Iterator<Map.Entry<String, Marker>> it = markers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Marker> entry = it.next();
                String uid = entry.getKey();
                String icao24 = uid.replace(UID_PREFIX, "");
                IcaoRecord rec = (icaoDatabase != null) ? icaoDatabase.lookup(icao24) : null;
                if (rec == null || !rec.mil) {
                    Marker m = entry.getValue();
                    MapGroup parent = m.getGroup();
                    if (parent != null) parent.removeItem(m);
                    it.remove();
                    lastSetTypes.remove(uid);
                    userTypeOverrides.remove(uid);
                    categoryCache.remove(icao24);
                    lastUpdateTimes.remove(icao24);
                }
            }
        }
    }

    public int getAircraftCount() {
        return markers.size();
    }

    private MapGroup getOrCreateGroup() {
        if (airGroup != null) return airGroup;
        MapView mv = MapView.getMapView();
        if (mv == null) return null;
        MapGroup root = mv.getRootGroup();
        airGroup = root.findMapGroup(GROUP_NAME);
        if (airGroup == null) airGroup = root.addGroup(GROUP_NAME);
        return airGroup;
    }

    public void updateAircraft(Aircraft a) {
        if (a.icao24 == null || a.icao24.isEmpty()) return;
        // Skip frames with no position decoded yet (lat/lon both 0 = no CPR fix)
        if (a.lat == 0.0 && a.lon == 0.0) return;

        // Persist category across polls that return empty (e.g. OpenSky snapshot misses TC1-4)
        if (!a.category.isEmpty()) {
            categoryCache.put(a.icao24, a.category);
        } else {
            String cached = categoryCache.get(a.icao24);
            if (cached != null) a.category = cached;
        }

        long now = System.currentTimeMillis();

        // Evict markers not updated within staleOffsetMs — run every 60 s
        if (now >= nextEvictMs) {
            nextEvictMs = now + 60_000L;
            evictStale(now);
        }
        Long lastUpdate = lastUpdateTimes.get(a.icao24);
        lastUpdateTimes.put(a.icao24, now);
        if (updateFrequencyMs > 0
                && lastUpdate != null
                && (now - lastUpdate) < updateFrequencyMs) return;

        if (militaryOnly) {
            IcaoRecord rec = (icaoDatabase != null) ? icaoDatabase.lookup(a.icao24) : null;
            if (rec == null || !rec.mil) return;
        }

        String uid = UID_PREFIX + a.icao24;
        MapGroup group = getOrCreateGroup();
        if (group == null) return;

        try {
            IcaoRecord icaoRec = (icaoDatabase != null) ? icaoDatabase.lookup(a.icao24) : null;
            String displayName = resolveDisplayName(a);
            String remarks = buildRemarks(a, icaoRec);
            double speedMps = a.groundSpeedKnots * 0.514444;
            // Use UNKNOWN altitude for on-ground aircraft so ATAK doesn't
            // falsely place them at sea level. For airborne, convert
            // barometric (MSL) feet to meters — treated as HAE approximation.
            double altM = (a.onGround || a.altitudeFt <= 0)
                    ? GeoPoint.UNKNOWN
                    : a.altitudeFt * 0.3048;

            int style = (speedMps > 0)
                    ? (Marker.STYLE_ROTATE_HEADING_MASK
                            | Marker.STYLE_SMOOTH_ROTATION_MASK
                            | Marker.STYLE_SMOOTH_MOVEMENT_MASK)
                    : (Marker.STYLE_ROTATE_HEADING_NOARROW_MASK
                            | Marker.STYLE_SMOOTH_ROTATION_MASK);

            GeoPoint point = new GeoPoint(a.lat, a.lon, altM);
            Marker marker = markers.get(uid);

            // Detect user type change before computing the effective type
            if (marker != null) {
                detectUserTypeChange(uid, marker);
            }

            // Use user override if set, otherwise auto-categorize
            String cotType = resolveEffectiveType(resolveCotType(a, icaoRec), uid);

            if (marker == null) {
                marker = createMarker(point, uid, cotType, style, displayName,
                        a.trackDeg, speedMps, remarks, altM);
                group.addItem(marker);
                markers.put(uid, marker);
            } else if (!cotType.equals(marker.getType())) {
                // Type changed (auto-category update) — recreate for icon refresh
                group.removeItem(marker);
                markers.remove(uid);
                marker = createMarker(point, uid, cotType, style, displayName,
                        a.trackDeg, speedMps, remarks, altM);
                group.addItem(marker);
                markers.put(uid, marker);
            } else {
                marker.setPoint(point);
                marker.setStyle(style);
                marker.setTitle(displayName);
                marker.setMetaString("callsign", displayName);
                marker.setTrack(a.trackDeg, speedMps);
                marker.setMetaString("remarks", remarks);
                if (!Double.isNaN(altM))
                    marker.setMetaDouble("altitude", altM);
            }

            lastSetTypes.put(uid, cotType);

            if (broadcastAll) broadcastMarker(uid, displayName, a, cotType, remarks);
        } catch (Exception e) {
            Log.e(TAG, "Error updating aircraft marker " + a.icao24, e);
        }
    }

    private Marker createMarker(GeoPoint point, String uid, String cotType,
                                int style, String displayName,
                                double trackDeg, double speedMps,
                                String remarks, double altM) {
        Marker marker = new Marker(point, uid);
        marker.setType(cotType);
        marker.setStyle(style);
        marker.setTitle(displayName);
        marker.setMetaString("callsign", displayName);
        marker.setMetaString("how", "h-g-i-g-o");
        marker.setTrack(trackDeg, speedMps);
        marker.setMetaString("remarks", remarks);
        if (!Double.isNaN(altM))
            marker.setMetaDouble("altitude", altM);
        marker.setMetaBoolean("readiness", true);
        marker.setMetaBoolean("archive", false);
        marker.setEditable(true);
        marker.setVisible(true);
        return marker;
    }

    /**
     * Detect if the user changed the marker's type via ATAK's UI.
     * Compares the marker's current type to what we last set programmatically.
     * Stores the user's full type choice for future updates.
     */
    private void detectUserTypeChange(String uid, Marker marker) {
        String currentType = marker.getType();
        String lastSet = lastSetTypes.get(uid);
        if (lastSet == null || currentType == null) return;
        if (currentType.equals(lastSet)) return;
        // Type was changed externally — store the user's full type
        userTypeOverrides.put(uid, currentType);
        Log.d(TAG, uid + " user set type: " + currentType);
    }

    /**
     * If the user has overridden the type, use their choice; otherwise
     * use the auto-categorized type.
     */
    private String resolveEffectiveType(String autoType, String uid) {
        String userType = userTypeOverrides.get(uid);
        if (userType != null) return userType;
        return autoType;
    }

    private String resolveCotType(Aircraft a, IcaoRecord icaoRec) {
        if (icaoRec != null && icaoRec.mil) {
            // Military aircraft: classify by SHORT_TYPE first character.
            // L=Landplane, S=Seaplane, A=Amphibian → fixed wing
            // G=Gyroplane, H=Helicopter, R=Rotorcraft, T=Tiltrotor → rotary wing
            String st = icaoRec.shortType;
            if (!st.isEmpty()) {
                char first = Character.toUpperCase(st.charAt(0));
                if (first == 'L' || first == 'S' || first == 'A') {
                    return applyAffiliation(COT_TYPE_MIL_FIXED);
                } else if (first == 'G' || first == 'H' || first == 'R' || first == 'T') {
                    return applyAffiliation(COT_TYPE_MIL_ROTARY);
                }
            }
        }
        return applyAffiliation(resolveBaseCotType(a));
    }

    private static String resolveBaseCotType(Aircraft a) {
        String cat = a.category;
        if (cat == null || cat.isEmpty()) return COT_TYPE;
        switch (cat) {
            case "Light":              // A1
            case "Small":              // A2
            case "Large":              // A3
            case "High Vortex Large":  // A4
            case "Heavy":              // A5
            case "High Performance":   // A6
            case "Glider/Sailplane":   // B1
                return COT_TYPE_FIXED_WING;
            case "Rotorcraft":         // A7
                return COT_TYPE_ROTARY;
            case "Lighter-than-Air":   // B2
                return COT_TYPE_LTA;
            case "Emergency Vehicle":  // C1
            case "Service Vehicle":    // C2
                return COT_TYPE_GROUND_VEH;
            default:
                return COT_TYPE;
        }
    }

    private static String resolveDisplayName(Aircraft a) {
        if (!a.callsign.isEmpty()) return a.callsign;
        if (!a.registration.isEmpty()) return a.registration;
        return a.icao24.toUpperCase();
    }

    private static String buildRemarks(Aircraft a, IcaoRecord icaoRec) {
        StringBuilder sb = new StringBuilder();
        sb.append("ICAO24: ").append(a.icao24.toUpperCase());
        if (!a.callsign.isEmpty())
            sb.append("\nFlight: ").append(a.callsign);
        if (!a.registration.isEmpty())
            sb.append("\nReg: ").append(a.registration);
        if (icaoRec != null && !icaoRec.model.isEmpty())
            sb.append("\nModel: ").append(icaoRec.model);
        if (icaoRec != null && !icaoRec.ownop.isEmpty())
            sb.append("\nOperator: ").append(icaoRec.ownop);
        if (!a.aircraftType.isEmpty())
            sb.append("\nType: ").append(a.aircraftType);
        if (a.altitudeFt > 0)
            sb.append("\nAlt: ").append((int) a.altitudeFt).append(" ft");
        if (a.groundSpeedKnots > 0)
            sb.append("\nSpeed: ").append((int) a.groundSpeedKnots).append(" kts");
        if (a.verticalRateFpm != 0)
            sb.append("\nAscent: ").append((int) a.verticalRateFpm).append(" fpm");
        if (!a.squawk.isEmpty())
            sb.append("\nSquawk: ").append(a.squawk);
        if (!a.category.isEmpty())
            sb.append("\nCategory: ").append(a.category);
        if (a.onGround)
            sb.append("\n[On Ground]");
        return sb.toString();
    }

    private void broadcastMarker(String uid, String callsign,
                                  Aircraft a, String cotType, String remarks) {
        try {
            CotEvent event = new CotEvent();
            event.setUID(uid);
            event.setType(cotType);
            event.setHow("h-g-i-g-o");

            CoordinatedTime now = new CoordinatedTime();
            event.setTime(now);
            event.setStart(now);
            event.setStale(new CoordinatedTime(
                    now.getMilliseconds() + staleOffsetMs));
            double hae = (a.onGround || a.altitudeFt <= 0)
                    ? CotPoint.UNKNOWN
                    : a.altitudeFt * 0.3048;
            event.setPoint(new CotPoint(a.lat, a.lon, hae,
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN));

            CotDetail detail = new CotDetail("detail");
            CotDetail contact = new CotDetail("contact");
            contact.setAttribute("callsign", callsign);
            detail.addChild(contact);

            double speedMps = a.groundSpeedKnots * 0.514444;
            CotDetail track = new CotDetail("track");
            track.setAttribute("course", String.valueOf(a.trackDeg));
            track.setAttribute("speed", String.valueOf(speedMps));
            detail.addChild(track);

            CotDetail remarksDetail = new CotDetail("remarks");
            remarksDetail.setInnerText(remarks);
            detail.addChild(remarksDetail);

            event.setDetail(detail);

            CotDispatcher external = CotMapComponent.getExternalDispatcher();
            if (external != null) external.dispatch(event);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting aircraft " + uid, e);
        }
    }

    private void broadcastAllExisting() {
        for (Map.Entry<String, Marker> entry : markers.entrySet()) {
            try {
                Marker m = entry.getValue();
                Aircraft stub = new Aircraft();
                stub.icao24 = entry.getKey().replace(UID_PREFIX, "");
                GeoPoint p = m.getPoint();
                if (p == null) continue;
                stub.lat = p.getLatitude();
                stub.lon = p.getLongitude();
                stub.altitudeFt = p.getAltitude() / 0.3048;
                stub.trackDeg = m.getTrackHeading();
                stub.groundSpeedKnots = m.getTrackSpeed() / 0.514444;
                broadcastMarker(entry.getKey(),
                        m.getMetaString("callsign", m.getTitle()),
                        stub, m.getType(), m.getMetaString("remarks", ""));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting existing aircraft", e);
            }
        }
    }

    private void evictStale(long now) {
        MapGroup group = airGroup;
        Iterator<Map.Entry<String, Long>> it = lastUpdateTimes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > staleOffsetMs) {
                String uid = UID_PREFIX + entry.getKey();
                Marker m = markers.remove(uid);
                if (m != null) {
                    MapGroup parent = m.getGroup();
                    if (parent != null) parent.removeItem(m);
                }
                lastSetTypes.remove(uid);
                userTypeOverrides.remove(uid);
                categoryCache.remove(entry.getKey());
                it.remove();
            }
        }
    }

    public void removeAllMarkers() {
        // Remove each marker individually — user-retyped markers may have
        // been moved to a different MapGroup by ATAK
        for (Marker m : markers.values()) {
            MapGroup parent = m.getGroup();
            if (parent != null) parent.removeItem(m);
        }
        MapGroup group = airGroup;
        if (group != null) group.clearItems();
        markers.clear();
        lastUpdateTimes.clear();
        lastSetTypes.clear();
        userTypeOverrides.clear();
        categoryCache.clear();
        airGroup = null;
    }
}
