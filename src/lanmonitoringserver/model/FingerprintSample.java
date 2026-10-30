package lanmonitoringserver.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Administrator-surveyed RSSI fingerprint at a known room coordinate. */
public record FingerprintSample(String sampleId, String clientId, SpatialPosition position,
        Map<String, Double> rssiByTarget, long capturedAt) {
    public FingerprintSample {
        sampleId = sampleId == null ? "" : sampleId.trim();
        if (!sampleId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Invalid fingerprint sample ID");
        }
        clientId = clientId == null ? "" : clientId.trim();
        if (clientId.isEmpty() || clientId.length() > 128 || clientId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid fingerprint Client scope");
        }
        if (position == null) throw new IllegalArgumentException("Fingerprint position is required");
        if (capturedAt <= 0) throw new IllegalArgumentException("Fingerprint timestamp is required");
        LinkedHashMap<String, Double> copy = new LinkedHashMap<>();
        if (rssiByTarget != null) copy.putAll(rssiByTarget);
        if (copy.size() < 3 || copy.size() > 128) {
            throw new IllegalArgumentException("A fingerprint needs 3 to 128 Access Points");
        }
        for (Map.Entry<String, Double> entry : copy.entrySet()) {
            String target = entry.getKey();
            Double rssi = entry.getValue();
            if (target == null || !target.matches("[0-9a-f]{64}") || rssi == null
                    || !Double.isFinite(rssi) || rssi < -150 || rssi > 0) {
                throw new IllegalArgumentException("Invalid fingerprint RSSI entry");
            }
        }
        rssiByTarget = Map.copyOf(copy);
    }
}
