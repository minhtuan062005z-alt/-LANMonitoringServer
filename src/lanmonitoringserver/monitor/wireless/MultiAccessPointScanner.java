package lanmonitoringserver.monitor.wireless;

import lanmonitoringserver.model.WifiScanSnapshot;

/** Rate-limited collector for a privacy-preserving multi-AP RSSI snapshot. */
public interface MultiAccessPointScanner extends AutoCloseable {
    WifiScanSnapshot scan();
    String scannerName();
    @Override default void close() {}
}
