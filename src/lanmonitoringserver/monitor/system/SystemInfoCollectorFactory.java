package lanmonitoringserver.monitor.system;

import java.util.Locale;

public final class SystemInfoCollectorFactory {
    private SystemInfoCollectorFactory() {}

    public static SystemInfoCollector create() {
        return createFor(System.getProperty("os.name", ""));
    }

    public static SystemInfoCollector createFor(String osName) {
        String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("windows")) return new WindowsSystemInfoCollector();
        if (normalized.contains("linux")) return new LinuxSystemInfoCollector();
        return new GenericSystemInfoCollector();
    }
}
