package lanmonitoringserver.service;

import lanmonitoringserver.model.AlertThresholds;
import lanmonitoringserver.model.ClientMetrics;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.MetricSource;
import lanmonitoringserver.model.TelemetrySample;

public final class ClientMetricTrackerTest {
    public static void main(String[] args) {
        telemetryIsNotOverwrittenByHeartbeat();
        staleTelemetryFallsBackOnlyWhereARealFallbackExists();
        olderTelemetryCannotOverwriteNewerTelemetry();
        newSessionAcceptsAResetClientClock();
        alertThresholdConfigurationIsIndependentAndStrict();
        System.out.println("ClientMetricTrackerTest: PASS");
    }

    private static void telemetryIsNotOverwrittenByHeartbeat() {
        ClientMetricTracker tracker = new ClientMetricTracker();
        tracker.acceptHeartbeat(1_000, 40, 50, null, 70d);
        tracker.acceptTelemetry(sample(1_100, 20, 30, 60d, 45d, 71d), 1_100);
        tracker.acceptHeartbeat(2_000, 90, 91, null, 80d);

        ClientMetrics metrics = tracker.snapshot(2_000);
        check(metrics.cpuUsage().value() == 20, "fresh telemetry CPU wins");
        check(metrics.ramUsage().value() == 30, "fresh telemetry RAM wins");
        check(metrics.gpuUsage().value() == 60, "heartbeat without GPU cannot erase telemetry GPU");
        check(metrics.diskActive().value() == 45, "disk active remains a telemetry metric");
        check(metrics.diskCapacity().value() == 71, "disk capacity remains distinct from active time");
        check(metrics.gpuUsage().source() == MetricSource.TELEMETRY, "GPU provenance");
    }

    private static void staleTelemetryFallsBackOnlyWhereARealFallbackExists() {
        ClientMetricTracker tracker = new ClientMetricTracker();
        tracker.acceptTelemetry(sample(1_000, 20, 30, 60d, 45d, 71d), 1_000);
        tracker.acceptHeartbeat(2_000, 90, 91, null, 80d);

        ClientMetrics metrics = tracker.snapshot(4_100);
        check(metrics.cpuUsage().value() == 90
                && metrics.cpuUsage().source() == MetricSource.HEARTBEAT,
                "CPU uses a fresh heartbeat fallback");
        check(metrics.ramUsage().value() == 91
                && metrics.ramUsage().source() == MetricSource.HEARTBEAT,
                "RAM uses a fresh heartbeat fallback");
        check(metrics.diskCapacity().value() == 80
                && metrics.diskCapacity().source() == MetricSource.HEARTBEAT,
                "capacity uses a fresh heartbeat fallback");
        check(metrics.gpuUsage().status() == MeasurementStatus.STALE,
                "GPU becomes stale instead of N/A");
        check(metrics.diskActive().status() == MeasurementStatus.STALE,
                "disk active becomes stale and never falls back to capacity");
    }

    private static void olderTelemetryCannotOverwriteNewerTelemetry() {
        ClientMetricTracker tracker = new ClientMetricTracker();
        check(tracker.acceptTelemetry(sample(5_000, 22, 33, 66d, 44d, 72d), 1_000),
                "new telemetry accepted");
        check(!tracker.acceptTelemetry(sample(4_000, 99, 99, 1d, 2d, 3d), 1_100),
                "out-of-order telemetry rejected");
        ClientMetrics metrics = tracker.snapshot(1_100);
        check(metrics.gpuUsage().value() == 66, "out-of-order GPU sample rejected");
        check(metrics.diskActive().value() == 44, "out-of-order disk sample rejected");
    }

    private static void alertThresholdConfigurationIsIndependentAndStrict() {
        AlertThresholds thresholds = AlertThresholds.DEFAULT;
        check(thresholds.cpuPercent() == 80 && thresholds.gpuPercent() == 80
                && thresholds.diskActivePercent() == 80, "default thresholds");
        check(!thresholds.cpuExceeded(80), "exactly 80 percent does not exceed threshold");
        check(thresholds.cpuExceeded(80.1), "value above 80 percent exceeds threshold");
    }

    private static void newSessionAcceptsAResetClientClock() {
        ClientMetricTracker tracker = new ClientMetricTracker();
        tracker.acceptTelemetry(sample(50_000, 10, 20, 30d, 40d, 50d), 1_000);
        tracker.beginSession();
        tracker.acceptTelemetry(sample(1_000, 60, 61, 62d, 63d, 64d), 2_000);
        check(tracker.snapshot(2_000).gpuUsage().value() == 62,
                "new authenticated session accepts reset client timestamps");
    }

    private static TelemetrySample sample(long timestamp, double cpu, double ram,
            Double gpu, Double diskActive, Double diskCapacity) {
        return new TelemetrySample(timestamp, 1_000,
                MeasurementStatus.OK, cpu, 4, 8, 3_200d,
                MeasurementStatus.OK, ram, 4_000, 4_000, 8_000,
                gpu == null ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK,
                gpu, 1_000L, 2_000L, 50d, "test",
                diskActive == null ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK,
                diskActive, 1_024, 2_048, diskCapacity,
                MeasurementStatus.OK, 512, 1_024, "Ethernet", "test");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
