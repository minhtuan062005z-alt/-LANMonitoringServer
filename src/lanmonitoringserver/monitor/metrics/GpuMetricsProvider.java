package lanmonitoringserver.monitor.metrics;

import lanmonitoringserver.model.MeasurementStatus;

public interface GpuMetricsProvider {
    GpuReading read();

    record GpuReading(MeasurementStatus status, Double utilizationPercent,
            Long vramUsedBytes, Long vramTotalBytes, Double temperatureCelsius,
            String detail) {}
}
