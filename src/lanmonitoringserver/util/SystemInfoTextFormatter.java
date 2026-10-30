package lanmonitoringserver.util;

import lanmonitoringserver.model.SystemInformation;

public final class SystemInfoTextFormatter {
    private SystemInfoTextFormatter() {}

    public static String format(SystemInformation info) {
        StringBuilder text = new StringBuilder();
        section(text, "SYSTEM");
        line(text, "Hostname", info.system().hostname());
        line(text, "OS", join(info.system().osName(), info.system().osVersion()));
        line(text, "Architecture", info.system().architecture());
        line(text, "Manufacturer", info.system().manufacturer());
        line(text, "Model", info.system().model());
        line(text, "BIOS/UEFI", join(info.system().firmwareName(), info.system().firmwareVersion()));
        line(text, "Java", info.system().javaVersion());
        section(text, "CPU");
        line(text, "Model", info.cpu().model()); line(text, "Vendor", info.cpu().vendor());
        line(text, "Physical cores", info.cpu().physicalCores());
        line(text, "Logical processors", info.cpu().logicalProcessors());
        line(text, "Max frequency", info.cpu().maxFrequencyMhz() == null
                ? null : info.cpu().maxFrequencyMhz() + " MHz");
        section(text, "MEMORY");
        line(text, "Total", bytes(info.memory().totalBytes()));
        line(text, "Available", bytes(info.memory().availableBytes()));
        section(text, "GPU");
        if (info.gpus().isEmpty()) line(text, "GPU", null);
        for (SystemInformation.GpuDetails gpu : info.gpus()) {
            line(text, "Name", gpu.name()); line(text, "Vendor", gpu.vendor());
            line(text, "Driver", gpu.driver()); line(text, "VRAM", bytes(gpu.vramBytes()));
            line(text, "Category", gpu.category()); text.append('\n');
        }
        section(text, "STORAGE");
        if (info.storage().isEmpty()) line(text, "Storage", null);
        for (SystemInformation.StorageDetails storage : info.storage()) {
            line(text, "Device", storage.device()); line(text, "Model", storage.model());
            line(text, "Mount", storage.mountPoint()); line(text, "Filesystem", storage.fileSystem());
            line(text, "Total", bytes(storage.totalBytes())); line(text, "Used", bytes(storage.usedBytes()));
            line(text, "Free", bytes(storage.freeBytes())); line(text, "Media", storage.mediaType());
            text.append('\n');
        }
        section(text, "NETWORK");
        if (info.network().isEmpty()) line(text, "Network", null);
        for (SystemInformation.NetworkDetails network : info.network()) {
            line(text, "Adapter", join(network.name(), network.displayName()));
            line(text, "Type", network.connectionType()); line(text, "MAC", network.macAddress());
            line(text, "IPv4", String.join(", ", network.ipv4Addresses()));
            line(text, "IPv6", String.join(", ", network.ipv6Addresses())); text.append('\n');
        }
        if (!info.unavailableReasons().isEmpty()) {
            section(text, "UNAVAILABLE FIELDS");
            info.unavailableReasons().forEach((key, reason) -> line(text, key, reason));
        }
        return text.toString();
    }

    private static void section(StringBuilder text, String name) {
        if (text.length() > 0) text.append('\n');
        text.append("=== ").append(name).append(" ===\n");
    }
    private static void line(StringBuilder text, String name, Object value) {
        text.append(name).append(": ").append(value == null || value.toString().isBlank()
                ? "N/A - Not Supported" : value).append('\n');
    }
    private static String join(String first, String second) {
        if (first == null) return second;
        if (second == null) return first;
        return first + " " + second;
    }
    private static String bytes(Long value) {
        if (value == null) return null;
        double gib = value / 1_073_741_824.0;
        return String.format("%.2f GiB (%d bytes)", gib, value);
    }
}
