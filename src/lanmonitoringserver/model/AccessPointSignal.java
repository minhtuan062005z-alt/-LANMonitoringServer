package lanmonitoringserver.model;

/** Privacy-preserving RSSI observation for one Access Point in a multi-AP scan. */
public record AccessPointSignal(String targetId, double rssiDbm, Integer frequencyMhz) {
    public AccessPointSignal {
        targetId = targetId == null ? "" : targetId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!targetId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Access Point target ID must be a SHA-256 value");
        }
        if (!Double.isFinite(rssiDbm) || rssiDbm < -150 || rssiDbm > 0) {
            throw new IllegalArgumentException("RSSI must be a finite dBm value from -150 to 0");
        }
        if (frequencyMhz != null && (frequencyMhz < 1 || frequencyMhz > 100_000)) {
            throw new IllegalArgumentException("Invalid Wi-Fi frequency");
        }
    }
}
