package lanmonitoringserver.monitor.wireless;

import java.util.Locale;

public final class MultiAccessPointScannerFactory {
    private MultiAccessPointScannerFactory() {}

    public static MultiAccessPointScanner create() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try { return new WindowsNativeWifiMultiApScanner(); }
            catch (RuntimeException | LinkageError error) {
                return new UnsupportedMultiAccessPointScanner("Windows Native Wi-Fi unavailable: "
                        + safe(error));
            }
        }
        if (os.contains("linux")) return new LinuxIwMultiApScanner();
        return new UnsupportedMultiAccessPointScanner("Multi-AP scanning is unsupported on " + os);
    }

    private static String safe(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}
