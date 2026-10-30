package lanmonitoringserver.monitor.system;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lanmonitoringserver.model.SystemInformation;

final class LinuxParsers {
    private static final Pattern LSBLK_PAIR = Pattern.compile("([A-Z0-9_]+)=\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern QUOTED_FIELD = Pattern.compile("\"([^\"]*)\"");

    private LinuxParsers() {}

    static Map<String, String> parseOsRelease(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : content.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator <= 0 || line.startsWith("#")) continue;
            String value = line.substring(separator + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
            }
            values.put(line.substring(0, separator).trim(), value);
        }
        return values;
    }

    static void parseCpuInfo(String content, SystemInfoBuilder builder) {
        List<Map<String, String>> processors = new ArrayList<>();
        for (String block : content.split("(?:\\r?\\n){2,}")) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : block.split("\\R")) {
                int separator = line.indexOf(':');
                if (separator > 0) fields.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
            if (!fields.isEmpty()) processors.add(fields);
        }
        if (processors.isEmpty()) return;
        Map<String, String> first = processors.get(0);
        builder.cpuModel = first.getOrDefault("model name", first.get("Processor"));
        builder.cpuVendor = first.getOrDefault("vendor_id", first.get("CPU implementer"));
        builder.logicalProcessors = processors.size();
        Set<String> physicalCoreIds = new HashSet<>();
        for (Map<String, String> processor : processors) {
            String physical = processor.get("physical id");
            String core = processor.get("core id");
            if (physical != null && core != null) physicalCoreIds.add(physical + ':' + core);
        }
        if (!physicalCoreIds.isEmpty()) builder.physicalCores = physicalCoreIds.size();
        else builder.physicalCores = parseInteger(first.get("cpu cores"));
        long maximum = 0;
        for (Map<String, String> processor : processors) {
            try {
                String mhz = processor.get("cpu MHz");
                if (mhz != null) maximum = Math.max(maximum, Math.round(Double.parseDouble(mhz)));
            } catch (NumberFormatException ignored) {}
        }
        if (maximum > 0) builder.maxFrequencyMhz = maximum;
    }

    static void parseLsblk(String content, SystemInfoBuilder builder) {
        List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String> mediaByDisk = new HashMap<>();
        for (String line : content.split("\\R")) {
            Map<String, String> row = parsePairs(line);
            if (row.isEmpty()) continue;
            rows.add(row);
            if ("disk".equals(row.get("TYPE"))) mediaByDisk.put(row.get("NAME"), media(row.get("ROTA")));
        }
        if (rows.isEmpty()) return;
        builder.storage.clear();
        for (Map<String, String> row : rows) {
            String type = row.get("TYPE");
            if (!"disk".equals(type) && !"part".equals(type) && !"lvm".equals(type)) continue;
            String mount = emptyToNull(row.get("MOUNTPOINT"));
            Long total = parseLong(row.get("SIZE"));
            Long free = null;
            Long used = null;
            if (mount != null) {
                try {
                    FileStore store = Files.getFileStore(Path.of(mount));
                    total = store.getTotalSpace();
                    free = store.getUsableSpace();
                    used = Math.max(0, total - free);
                } catch (Exception exception) {
                    builder.unavailable.put("storage." + row.get("NAME"),
                            "Cannot read mounted filesystem usage: " + exception.getClass().getSimpleName());
                }
            }
            String media = "disk".equals(type) ? mediaByDisk.get(row.get("NAME"))
                    : mediaByDisk.get(row.get("PKNAME"));
            builder.storage.add(new SystemInformation.StorageDetails("/dev/" + row.get("NAME"),
                    emptyToNull(row.get("MODEL")), mount, emptyToNull(row.get("FSTYPE")),
                    total, used, free, media));
        }
    }

    static void parseLspci(String content, SystemInfoBuilder builder) {
        for (String line : content.split("\\R")) {
            String normalized = line.toLowerCase(Locale.ROOT);
            if (!normalized.contains("vga compatible controller")
                    && !normalized.contains("3d controller")
                    && !normalized.contains("display controller")) continue;
            List<String> fields = new ArrayList<>();
            Matcher matcher = QUOTED_FIELD.matcher(line);
            while (matcher.find()) fields.add(matcher.group(1));
            String vendor = fields.size() > 2 ? fields.get(2) : null;
            String name = fields.size() > 3 ? fields.get(3) : line.trim();
            builder.gpus.add(new SystemInformation.GpuDetails(name, vendor, null, null,
                    classifyGpu(name)));
        }
    }

    private static Map<String, String> parsePairs(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = LSBLK_PAIR.matcher(line);
        while (matcher.find()) values.put(matcher.group(1), matcher.group(2).replace("\\\"", "\""));
        return values;
    }

    private static String media(String rotational) {
        if ("0".equals(rotational)) return "SSD / non-rotational";
        if ("1".equals(rotational)) return "HDD / rotational";
        return null;
    }

    private static String classifyGpu(String name) {
        if (name == null) return null;
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("uhd") || value.contains("iris") || value.contains("integrated")) return "Integrated";
        if (value.contains("geforce") || value.contains("quadro") || value.contains("radeon rx")) return "Discrete";
        return null;
    }

    private static Integer parseInteger(String value) {
        try { return value == null ? null : Integer.valueOf(value.trim()); }
        catch (NumberFormatException exception) { return null; }
    }
    private static Long parseLong(String value) {
        try { return value == null || value.isBlank() ? null : Long.valueOf(value.trim()); }
        catch (NumberFormatException exception) { return null; }
    }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
