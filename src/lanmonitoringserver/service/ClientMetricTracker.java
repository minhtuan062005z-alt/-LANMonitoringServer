package lanmonitoringserver.service;

import lanmonitoringserver.model.ClientMetrics;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.MetricReading;
import lanmonitoringserver.model.MetricSource;
import lanmonitoringserver.model.TelemetrySample;

/**
 * Merges heartbeat fallbacks and authoritative telemetry without losing provenance.
 * Each source is ordered independently, and fresh telemetry always wins over heartbeat.
 */
final class ClientMetricTracker {
    static final long HEARTBEAT_FRESH_MILLIS = 10_000L;
    private static final long MIN_TELEMETRY_FRESH_MILLIS = 3_000L;

    private Entry cpuTelemetry;
    private Entry ramTelemetry;
    private Entry gpuTelemetry;
    private Entry diskActiveTelemetry;
    private Entry diskCapacityTelemetry;
    private Entry cpuHeartbeat;
    private Entry ramHeartbeat;
    private Entry gpuHeartbeat;
    private Entry diskCapacityHeartbeat;
    private long lastTelemetryTimestamp = -1;

    synchronized void beginSession() {
        cpuTelemetry = null;
        ramTelemetry = null;
        gpuTelemetry = null;
        diskActiveTelemetry = null;
        diskCapacityTelemetry = null;
        cpuHeartbeat = null;
        ramHeartbeat = null;
        gpuHeartbeat = null;
        diskCapacityHeartbeat = null;
        lastTelemetryTimestamp = -1;
    }

    synchronized void acceptHeartbeat(long receivedAt, double cpu, double ram,
            Double gpu, Double diskCapacity) {
        cpuHeartbeat = newer(cpuHeartbeat, reading(cpu, MeasurementStatus.OK,
                receivedAt, MetricSource.HEARTBEAT), receivedAt, HEARTBEAT_FRESH_MILLIS);
        ramHeartbeat = newer(ramHeartbeat, reading(ram, MeasurementStatus.OK,
                receivedAt, MetricSource.HEARTBEAT), receivedAt, HEARTBEAT_FRESH_MILLIS);
        if (gpu != null) {
            gpuHeartbeat = newer(gpuHeartbeat, reading(gpu, MeasurementStatus.OK,
                    receivedAt, MetricSource.HEARTBEAT), receivedAt, HEARTBEAT_FRESH_MILLIS);
        }
        if (diskCapacity != null) {
            diskCapacityHeartbeat = newer(diskCapacityHeartbeat,
                    reading(diskCapacity, MeasurementStatus.OK, receivedAt,
                            MetricSource.HEARTBEAT),
                    receivedAt, HEARTBEAT_FRESH_MILLIS);
        }
    }

    synchronized boolean acceptTelemetry(TelemetrySample sample, long receivedAt) {
        if (sample.timestamp() <= lastTelemetryTimestamp) return false;
        lastTelemetryTimestamp = sample.timestamp();
        long validity = Math.max(MIN_TELEMETRY_FRESH_MILLIS, sample.intervalMillis() * 3L);
        long measuredAt = sample.timestamp();
        cpuTelemetry = newer(cpuTelemetry, reading(sample.cpuUsagePercent(),
                sample.cpuStatus(), measuredAt, MetricSource.TELEMETRY), receivedAt, validity);
        ramTelemetry = newer(ramTelemetry, reading(sample.memoryUsagePercent(),
                sample.memoryStatus(), measuredAt, MetricSource.TELEMETRY), receivedAt, validity);
        gpuTelemetry = newer(gpuTelemetry, reading(sample.gpuUsagePercent(),
                sample.gpuStatus(), measuredAt, MetricSource.TELEMETRY), receivedAt, validity);
        diskActiveTelemetry = newer(diskActiveTelemetry, reading(sample.diskActivePercent(),
                sample.diskStatus(), measuredAt, MetricSource.TELEMETRY), receivedAt, validity);
        MeasurementStatus capacityStatus = sample.diskCapacityUsagePercent() == null
                ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK;
        diskCapacityTelemetry = newer(diskCapacityTelemetry,
                reading(sample.diskCapacityUsagePercent(), capacityStatus,
                        measuredAt, MetricSource.TELEMETRY), receivedAt, validity);
        return true;
    }

    synchronized ClientMetrics snapshot(long now) {
        return new ClientMetrics(
                select(cpuTelemetry, cpuHeartbeat, now),
                select(ramTelemetry, ramHeartbeat, now),
                select(gpuTelemetry, gpuHeartbeat, now),
                select(diskActiveTelemetry, null, now),
                select(diskCapacityTelemetry, diskCapacityHeartbeat, now));
    }

    private static Entry newer(Entry current, MetricReading reading,
            long receivedAt, long validForMillis) {
        if (current != null && reading.measuredAt() < current.reading.measuredAt()) return current;
        return new Entry(reading, receivedAt, validForMillis);
    }

    private static MetricReading select(Entry telemetry, Entry fallback, long now) {
        if (isFresh(telemetry, now)) return telemetry.reading;
        if (isFresh(fallback, now)) return fallback.reading;
        if (telemetry != null) return telemetry.reading.asStale();
        if (fallback != null) return fallback.reading.asStale();
        return MetricReading.unavailable(MeasurementStatus.UNSUPPORTED, 0,
                MetricSource.UNKNOWN);
    }

    private static boolean isFresh(Entry entry, long now) {
        return entry != null && now - entry.receivedAt <= entry.validForMillis;
    }

    private static MetricReading reading(Double value, MeasurementStatus status,
            long measuredAt, MetricSource source) {
        MeasurementStatus normalized = status == null ? MeasurementStatus.ERROR : status;
        if (value == null && normalized == MeasurementStatus.OK) {
            normalized = MeasurementStatus.UNSUPPORTED;
        }
        return new MetricReading(value, normalized, Math.max(0, measuredAt), source);
    }

    private record Entry(MetricReading reading, long receivedAt, long validForMillis) {}
}
