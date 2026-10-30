package lanmonitoringserver.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessConfidence;
import lanmonitoringserver.model.WirelessMeasurementTarget;
import lanmonitoringserver.model.WirelessNetworkType;
import lanmonitoringserver.model.WirelessRttCapability;
import lanmonitoringserver.model.WirelessSignalSample;

/** Strict binary-safe JSON mapping for direct wireless observations. */
public final class WirelessMapper {
    private WirelessMapper() {}

    public static Map<String, Object> toMap(WirelessSignalSample value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", value.timestamp());
        map.put("intervalMillis", value.intervalMillis());
        map.put("status", value.status().name());
        map.put("networkType", value.networkType().name());
        map.put("adapter", value.adapter());
        put(map, "ssid", value.ssid());
        put(map, "bssid", value.bssid());
        map.put("targetId", value.targetId());
        put(map, "rssiDbm", value.rssiDbm());
        put(map, "linkQualityPercent", value.linkQualityPercent());
        map.put("target", value.target().name());
        map.put("rttCapability", value.rttCapability().name());
        map.put("confidence", value.confidence().name());
        map.put("provider", value.provider());
        map.put("detail", value.detail());
        return map;
    }

    public static WirelessSignalSample fromMap(Map<?, ?> map) throws ProtocolException {
        try {
            return new WirelessSignalSample(integer(map, "timestamp", 1, Long.MAX_VALUE),
                    integer(map, "intervalMillis", 100, 60_000),
                    MeasurementStatus.valueOf(text(map, "status", 32)),
                    WirelessNetworkType.valueOf(text(map, "networkType", 32)),
                    text(map, "adapter", 255), optionalText(map, "ssid", 64),
                    optionalText(map, "bssid", 32), text(map, "targetId", 64),
                    optionalNumber(map, "rssiDbm", -150, 0),
                    optionalNumber(map, "linkQualityPercent", 0, 100),
                    WirelessMeasurementTarget.valueOf(text(map, "target", 32)),
                    WirelessRttCapability.valueOf(text(map, "rttCapability", 32)),
                    WirelessConfidence.valueOf(text(map, "confidence", 32)),
                    text(map, "provider", 128), text(map, "detail", 512));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid wireless sample", exception);
        }
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
    private static String text(Map<?, ?> map, String key, int max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.length() > max || text.indexOf('\0') >= 0) throw invalid(key);
        return text;
    }
    private static String optionalText(Map<?, ?> map, String key, int max) throws ProtocolException {
        return map.get(key) == null ? null : text(map, key, max);
    }
    private static long integer(Map<?, ?> map, String key, long min, long max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw invalid(key);
        long result = number.longValue();
        if (result < min || result > max || number.doubleValue() != result) throw invalid(key);
        return result;
    }
    private static Double optionalNumber(Map<?, ?> map, String key, double min, double max)
            throws ProtocolException {
        if (map.get(key) == null) return null;
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw invalid(key);
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) throw invalid(key);
        return result;
    }
    private static ProtocolException invalid(String key) {
        return new ProtocolException("Invalid wireless field: " + key);
    }
}
