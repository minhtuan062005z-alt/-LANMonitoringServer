package lanmonitoringserver.monitor.wireless;

import java.util.Locale;

public final class WirelessSignalCollectorFactory {
    private WirelessSignalCollectorFactory() {}

    public static WirelessSignalCollector create() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new WindowsNetshWirelessCollector();
        if (os.contains("linux")) return new LinuxIwWirelessCollector();
        return new UnsupportedWirelessSignalCollector("Unsupported operating system: " + os);
    }
}
