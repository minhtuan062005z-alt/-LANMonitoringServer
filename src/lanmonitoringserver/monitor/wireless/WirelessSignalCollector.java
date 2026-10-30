package lanmonitoringserver.monitor.wireless;

import lanmonitoringserver.model.WirelessSignalSample;

public interface WirelessSignalCollector extends AutoCloseable {
    WirelessSignalSample collect();
    String collectorName();
    @Override default void close() {}
}
