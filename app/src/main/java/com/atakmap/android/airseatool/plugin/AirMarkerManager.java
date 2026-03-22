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
    private static final String COT_TYPE = "a-n-A";
    private static final String GROUP_NAME = "ADS-B Aircraft";
    private static final long STALE_OFFSET_MS = 60 * 1000L;

    private final Map<String, Long> lastUpdateTimes = new ConcurrentHashMap<>();
    private final Map<String, Marker> markers = new ConcurrentHashMap<>();
    private MapGroup airGroup;
    private long updateFrequencyMs;
    private volatile boolean broadcastAll = false;
    private long nextEvictMs = 0;

    public AirMarkerManager(int updateFrequencySeconds) {
        this.updateFrequencyMs = updateFrequencySeconds * 1000L;
    }

    public void setUpdateFrequency(int seconds) {
        this.updateFrequencyMs = seconds * 1000L;
    }

    public void setBroadcastAll(boolean broadcast) {
        this.broadcastAll = broadcast;
        if (broadcast) broadcastAllExisting();
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

        long now = System.currentTimeMillis();

        // Evict markers not updated within STALE_OFFSET_MS — run every 60 s
        if (now >= nextEvictMs) {
            nextEvictMs = now + 60_000L;
            evictStale(now);
        }
        Long lastUpdate = lastUpdateTimes.get(a.icao24);
        lastUpdateTimes.put(a.icao24, now);
        if (updateFrequencyMs > 0
                && lastUpdate != null
                && (now - lastUpdate) < updateFrequencyMs) return;

        String uid = UID_PREFIX + a.icao24;
        MapGroup group = getOrCreateGroup();
        if (group == null) return;

        try {
            String displayName = resolveDisplayName(a);
            String remarks = buildRemarks(a);
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

            if (marker == null) {
                marker = new Marker(point, uid);
                marker.setType(COT_TYPE);
                marker.setStyle(style);
                marker.setTitle(displayName);
                marker.setMetaString("callsign", displayName);
                marker.setMetaString("how", "m-g");
                marker.setTrack(a.trackDeg, speedMps);
                marker.setMetaString("remarks", remarks);
                if (!Double.isNaN(altM))
                    marker.setMetaDouble("altitude", altM);
                marker.setMetaBoolean("readiness", true);
                marker.setMetaBoolean("archive", false);
                marker.setVisible(true);
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

            if (broadcastAll) broadcastMarker(uid, displayName, a, remarks);
        } catch (Exception e) {
            Log.e(TAG, "Error updating aircraft marker " + a.icao24, e);
        }
    }

    private static String resolveDisplayName(Aircraft a) {
        if (!a.callsign.isEmpty()) return a.callsign;
        if (!a.registration.isEmpty()) return a.registration;
        return a.icao24.toUpperCase();
    }

    private static String buildRemarks(Aircraft a) {
        StringBuilder sb = new StringBuilder();
        sb.append("ICAO24: ").append(a.icao24.toUpperCase());
        if (!a.callsign.isEmpty())
            sb.append("\nFlight: ").append(a.callsign);
        if (!a.registration.isEmpty())
            sb.append("\nReg: ").append(a.registration);
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
                                  Aircraft a, String remarks) {
        try {
            CotEvent event = new CotEvent();
            event.setUID(uid);
            event.setType(COT_TYPE);
            event.setHow("m-g");

            CoordinatedTime now = new CoordinatedTime();
            event.setTime(now);
            event.setStart(now);
            event.setStale(new CoordinatedTime(
                    now.getMilliseconds() + STALE_OFFSET_MS));
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
                        stub, m.getMetaString("remarks", ""));
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
            if (now - entry.getValue() > STALE_OFFSET_MS) {
                String uid = UID_PREFIX + entry.getKey();
                Marker m = markers.remove(uid);
                if (m != null && group != null) group.removeItem(m);
                it.remove();
            }
        }
    }

    public void removeAllMarkers() {
        MapGroup group = airGroup;
        if (group != null) group.clearItems();
        markers.clear();
        lastUpdateTimes.clear();
        airGroup = null;
    }
}
