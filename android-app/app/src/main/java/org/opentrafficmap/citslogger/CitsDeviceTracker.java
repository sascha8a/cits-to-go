package org.opentrafficmap.citslogger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class CitsDeviceTracker {
    static final class Discovery {
        final String sourceMac;
        final String transmitterMac;
        final String receiverMac;
        final int frequencyMhz;
        final int rssiDbm;

        Discovery(String sourceMac, String transmitterMac, String receiverMac,
                  int frequencyMhz, int rssiDbm) {
            this.sourceMac = sourceMac;
            this.transmitterMac = transmitterMac;
            this.receiverMac = receiverMac;
            this.frequencyMhz = frequencyMhz;
            this.rssiDbm = rssiDbm;
        }
    }

    private static final String PREFS = "cits_seen_devices";
    private static final String KEY_SEEN_MACS = "seen_macs";
    private static final String KEY_NOTIFICATIONS = "notifications_enabled";

    private final SharedPreferences prefs;
    private final Set<String> seenMacs = new HashSet<>();
    private boolean notificationsEnabled;

    CitsDeviceTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_SEEN_MACS, Collections.emptySet());
        if (saved != null) seenMacs.addAll(saved);
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, false);
    }

    synchronized boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    synchronized void setNotificationsEnabled(boolean enabled) {
        notificationsEnabled = enabled;
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply();
    }

    synchronized int seenCount() {
        return seenMacs.size();
    }

    synchronized void clearSeenDevices() {
        seenMacs.clear();
        prefs.edit().putStringSet(KEY_SEEN_MACS, new HashSet<>()).apply();
    }

    synchronized Discovery notePacket(CitsPacket packet) {
        Discovery d = extractDiscovery(packet);
        if (d == null || d.sourceMac == null) return null;
        if (seenMacs.contains(d.sourceMac)) return null;
        seenMacs.add(d.sourceMac);
        prefs.edit().putStringSet(KEY_SEEN_MACS, new HashSet<>(seenMacs)).apply();
        return d;
    }

    private static Discovery extractDiscovery(CitsPacket packet) {
        byte[] frame = packet.payload;
        if (frame == null || frame.length < 24) return null;

        int fc = u8(frame[0]) | (u8(frame[1]) << 8);
        int type = (fc >> 2) & 0x03;
        if (type != 2) return null; // C-ITS GeoNetworking is carried in 802.11 data frames.

        boolean toDs = (fc & (1 << 8)) != 0;
        boolean fromDs = (fc & (1 << 9)) != 0;

        byte[] receiver = macAt(frame, 4);
        byte[] transmitter = macAt(frame, 10);
        byte[] source;

        if (toDs && fromDs) {
            source = frame.length >= 30 ? macAt(frame, 24) : transmitter;
        } else if (!toDs && fromDs) {
            source = macAt(frame, 16);
        } else {
            // OCB/IBSS/no-DS C-ITS frames normally use Address 2 as source/transmitter.
            source = transmitter;
        }

        if (!isUsableDeviceMac(source)) {
            source = isUsableDeviceMac(transmitter) ? transmitter : null;
        }
        if (source == null) return null;

        return new Discovery(
                formatMac(source),
                isUsableMac(transmitter) ? formatMac(transmitter) : "",
                isUsableMac(receiver) ? formatMac(receiver) : "",
                packet.frequencyMhz,
                packet.rssiDbm);
    }

    private static byte[] macAt(byte[] frame, int off) {
        if (off < 0 || frame.length < off + 6) return null;
        byte[] out = new byte[6];
        System.arraycopy(frame, off, out, 0, 6);
        return out;
    }

    private static boolean isUsableDeviceMac(byte[] mac) {
        if (!isUsableMac(mac)) return false;
        // Ignore multicast/group addresses. A discovered C-ITS device should be represented
        // by the source/transmitter station address, not a broadcast destination.
        return (mac[0] & 0x01) == 0;
    }

    private static boolean isUsableMac(byte[] mac) {
        if (mac == null || mac.length != 6) return false;
        boolean anyNonZero = false;
        boolean anyNonFf = false;
        for (byte b : mac) {
            if (b != 0) anyNonZero = true;
            if ((b & 0xff) != 0xff) anyNonFf = true;
        }
        return anyNonZero && anyNonFf;
    }

    private static String formatMac(byte[] mac) {
        return String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                u8(mac[0]), u8(mac[1]), u8(mac[2]), u8(mac[3]), u8(mac[4]), u8(mac[5]));
    }

    private static int u8(byte b) {
        return b & 0xff;
    }
}
