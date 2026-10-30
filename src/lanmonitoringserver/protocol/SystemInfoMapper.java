package lanmonitoringserver.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.SystemInformation;

public final class SystemInfoMapper {
    private SystemInfoMapper() {}

    public static Map<String, Object> toMap(SystemInformation information) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("collectedAt", information.collectedAt());
        root.put("collector", information.collector());
        root.put("system", systemToMap(information.system()));
        root.put("cpu", cpuToMap(information.cpu()));
        root.put("gpus", information.gpus().stream().map(SystemInfoMapper::gpuToMap).toList());
        root.put("memory", memoryToMap(information.memory()));
        root.put("storage", information.storage().stream().map(SystemInfoMapper::storageToMap).toList());
        root.put("network", information.network().stream().map(SystemInfoMapper::networkToMap).toList());
        root.put("unavailableReasons", new LinkedHashMap<>(information.unavailableReasons()));
        return root;
    }

    public static SystemInformation fromMap(Map<?, ?> root) throws ProtocolException {
        Map<?, ?> system = requiredMap(root, "system");
        Map<?, ?> cpu = requiredMap(root, "cpu");
        Map<?, ?> memory = requiredMap(root, "memory");
        List<SystemInformation.GpuDetails> gpus = new ArrayList<>();
        for (Object item : requiredList(root, "gpus")) {
            Map<?, ?> value = asMap(item, "gpus item");
            gpus.add(new SystemInformation.GpuDetails(string(value, "name"),
                    string(value, "vendor"), string(value, "driver"),
                    longValue(value, "vramBytes"), string(value, "category")));
        }
        List<SystemInformation.StorageDetails> storage = new ArrayList<>();
        for (Object item : requiredList(root, "storage")) {
            Map<?, ?> value = asMap(item, "storage item");
            storage.add(new SystemInformation.StorageDetails(string(value, "device"),
                    string(value, "model"), string(value, "mountPoint"),
                    string(value, "fileSystem"), longValue(value, "totalBytes"),
                    longValue(value, "usedBytes"), longValue(value, "freeBytes"),
                    string(value, "mediaType")));
        }
        List<SystemInformation.NetworkDetails> network = new ArrayList<>();
        for (Object item : requiredList(root, "network")) {
            Map<?, ?> value = asMap(item, "network item");
            network.add(new SystemInformation.NetworkDetails(string(value, "name"),
                    string(value, "displayName"), string(value, "connectionType"),
                    string(value, "macAddress"), stringList(value, "ipv4Addresses"),
                    stringList(value, "ipv6Addresses")));
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        Object reasonValue = root.get("unavailableReasons");
        if (reasonValue instanceof Map<?, ?> reasonMap) {
            for (Map.Entry<?, ?> entry : reasonMap.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String reason) {
                    reasons.put(key, reason);
                }
            }
        }
        return new SystemInformation(requiredLong(root, "collectedAt"),
                requiredString(root, "collector", 255),
                new SystemInformation.SystemDetails(string(system, "hostname"),
                        string(system, "osName"), string(system, "osVersion"),
                        string(system, "architecture"), string(system, "manufacturer"),
                        string(system, "model"), string(system, "firmwareName"),
                        string(system, "firmwareVersion"), string(system, "javaVersion")),
                new SystemInformation.CpuDetails(string(cpu, "model"), string(cpu, "vendor"),
                        integerValue(cpu, "physicalCores"), integerValue(cpu, "logicalProcessors"),
                        longValue(cpu, "maxFrequencyMhz")),
                gpus, new SystemInformation.MemoryDetails(longValue(memory, "totalBytes"),
                        longValue(memory, "availableBytes")), storage, network, reasons);
    }

    public static String toJson(SystemInformation information) throws ProtocolException {
        return JsonCodec.stringify(toMap(information));
    }

    public static SystemInformation fromJson(String json) throws ProtocolException {
        return fromMap(asMap(JsonCodec.parse(json), "system information"));
    }

    private static Map<String, Object> systemToMap(SystemInformation.SystemDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "hostname", value.hostname()); put(map, "osName", value.osName());
        put(map, "osVersion", value.osVersion()); put(map, "architecture", value.architecture());
        put(map, "manufacturer", value.manufacturer()); put(map, "model", value.model());
        put(map, "firmwareName", value.firmwareName());
        put(map, "firmwareVersion", value.firmwareVersion()); put(map, "javaVersion", value.javaVersion());
        return map;
    }

    private static Map<String, Object> cpuToMap(SystemInformation.CpuDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "model", value.model()); put(map, "vendor", value.vendor());
        put(map, "physicalCores", value.physicalCores());
        put(map, "logicalProcessors", value.logicalProcessors());
        put(map, "maxFrequencyMhz", value.maxFrequencyMhz());
        return map;
    }

    private static Map<String, Object> gpuToMap(SystemInformation.GpuDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "name", value.name()); put(map, "vendor", value.vendor());
        put(map, "driver", value.driver()); put(map, "vramBytes", value.vramBytes());
        put(map, "category", value.category());
        return map;
    }

    private static Map<String, Object> memoryToMap(SystemInformation.MemoryDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "totalBytes", value.totalBytes()); put(map, "availableBytes", value.availableBytes());
        return map;
    }

    private static Map<String, Object> storageToMap(SystemInformation.StorageDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "device", value.device()); put(map, "model", value.model());
        put(map, "mountPoint", value.mountPoint()); put(map, "fileSystem", value.fileSystem());
        put(map, "totalBytes", value.totalBytes()); put(map, "usedBytes", value.usedBytes());
        put(map, "freeBytes", value.freeBytes()); put(map, "mediaType", value.mediaType());
        return map;
    }

    private static Map<String, Object> networkToMap(SystemInformation.NetworkDetails value) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "name", value.name()); put(map, "displayName", value.displayName());
        put(map, "connectionType", value.connectionType()); put(map, "macAddress", value.macAddress());
        map.put("ipv4Addresses", value.ipv4Addresses()); map.put("ipv6Addresses", value.ipv6Addresses());
        return map;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        map.put(key, value);
    }

    private static Map<?, ?> requiredMap(Map<?, ?> map, String key) throws ProtocolException {
        return asMap(map.get(key), key);
    }

    private static Map<?, ?> asMap(Object value, String description) throws ProtocolException {
        if (!(value instanceof Map<?, ?> map)) throw new ProtocolException(description + " must be an object");
        return map;
    }

    private static List<?> requiredList(Map<?, ?> map, String key) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) throw new ProtocolException(key + " must be an array");
        return list;
    }

    private static String requiredString(Map<?, ?> map, String key, int maximum) throws ProtocolException {
        String value = string(map, key);
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new ProtocolException("Invalid field: " + key);
        }
        return value;
    }

    private static long requiredLong(Map<?, ?> map, String key) throws ProtocolException {
        Long value = longValue(map, key);
        if (value == null || value < 0) throw new ProtocolException("Invalid field: " + key);
        return value;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text : null;
    }

    private static Long longValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer integerValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static List<String> stringList(Map<?, ?> map, String key) throws ProtocolException {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw new ProtocolException(key + " contains non-string value");
            result.add(text);
        }
        return result;
    }
}
