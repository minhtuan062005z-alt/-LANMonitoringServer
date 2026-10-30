package lanmonitoringserver.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetrySample;

/** Strict conversion between the telemetry model and structured JSON payloads. */
public final class TelemetryMapper {
    private TelemetryMapper() {}

    public static Map<String, Object> toMap(TelemetrySample value) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", value.timestamp());
        root.put("intervalMillis", value.intervalMillis());
        root.put("cpu", map("status", value.cpuStatus().name(), "usagePercent", value.cpuUsagePercent(),
                "physicalCores", value.physicalCores(), "logicalProcessors", value.logicalProcessors(),
                "frequencyMhz", value.cpuFrequencyMhz()));
        root.put("memory", map("status", value.memoryStatus().name(), "usagePercent", value.memoryUsagePercent(),
                "usedBytes", value.memoryUsedBytes(), "freeBytes", value.memoryFreeBytes(),
                "totalBytes", value.memoryTotalBytes()));
        root.put("gpu", map("status", value.gpuStatus().name(), "usagePercent", value.gpuUsagePercent(),
                "vramUsedBytes", value.vramUsedBytes(), "vramTotalBytes", value.vramTotalBytes(),
                "temperatureCelsius", value.gpuTemperatureCelsius(), "detail", value.gpuDetail()));
        root.put("disk", map("status", value.diskStatus().name(), "activePercent", value.diskActivePercent(),
                "readBytesPerSecond", value.diskReadBytesPerSecond(),
                "writeBytesPerSecond", value.diskWriteBytesPerSecond(),
                "capacityUsagePercent", value.diskCapacityUsagePercent()));
        root.put("network", map("status", value.networkStatus().name(),
                "uploadBytesPerSecond", value.networkUploadBytesPerSecond(),
                "downloadBytesPerSecond", value.networkDownloadBytesPerSecond(),
                "interface", value.networkInterface(), "detail", value.networkDetail()));
        return root;
    }

    public static TelemetrySample fromMap(Map<?, ?> root) throws ProtocolException {
        long timestamp = integer(root, "timestamp", 1, Long.MAX_VALUE);
        long interval = integer(root, "intervalMillis", 100, 60_000);
        Map<?, ?> cpu = object(root, "cpu");
        Map<?, ?> memory = object(root, "memory");
        Map<?, ?> gpu = object(root, "gpu");
        Map<?, ?> disk = object(root, "disk");
        Map<?, ?> network = object(root, "network");
        return new TelemetrySample(timestamp, interval,
                status(cpu), number(cpu, "usagePercent", 0, 100),
                (int) integer(cpu, "physicalCores", 0, 4096),
                (int) integer(cpu, "logicalProcessors", 1, 4096), optionalNumber(cpu, "frequencyMhz", 0, 1_000_000),
                status(memory), number(memory, "usagePercent", 0, 100),
                integer(memory, "usedBytes", 0, Long.MAX_VALUE), integer(memory, "freeBytes", 0, Long.MAX_VALUE),
                integer(memory, "totalBytes", 0, Long.MAX_VALUE),
                status(gpu), optionalNumber(gpu, "usagePercent", 0, 100),
                optionalInteger(gpu, "vramUsedBytes", 0, Long.MAX_VALUE),
                optionalInteger(gpu, "vramTotalBytes", 0, Long.MAX_VALUE),
                optionalNumber(gpu, "temperatureCelsius", -50, 200), text(gpu, "detail", 512),
                status(disk), optionalNumber(disk, "activePercent", 0, 100),
                number(disk, "readBytesPerSecond", 0, Double.MAX_VALUE),
                number(disk, "writeBytesPerSecond", 0, Double.MAX_VALUE),
                optionalNumber(disk, "capacityUsagePercent", 0, 100),
                status(network), number(network, "uploadBytesPerSecond", 0, Double.MAX_VALUE),
                number(network, "downloadBytesPerSecond", 0, Double.MAX_VALUE),
                text(network, "interface", 255), text(network, "detail", 512));
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static Map<?, ?> object(Map<?, ?> map, String key) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> object)) throw invalid(key);
        return object;
    }
    private static MeasurementStatus status(Map<?, ?> map) throws ProtocolException {
        try { return MeasurementStatus.valueOf(text(map, "status", 32)); }
        catch (IllegalArgumentException exception) { throw invalid("status"); }
    }
    private static String text(Map<?, ?> map, String key, int max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.length() > max) throw invalid(key);
        return text;
    }
    private static double number(Map<?, ?> map, String key, double min, double max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw invalid(key);
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) throw invalid(key);
        return result;
    }
    private static Double optionalNumber(Map<?, ?> map, String key, double min, double max) throws ProtocolException {
        return map.get(key) == null ? null : number(map, key, min, max);
    }
    private static long integer(Map<?, ?> map, String key, long min, long max) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw invalid(key);
        long result = number.longValue();
        if (result < min || result > max || number.doubleValue() != result) throw invalid(key);
        return result;
    }
    private static Long optionalInteger(Map<?, ?> map, String key, long min, long max) throws ProtocolException {
        return map.get(key) == null ? null : integer(map, key, min, max);
    }
    private static ProtocolException invalid(String key) {
        return new ProtocolException("Invalid telemetry field: " + key);
    }
}
