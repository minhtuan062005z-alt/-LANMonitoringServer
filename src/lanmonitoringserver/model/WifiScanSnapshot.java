package lanmonitoringserver.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One rate-limited multi-AP scan; raw SSID/BSSID values never enter this model. */
public record WifiScanSnapshot(long timestamp, long intervalMillis, MeasurementStatus status,
        String adapter, List<AccessPointSignal> accessPoints, String provider, String detail) {
    public WifiScanSnapshot {
        if (timestamp <= 0 || intervalMillis < 5_000 || intervalMillis > 300_000) {
            throw new IllegalArgumentException("Invalid Wi-Fi scan timing");
        }
        if (status == null) throw new IllegalArgumentException("Wi-Fi scan status is required");
        adapter = bounded(adapter, 255);
        provider = bounded(provider, 128);
        detail = bounded(detail, 512);
        List<AccessPointSignal> copy = new ArrayList<>(accessPoints == null ? List.of() : accessPoints);
        if (copy.size() > 128) throw new IllegalArgumentException("Too many Access Points in one scan");
        HashSet<String> targets = new HashSet<>();
        for (AccessPointSignal signal : copy) {
            if (signal == null || !targets.add(signal.targetId())) {
                throw new IllegalArgumentException("Wi-Fi scan contains a null or duplicate Access Point");
            }
        }
        accessPoints = List.copyOf(copy);
        if (status == MeasurementStatus.OK && accessPoints.isEmpty()) {
            throw new IllegalArgumentException("An OK Wi-Fi scan must contain RSSI observations");
        }
    }

    public boolean isStale(long now) {
        return now - timestamp > Math.max(30_000L, intervalMillis * 3L);
    }

    private static String bounded(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || result.indexOf('\0') >= 0
                || result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid Wi-Fi scan text");
        }
        return result;
    }
}
