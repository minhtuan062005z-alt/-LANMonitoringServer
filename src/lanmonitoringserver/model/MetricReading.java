package lanmonitoringserver.model;

/** A percentage metric together with its availability, capture time and source. */
public record MetricReading(Double value, MeasurementStatus status,
        long measuredAt, MetricSource source) {

    public MetricReading {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (measuredAt < 0) throw new IllegalArgumentException("measuredAt must not be negative");
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 100)) {
            throw new IllegalArgumentException("percentage value must be between 0 and 100");
        }
        if (status == MeasurementStatus.OK && value == null) {
            throw new IllegalArgumentException("OK metric must contain a value");
        }
    }

    public static MetricReading ok(double value, long measuredAt, MetricSource source) {
        return new MetricReading(value, MeasurementStatus.OK, measuredAt, source);
    }

    public static MetricReading unavailable(MeasurementStatus status, long measuredAt,
            MetricSource source) {
        if (status == MeasurementStatus.OK) {
            throw new IllegalArgumentException("Unavailable metric cannot have OK status");
        }
        return new MetricReading(null, status, measuredAt, source);
    }

    public MetricReading asStale() {
        return new MetricReading(value, MeasurementStatus.STALE, measuredAt, source);
    }

    public boolean isCurrentValue() {
        return status == MeasurementStatus.OK && value != null;
    }
}
