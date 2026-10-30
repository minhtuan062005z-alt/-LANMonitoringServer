package lanmonitoringserver.monitor.wireless;

import java.util.List;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WifiScanSnapshot;

public final class UnsupportedMultiAccessPointScanner implements MultiAccessPointScanner {
    private final String detail;
    public UnsupportedMultiAccessPointScanner(String detail) {
        this.detail = detail == null ? "Multi-AP scanning is unavailable" : detail;
    }
    @Override public WifiScanSnapshot scan() {
        return new WifiScanSnapshot(System.currentTimeMillis(), interval(),
                MeasurementStatus.UNSUPPORTED, "", List.of(), scannerName(), detail);
    }
    @Override public String scannerName() { return "No multi-AP scanner"; }
    private static long interval() {
        return Math.max(5_000L, Math.min(300_000L,
                Long.getLong("lanmonitoring.fingerprint.interval.ms", 10_000L)));
    }
}
