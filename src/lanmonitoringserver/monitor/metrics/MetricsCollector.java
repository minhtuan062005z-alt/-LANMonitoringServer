package lanmonitoringserver.monitor.metrics;

import lanmonitoringserver.model.TelemetrySample;

public interface MetricsCollector extends AutoCloseable {
    TelemetrySample collect();
    String collectorName();
    @Override default void close() {}
}
