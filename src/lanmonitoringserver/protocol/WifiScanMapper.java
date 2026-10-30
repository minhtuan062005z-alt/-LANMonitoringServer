package lanmonitoringserver.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WifiScanSnapshot;

/** Strict JSON mapping for privacy-preserving multi-AP snapshots. */
public final class WifiScanMapper {
    private WifiScanMapper() {}

    public static Map<String, Object> toMap(WifiScanSnapshot value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", value.timestamp());
        map.put("intervalMillis", value.intervalMillis());
        map.put("status", value.status().name());
        map.put("adapter", value.adapter());
        List<Object> points = new ArrayList<>();
        for (AccessPointSignal signal : value.accessPoints()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("targetId", signal.targetId());
            item.put("rssiDbm", signal.rssiDbm());
            if (signal.frequencyMhz() != null) item.put("frequencyMhz", signal.frequencyMhz());
            points.add(item);
        }
        map.put("accessPoints", points);
        map.put("provider", value.provider());
        map.put("detail", value.detail());
        return map;
    }

    public static WifiScanSnapshot fromMap(Map<?, ?> map) throws ProtocolException {
        try {
            Object raw = map.get("accessPoints");
            if (!(raw instanceof List<?> list) || list.size() > 128) throw invalid("accessPoints");
            List<AccessPointSignal> points = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> signal)) throw invalid("accessPoints");
                Object frequency = signal.get("frequencyMhz");
                points.add(new AccessPointSignal(text(signal, "targetId", 64),
                        number(signal, "rssiDbm", -150, 0), frequency == null ? null
                                : (int) integer(signal, "frequencyMhz", 1, 100_000)));
            }
            return new WifiScanSnapshot(integer(map, "timestamp", 1, Long.MAX_VALUE),
                    integer(map, "intervalMillis", 5_000, 300_000),
                    MeasurementStatus.valueOf(text(map, "status", 32)),
                    text(map, "adapter", 255), points, text(map, "provider", 128),
                    text(map, "detail", 512));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid Wi-Fi scan", exception);
        }
    }

    private static String text(Map<?, ?> map, String key, int max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.length() > max || text.indexOf('\0') >= 0) throw invalid(key);
        return text;
    }
    private static long integer(Map<?, ?> map, String key, long min, long max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() < min || number.longValue() > max) throw invalid(key);
        return number.longValue();
    }
    private static double number(Map<?, ?> map, String key, double min, double max)
            throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max) throw invalid(key);
        return number.doubleValue();
    }
    private static ProtocolException invalid(String key) {
        return new ProtocolException("Invalid Wi-Fi scan field: " + key);
    }
}
