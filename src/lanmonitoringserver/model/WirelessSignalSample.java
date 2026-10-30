package lanmonitoringserver.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Direct wireless observation. RSSI and link-quality remain distinct quantities. */
public record WirelessSignalSample(long timestamp, long intervalMillis,
        MeasurementStatus status, WirelessNetworkType networkType, String adapter,
        String ssid, String bssid, String targetId, Double rssiDbm,
        Double linkQualityPercent, WirelessMeasurementTarget target,
        WirelessRttCapability rttCapability, WirelessConfidence confidence,
        String provider, String detail) {

    public WirelessSignalSample {
        if (timestamp <= 0 || intervalMillis < 100 || intervalMillis > 60_000) {
            throw new IllegalArgumentException("Invalid wireless sample time");
        }
        if (status == null || networkType == null || target == null
                || rttCapability == null || confidence == null) {
            throw new IllegalArgumentException("Wireless sample enums are required");
        }
        adapter = bounded(adapter, 255);
        ssid = nullableBounded(ssid, 64);
        bssid = normalizeBssid(bssid);
        targetId = targetId == null ? "" : targetId.toLowerCase(Locale.ROOT);
        if (!targetId.isEmpty() && !targetId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid wireless target ID");
        }
        if (rssiDbm != null && (!Double.isFinite(rssiDbm) || rssiDbm < -150 || rssiDbm > 0)) {
            throw new IllegalArgumentException("Invalid RSSI dBm");
        }
        if (linkQualityPercent != null && (!Double.isFinite(linkQualityPercent)
                || linkQualityPercent < 0 || linkQualityPercent > 100)) {
            throw new IllegalArgumentException("Invalid link quality");
        }
        provider = bounded(provider, 128);
        detail = bounded(detail, 512);
    }

    public boolean isStale(long now) {
        return timestamp <= 0 || now - timestamp > Math.max(6_000L, intervalMillis * 3L);
    }

    /** Hides user-facing Wi-Fi identifiers while retaining a stable AP target hash. */
    public WirelessSignalSample forSharing(boolean shareIdentifiers) {
        return shareIdentifiers ? this : new WirelessSignalSample(timestamp, intervalMillis,
                status, networkType, adapter, null, null, targetId, rssiDbm,
                linkQualityPercent, target, rttCapability, confidence, provider,
                detail + (detail.isBlank() ? "" : " ") + "Wi-Fi identifiers hidden by Client privacy setting.");
    }

    public static String targetIdForBssid(String bssid) {
        String normalized = normalizeBssid(bssid);
        if (normalized == null) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String normalizeBssid(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', ':');
        if (!normalized.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            throw new IllegalArgumentException("Invalid BSSID");
        }
        return normalized;
    }
    private static String bounded(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || result.indexOf('\0') >= 0) throw new IllegalArgumentException("Text too long");
        return result;
    }
    private static String nullableBounded(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, max);
    }
}
