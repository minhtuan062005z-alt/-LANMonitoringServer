package lanmonitoringserver.monitor.system;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

final class WindowsCimParser {
    private WindowsCimParser() {}

    static void apply(String json, SystemInfoBuilder builder) throws ProtocolException {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new ProtocolException("CIM output must be a JSON object");
        Map<?, ?> system = map(root.get("system"));
        if (system != null) {
            builder.manufacturer = text(system, "Manufacturer", builder.manufacturer);
            builder.model = text(system, "Model", builder.model);
            builder.osName = text(system, "OSName", builder.osName);
            builder.osVersion = text(system, "OSVersion", builder.osVersion);
            builder.architecture = text(system, "Architecture", builder.architecture);
            builder.totalMemoryBytes = number(system, "TotalMemory", builder.totalMemoryBytes);
            Long freeKilobytes = number(system, "FreeMemoryKB", null);
            if (freeKilobytes != null) builder.availableMemoryBytes = freeKilobytes * 1024L;
        }
        Map<?, ?> bios = map(root.get("bios"));
        if (bios != null) {
            builder.firmwareName = text(bios, "Name", builder.firmwareName);
            builder.firmwareVersion = text(bios, "Version", builder.firmwareVersion);
        }
        Map<?, ?> cpu = map(root.get("cpu"));
        if (cpu != null) {
            builder.cpuModel = text(cpu, "Name", builder.cpuModel);
            builder.cpuVendor = text(cpu, "Manufacturer", builder.cpuVendor);
            builder.physicalCores = integer(cpu, "Cores", builder.physicalCores);
            builder.logicalProcessors = integer(cpu, "Logical", builder.logicalProcessors);
            builder.maxFrequencyMhz = number(cpu, "MaxClockMHz", builder.maxFrequencyMhz);
        }
        builder.gpus.clear();
        for (Object item : list(root.get("gpus"))) {
            Map<?, ?> gpu = map(item);
            if (gpu == null) continue;
            String name = text(gpu, "Name", null);
            String category = classifyGpu(name);
            if (category == null) builder.unavailable.putIfAbsent("gpu.category",
                    "Integrated/discrete type cannot be identified reliably from CIM data");
            builder.gpus.add(new SystemInformation.GpuDetails(name,
                    text(gpu, "Vendor", null), text(gpu, "Driver", null),
                    number(gpu, "VRAM", null), category));
        }
        if (builder.gpus.isEmpty()) builder.unavailable.put("gpu", "CIM returned no video controller");

        List<?> disks = list(root.get("storage"));
        if (!disks.isEmpty()) {
            builder.storage.clear();
            for (Object item : disks) {
                Map<?, ?> disk = map(item);
                if (disk == null) continue;
                Long total = number(disk, "Size", null);
                Long free = number(disk, "Free", null);
                builder.storage.add(new SystemInformation.StorageDetails(
                        text(disk, "Device", null), text(disk, "Label", null),
                        text(disk, "Device", null), text(disk, "FileSystem", null),
                        total, total != null && free != null ? Math.max(0, total - free) : null,
                        free, null));
            }
            builder.unavailable.put("storage.mediaType",
                    "Logical disk CIM data does not reliably map volumes to SSD/HDD devices");
        }
    }

    private static String classifyGpu(String name) {
        if (name == null) return null;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("uhd graphics") || normalized.contains("iris")
                || normalized.contains("hd graphics") || normalized.contains("radeon graphics")) {
            return "Integrated";
        }
        if (normalized.contains("geforce rtx") || normalized.contains("geforce gtx")
                || normalized.contains("quadro") || normalized.contains("radeon rx")) {
            return "Discrete";
        }
        return null;
    }

    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : null; }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }
    private static Long number(Map<?, ?> map, String key, Long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }
    private static Integer integer(Map<?, ?> map, String key, Integer fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
