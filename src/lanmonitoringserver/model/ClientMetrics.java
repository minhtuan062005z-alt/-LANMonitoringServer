package lanmonitoringserver.model;

/** Explicit dashboard metrics. Disk activity and disk capacity are never interchangeable. */
public record ClientMetrics(
        MetricReading cpuUsage,
        MetricReading ramUsage,
        MetricReading gpuUsage,
        MetricReading diskActive,
        MetricReading diskCapacity) {

    public ClientMetrics {
        if (cpuUsage == null || ramUsage == null || gpuUsage == null
                || diskActive == null || diskCapacity == null) {
            throw new IllegalArgumentException("Client metric readings must not be null");
        }
    }

    public static ClientMetrics legacy(double cpu, double ram, Double gpu,
            Double diskCapacity, long measuredAt) {
        return new ClientMetrics(
                MetricReading.ok(cpu, measuredAt, MetricSource.LEGACY),
                MetricReading.ok(ram, measuredAt, MetricSource.LEGACY),
                optional(gpu, measuredAt),
                MetricReading.unavailable(MeasurementStatus.UNSUPPORTED,
                        measuredAt, MetricSource.LEGACY),
                optional(diskCapacity, measuredAt));
    }

    private static MetricReading optional(Double value, long measuredAt) {
        return value == null
                ? MetricReading.unavailable(MeasurementStatus.UNSUPPORTED,
                        measuredAt, MetricSource.LEGACY)
                : MetricReading.ok(value, measuredAt, MetricSource.LEGACY);
    }
}
