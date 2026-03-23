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
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ShipMarkerManager {

    private static final String TAG = "ShipMarkerManager";
    private static final String UID_PREFIX = "AIS-";
    private static final String COT_TYPE = "a-n-S";
    private static final String GROUP_NAME = "AIS Ships";
    private static final long STALE_OFFSET_MS = 5 * 60 * 1000L;

    private final Map<Integer, Long> lastUpdateTimes = new ConcurrentHashMap<>();
    private final Map<String, Marker> markers = new ConcurrentHashMap<>();
    /** The CoT type we last programmatically set on each marker (uid → type). */
    private final Map<String, String> lastSetTypes = new ConcurrentHashMap<>();
    /** User-overridden affiliation characters (uid → affiliation char, e.g. "h"). */
    private final Map<String, String> userAffiliations = new ConcurrentHashMap<>();
    private MapGroup aisGroup;
    private long updateFrequencyMs;
    private volatile boolean broadcastAll = false;

    public ShipMarkerManager(int updateFrequencySeconds) {
        this.updateFrequencyMs = updateFrequencySeconds * 1000L;
    }

    public void setUpdateFrequency(int seconds) {
        this.updateFrequencyMs = seconds * 1000L;
    }

    public void setBroadcastAll(boolean broadcast) {
        this.broadcastAll = broadcast;
        if (broadcast) {
            broadcastAllExisting();
        }
    }

    public int getShipCount() {
        return markers.size();
    }

    public void updateShipName(int mmsi, String name) {
        if (name == null || name.isEmpty()) return;
        String uid = UID_PREFIX + mmsi;
        Marker marker = markers.get(uid);
        if (marker == null) return;
        marker.setTitle(name);
        marker.setMetaString("callsign", name);
    }

    private MapGroup getOrCreateGroup() {
        if (aisGroup != null) return aisGroup;
        MapView mv = MapView.getMapView();
        if (mv == null) return null;
        MapGroup root = mv.getRootGroup();
        aisGroup = root.findMapGroup(GROUP_NAME);
        if (aisGroup == null) {
            aisGroup = root.addGroup(GROUP_NAME);
        }
        return aisGroup;
    }

    public void updateShip(int mmsi, String shipName, double lat, double lon,
            double cog, double sog, int rot, int trueHeading,
            int navStatus, int shipType, double draught,
            String destination, String eta, int imoNumber) {
        long now = System.currentTimeMillis();
        Long lastUpdate = lastUpdateTimes.get(mmsi);
        if (lastUpdate != null && (now - lastUpdate) < updateFrequencyMs) {
            return;
        }
        lastUpdateTimes.put(mmsi, now);

        String uid = UID_PREFIX + mmsi;
        MapGroup group = getOrCreateGroup();
        if (group == null) return;

        try {
            Marker marker = markers.get(uid);
            GeoPoint point = new GeoPoint(lat, lon, 0);

            StringBuilder remarksText = new StringBuilder();
            remarksText.append("MMSI: ").append(mmsi);
            if (imoNumber > 0) {
                remarksText.append("\nIMO: ").append(imoNumber);
            }
            if (navStatus >= 0 && navStatus <= 15) {
                remarksText.append("\nNav Status: ")
                        .append(getNavStatusText(navStatus));
            }
            if (shipType > 0) {
                remarksText.append("\nShip Type: ")
                        .append(getShipTypeText(shipType));
            }
            remarksText.append("\nRate of Turn: ").append(rot);
            if (trueHeading != 511) {
                remarksText.append("\nTrue Heading: ").append(trueHeading);
            } else {
                remarksText.append("\nTrue Heading: N/A");
            }
            if (draught > 0) {
                remarksText.append("\nDraught: ").append(draught).append(" m");
            }
            if (destination != null && !destination.isEmpty()) {
                remarksText.append("\nDestination: ").append(destination);
            }
            if (eta != null && !eta.isEmpty()) {
                remarksText.append("\nETA: ").append(eta);
            }

            double sogMps = sog * 0.514444;

            int style = (sogMps > 0)
                    ? (Marker.STYLE_ROTATE_HEADING_MASK
                            | Marker.STYLE_SMOOTH_ROTATION_MASK
                            | Marker.STYLE_SMOOTH_MOVEMENT_MASK)
                    : (Marker.STYLE_ROTATE_HEADING_NOARROW_MASK
                            | Marker.STYLE_SMOOTH_ROTATION_MASK);

            // Detect user affiliation change before computing effective type
            if (marker != null) {
                detectUserAffiliation(uid, marker);
            }

            String cotType = applyUserAffiliation(COT_TYPE, uid);

            if (marker == null) {
                marker = new Marker(point, uid);
                marker.setType(cotType);
                marker.setStyle(style);
                marker.setTitle(shipName);
                marker.setMetaString("callsign", shipName);
                marker.setMetaString("how", "h-g-i-g-o");
                marker.setTrack(cog, sogMps);
                marker.setMetaString("remarks", remarksText.toString());
                marker.setMetaBoolean("readiness", true);
                marker.setMetaBoolean("archive", false);
                marker.setEditable(true);
                marker.setVisible(true);
                group.addItem(marker);
                markers.put(uid, marker);
            } else {
                if (!cotType.equals(marker.getType())) {
                    // User changed affiliation — recreate for icon refresh
                    group.removeItem(marker);
                    markers.remove(uid);
                    marker = new Marker(point, uid);
                    marker.setType(cotType);
                    marker.setStyle(style);
                    marker.setTitle(shipName);
                    marker.setMetaString("callsign", shipName);
                    marker.setMetaString("how", "h-g-i-g-o");
                    marker.setTrack(cog, sogMps);
                    marker.setMetaString("remarks", remarksText.toString());
                    marker.setMetaBoolean("readiness", true);
                    marker.setMetaBoolean("archive", false);
                    marker.setVisible(true);
                    group.addItem(marker);
                    markers.put(uid, marker);
                } else {
                    marker.setPoint(point);
                    marker.setStyle(style);
                    marker.setTitle(shipName);
                    marker.setMetaString("callsign", shipName);
                    marker.setTrack(cog, sogMps);
                    marker.setMetaString("remarks", remarksText.toString());
                }
            }

            lastSetTypes.put(uid, cotType);

            if (broadcastAll) {
                broadcastMarker(uid, shipName, lat, lon, cog, sogMps,
                        cotType, remarksText.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating marker for MMSI " + mmsi, e);
        }
    }

    /**
     * Detect if the user changed the marker's affiliation via ATAK's UI.
     */
    private void detectUserAffiliation(String uid, Marker marker) {
        String currentType = marker.getType();
        String lastSet = lastSetTypes.get(uid);
        if (lastSet == null || currentType == null) return;
        if (currentType.equals(lastSet)) return;
        if (currentType.length() >= 3 && currentType.charAt(0) == 'a'
                && currentType.charAt(1) == '-') {
            String aff = String.valueOf(currentType.charAt(2));
            userAffiliations.put(uid, aff);
            Log.d(TAG, uid + " user set affiliation: " + aff);
        }
    }

    /**
     * Apply a user-overridden affiliation character to a CoT type.
     */
    private String applyUserAffiliation(String baseType, String uid) {
        String aff = userAffiliations.get(uid);
        if (aff == null || baseType.length() < 3) return baseType;
        return baseType.substring(0, 2) + aff + baseType.substring(3);
    }

    private static String getNavStatusText(int status) {
        switch (status) {
            case 0: return "Under Way Using Engine";
            case 1: return "At Anchor";
            case 2: return "Not Under Command";
            case 3: return "Restricted Maneuverability";
            case 4: return "Constrained by Draught";
            case 5: return "Moored";
            case 6: return "Aground";
            case 7: return "Engaged in Fishing";
            case 8: return "Under Way Sailing";
            case 9: return "Reserved (HSC)";
            case 10: return "Reserved (WIG)";
            case 11: return "Power-Driven Towing Astern";
            case 12: return "Power-Driven Pushing/Towing";
            case 13: return "Reserved";
            case 14: return "AIS-SART/MOB/EPIRB";
            case 15: return "Undefined";
            default: return String.valueOf(status);
        }
    }

    private static String getShipTypeText(int type) {
        if (type >= 20 && type <= 28) return "WIG (" + type + ")";
        if (type >= 40 && type <= 49) return "High-Speed Craft (" + type + ")";
        if (type >= 60 && type <= 69) return "Passenger (" + type + ")";
        if (type >= 70 && type <= 79) return "Cargo (" + type + ")";
        if (type >= 80 && type <= 89) return "Tanker (" + type + ")";
        if (type >= 90 && type <= 99) return "Other (" + type + ")";
        switch (type) {
            case 29: return "SAR Aircraft";
            case 30: return "Fishing";
            case 31: return "Towing";
            case 32: return "Towing (Large)";
            case 33: return "Dredger";
            case 34: return "Diving Operations";
            case 35: return "Military";
            case 36: return "Sailing";
            case 37: return "Pleasure Craft";
            case 50: return "Pilot Vessel";
            case 51: return "Search & Rescue";
            case 52: return "Tug";
            case 53: return "Port Tender";
            case 54: return "Anti-Pollution";
            case 55: return "Law Enforcement";
            case 56: return "Spare";
            case 57: return "Spare";
            case 58: return "Medical Transport";
            case 59: return "Noncombatant Ship";
            default: return String.valueOf(type);
        }
    }

    private void broadcastMarker(String uid, String callsign, double lat,
            double lon, double cog, double speed,
            String cotType, String remarks) {
        try {
            CotEvent event = new CotEvent();
            event.setUID(uid);
            event.setType(cotType);
            event.setHow("h-g-i-g-o");

            CoordinatedTime now = new CoordinatedTime();
            event.setTime(now);
            event.setStart(now);
            event.setStale(new CoordinatedTime(
                    now.getMilliseconds() + STALE_OFFSET_MS));

            event.setPoint(new CotPoint(lat, lon, 0,
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN));

            CotDetail detail = new CotDetail("detail");

            CotDetail contact = new CotDetail("contact");
            contact.setAttribute("callsign", callsign);
            detail.addChild(contact);

            CotDetail track = new CotDetail("track");
            track.setAttribute("course", String.valueOf(cog));
            track.setAttribute("speed", String.valueOf(speed));
            detail.addChild(track);

            CotDetail remarksDetail = new CotDetail("remarks");
            remarksDetail.setInnerText(remarks);
            detail.addChild(remarksDetail);

            event.setDetail(detail);

            CotDispatcher external =
                    CotMapComponent.getExternalDispatcher();
            if (external != null) {
                external.dispatch(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting marker " + uid, e);
        }
    }

    private void broadcastAllExisting() {
        for (Map.Entry<String, Marker> entry : markers.entrySet()) {
            try {
                Marker m = entry.getValue();
                String uid = entry.getKey();
                GeoPoint p = m.getPoint();
                if (p == null) continue;
                double[] track = {0, 0};
                try {
                    track[0] = m.getTrackHeading();
                    track[1] = m.getTrackSpeed();
                } catch (Exception ignored) {}

                broadcastMarker(uid,
                        m.getMetaString("callsign", m.getTitle()),
                        p.getLatitude(), p.getLongitude(),
                        track[0], track[1],
                        m.getType(), m.getMetaString("remarks", ""));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting existing marker", e);
            }
        }
    }

    public void removeAllMarkers() {
        MapGroup group = aisGroup;
        if (group != null) {
            group.clearItems();
        }
        markers.clear();
        lastUpdateTimes.clear();
        lastSetTypes.clear();
        userAffiliations.clear();
        aisGroup = null;
    }
}
