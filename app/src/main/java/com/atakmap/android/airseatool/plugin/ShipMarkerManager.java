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
    private static final String COT_TYPE             = "a-n-S-X-M";   // default: merchant
    private static final String COT_TYPE_HOVERCRAFT   = "a-n-S-X-H";   // hovercraft (WIG)
    private static final String COT_TYPE_FISHING      = "a-n-S-X-F";   // fishing
    private static final String COT_TYPE_MERCHANT_TOW = "a-n-S-X-M-T-O"; // towing vessel
    private static final String COT_TYPE_DREDGE       = "a-n-S-X-F-D-R"; // fishing dredge
    private static final String COT_TYPE_LEISURE      = "a-n-S-X-R";   // leisure craft
    private static final String COT_TYPE_FAST_CRAFT   = "a-n-S-X-A";   // fast recreational
    private static final String COT_TYPE_LAW_ENFORCE  = "a-n-S-X-L";   // law enforcement
    private static final String COT_TYPE_MERCHANT_TUG = "a-n-S-X-M-T-U"; // merchant tug
    private static final String COT_TYPE_MERCHANT_PAX = "a-n-S-X-M-P"; // merchant passenger
    private static final String COT_TYPE_MERCHANT_CRG = "a-n-S-X-M-C"; // merchant cargo
    private static final String COT_TYPE_MERCHANT_TNK = "a-n-S-X-M-O"; // merchant oiler/tanker
    private static final String COT_TYPE_HOSPITAL     = "a-n-S-N-M";   // noncombatant hospital
    private static final String COT_TYPE_NONCOMBATANT = "a-n-S-N";     // noncombatant
    private static final String COT_TYPE_NONCOMBAT_SH = "a-n-S-N-S";   // noncombatant service/harbor
    private static final String COT_TYPE_COMBATANT    = "a-n-S-C";     // combatant
    private static final String GROUP_NAME = "AIS Ships";
    public  static final long DEFAULT_staleOffsetMs = 5 * 60 * 1000L;
    private volatile long staleOffsetMs = DEFAULT_staleOffsetMs;

    private volatile char affiliation = 'n';

    private final Map<Integer, Long> lastUpdateTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lastShipTypes = new ConcurrentHashMap<>();
    private final Map<String, Marker> markers = new ConcurrentHashMap<>();
    /** The CoT type we last programmatically set on each marker (uid → type). */
    private final Map<String, String> lastSetTypes = new ConcurrentHashMap<>();
    /** User-overridden full CoT type (uid → complete type string). */
    private final Map<String, String> userTypeOverrides = new ConcurrentHashMap<>();
    private MapGroup aisGroup;
    private long updateFrequencyMs;
    private volatile boolean broadcastAll = false;

    public ShipMarkerManager(int updateFrequencySeconds) {
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

    /** Replace the affiliation character in a CoT type string (e.g. "a-n-S-X-M" → "a-f-S-X-M"). */
    private String applyAffiliation(String cotType) {
        int firstDash  = cotType.indexOf('-');
        int secondDash = (firstDash >= 0) ? cotType.indexOf('-', firstDash + 1) : -1;
        if (firstDash < 0 || secondDash < 0) return cotType;
        return cotType.substring(0, firstDash + 1) + affiliation + cotType.substring(secondDash);
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
            // Allow through if ship type changed (static data arrived)
            Integer prevType = lastShipTypes.get(mmsi);
            boolean typeChanged = shipType > 0
                    && (prevType == null || prevType != shipType);
            if (!typeChanged) return;
        }
        lastShipTypes.put(mmsi, shipType);
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

            // Detect user type change before computing effective type
            if (marker != null) {
                detectUserTypeChange(uid, marker);
            }

            String cotType = resolveEffectiveType(resolveCotType(shipType), uid);

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
                    // Type changed — recreate for icon refresh
                    MapGroup oldGroup = marker.getGroup();
                    if (oldGroup != null) oldGroup.removeItem(marker);
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
                    marker.setEditable(true);
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

    private String resolveCotType(int shipType) {
        return applyAffiliation(resolveBaseCotType(shipType));
    }

    private static String resolveBaseCotType(int shipType) {
        if (shipType >= 20 && shipType <= 29) return COT_TYPE_HOVERCRAFT;
        if (shipType == 30)                   return COT_TYPE_FISHING;
        if (shipType == 31 || shipType == 32) return COT_TYPE_MERCHANT_TOW;
        if (shipType == 33)                   return COT_TYPE_DREDGE;
        if (shipType == 35)                   return COT_TYPE_COMBATANT;
        if (shipType == 36 || shipType == 37) return COT_TYPE_LEISURE;
        if (shipType >= 40 && shipType <= 49) return COT_TYPE_FAST_CRAFT;
        if (shipType == 50 || shipType == 53) return COT_TYPE_NONCOMBAT_SH;
        if (shipType == 51 || shipType == 55) return COT_TYPE_LAW_ENFORCE;
        if (shipType == 52)                   return COT_TYPE_MERCHANT_TUG;
        if (shipType == 58)                   return COT_TYPE_HOSPITAL;
        if (shipType == 59)                   return COT_TYPE_NONCOMBATANT;
        if (shipType >= 60 && shipType <= 69) return COT_TYPE_MERCHANT_PAX;
        if (shipType >= 70 && shipType <= 79) return COT_TYPE_MERCHANT_CRG;
        if (shipType >= 80 && shipType <= 89) return COT_TYPE_MERCHANT_TNK;
        return COT_TYPE; // default: merchant
    }

    /**
     * Detect if the user changed the marker's type via ATAK's UI.
     * Stores the user's full type choice for future updates.
     */
    private void detectUserTypeChange(String uid, Marker marker) {
        String currentType = marker.getType();
        String lastSet = lastSetTypes.get(uid);
        if (lastSet == null || currentType == null) return;
        if (currentType.equals(lastSet)) return;
        userTypeOverrides.put(uid, currentType);
        Log.d(TAG, uid + " user set type: " + currentType);
    }

    /**
     * If the user has overridden the type, use their choice; otherwise
     * use the default type.
     */
    private String resolveEffectiveType(String defaultType, String uid) {
        String userType = userTypeOverrides.get(uid);
        if (userType != null) return userType;
        return defaultType;
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
                    now.getMilliseconds() + staleOffsetMs));

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
        // Remove each marker individually — user-retyped markers may have
        // been moved to a different MapGroup by ATAK
        for (Marker m : markers.values()) {
            MapGroup parent = m.getGroup();
            if (parent != null) parent.removeItem(m);
        }
        MapGroup group = aisGroup;
        if (group != null) {
            group.clearItems();
        }
        markers.clear();
        lastUpdateTimes.clear();
        lastShipTypes.clear();
        lastSetTypes.clear();
        userTypeOverrides.clear();
        aisGroup = null;
    }
}
