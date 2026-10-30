package lanmonitoringserver.monitor.system;

import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import lanmonitoringserver.model.SystemInformation;

final class JavaSystemProbe {
    private JavaSystemProbe() {}

    static SystemInfoBuilder baseline(String collector) {
        SystemInfoBuilder builder = new SystemInfoBuilder();
        builder.collector = collector;
        builder.hostname = readHostname();
        builder.osName = System.getProperty("os.name", "Unknown");
        builder.osVersion = System.getProperty("os.version", "Unknown");
        builder.architecture = System.getProperty("os.arch", "Unknown");
        builder.javaVersion = System.getProperty("java.runtime.version",
                System.getProperty("java.version", "Unknown"));
        builder.logicalProcessors = Runtime.getRuntime().availableProcessors();
        readMemory(builder);
        builder.storage.addAll(readStorage(builder));
        builder.network.addAll(readNetwork(builder));
        return builder;
    }

    private static String readHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            String environment = System.getenv("COMPUTERNAME");
            if (environment == null || environment.isBlank()) environment = System.getenv("HOSTNAME");
            return environment == null || environment.isBlank() ? "Unknown" : environment;
        }
    }

    private static void readMemory(SystemInfoBuilder builder) {
        try {
            OperatingSystemMXBean bean = java.lang.management.ManagementFactory
                    .getPlatformMXBean(OperatingSystemMXBean.class);
            builder.totalMemoryBytes = positiveOrNull(bean.getTotalMemorySize());
            builder.availableMemoryBytes = positiveOrNull(bean.getFreeMemorySize());
        } catch (RuntimeException exception) {
            builder.unavailable.put("memory", "Java API could not read physical memory: "
                    + exception.getClass().getSimpleName());
        }
    }

    private static List<SystemInformation.StorageDetails> readStorage(SystemInfoBuilder builder) {
        List<SystemInformation.StorageDetails> result = new ArrayList<>();
        File[] roots = File.listRoots();
        if (roots == null) {
            builder.unavailable.put("storage", "Java API returned no filesystem roots");
            return result;
        }
        for (File root : roots) {
            try {
                long total = root.getTotalSpace();
                long free = root.getUsableSpace();
                FileStore store = Files.getFileStore(root.toPath());
                result.add(new SystemInformation.StorageDetails(root.getPath(), null,
                        root.getPath(), store.type(), positiveOrNull(total),
                        total > 0 ? Math.max(0, total - free) : null,
                        positiveOrNull(free), null));
            } catch (Exception exception) {
                builder.unavailable.put("storage." + root.getPath(),
                        "Cannot read filesystem: " + exception.getClass().getSimpleName());
            }
        }
        if (result.isEmpty()) builder.unavailable.putIfAbsent("storage", "No readable filesystem found");
        builder.unavailable.putIfAbsent("storage.mediaType",
                "SSD/HDD type is not exposed by the portable Java filesystem API");
        return result;
    }

    private static List<SystemInformation.NetworkDetails> readNetwork(SystemInfoBuilder builder) {
        List<SystemInformation.NetworkDetails> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                builder.unavailable.put("network", "Java API returned no network interfaces");
                return result;
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                    List<String> ipv4 = new ArrayList<>();
                    List<String> ipv6 = new ArrayList<>();
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        String hostAddress = address.getHostAddress();
                        int scopeIndex = hostAddress.indexOf('%');
                        if (scopeIndex >= 0) hostAddress = hostAddress.substring(0, scopeIndex);
                        if (address instanceof Inet4Address) ipv4.add(hostAddress);
                        else if (address instanceof Inet6Address) ipv6.add(hostAddress);
                    }
                    byte[] hardware = networkInterface.getHardwareAddress();
                    result.add(new SystemInformation.NetworkDetails(networkInterface.getName(),
                            networkInterface.getDisplayName(), connectionType(networkInterface),
                            formatMac(hardware), ipv4, ipv6));
                } catch (Exception exception) {
                    builder.unavailable.put("network." + networkInterface.getName(),
                            "Cannot inspect adapter: " + exception.getClass().getSimpleName());
                }
            }
        } catch (Exception exception) {
            builder.unavailable.put("network", "Cannot enumerate adapters: "
                    + exception.getClass().getSimpleName());
        }
        if (result.isEmpty()) builder.unavailable.putIfAbsent("network", "No active non-loopback adapter found");
        return result;
    }

    private static String connectionType(NetworkInterface networkInterface) {
        String value = (networkInterface.getName() + " " + networkInterface.getDisplayName())
                .toLowerCase(Locale.ROOT);
        if (value.contains("wi-fi") || value.contains("wifi") || value.contains("wireless")
                || value.contains("wlan") || value.startsWith("wl")) return "Wi-Fi";
        if (value.contains("ethernet") || value.startsWith("eth") || value.startsWith("en")) {
            return "Ethernet";
        }
        return "N/A - Not reliably identifiable";
    }

    private static String formatMac(byte[] hardware) {
        if (hardware == null || hardware.length == 0) return null;
        StringBuilder result = new StringBuilder();
        for (byte value : hardware) {
            if (result.length() > 0) result.append('-');
            result.append(String.format("%02X", value & 0xff));
        }
        return result.toString();
    }

    private static Long positiveOrNull(long value) {
        return value > 0 ? value : null;
    }
}
