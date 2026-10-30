package lanmonitoringserver.monitor.metrics;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetryRingBuffer;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.protocol.ProtocolException;
import lanmonitoringserver.protocol.TelemetryMapper;

public final class MetricsCollectorTest {
    public static void main(String[] args) throws Exception {
        testRingBufferAndProtocol();
        testRealMeasurementsUnderLoad();
        System.out.println("MetricsCollectorTest: PASS");
    }

    private static void testRingBufferAndProtocol() throws Exception {
        TelemetryRingBuffer buffer = new TelemetryRingBuffer(60);
        TelemetrySample sample = sample(System.currentTimeMillis());
        for (int index = 0; index < 80; index++) buffer.add(sample(index));
        check(buffer.size() == 60, "ring buffer must remain bounded");
        check(buffer.snapshot().get(0).timestamp() == 20, "ring buffer discards oldest sample");
        TelemetrySample decoded = TelemetryMapper.fromMap(TelemetryMapper.toMap(sample));
        check(decoded.cpuUsagePercent() == sample.cpuUsagePercent(), "telemetry JSON model round trip");
        check(decoded.diskActivePercent().equals(sample.diskActivePercent()),
                "disk active time remains independent");
        TelemetrySample unsupportedActive = sampleWithUnsupportedDiskActive();
        TelemetrySample unsupportedDecoded = TelemetryMapper.fromMap(
                TelemetryMapper.toMap(unsupportedActive));
        check(unsupportedDecoded.diskStatus() == MeasurementStatus.UNSUPPORTED,
                "unsupported disk active status survives protocol mapping");
        check(unsupportedDecoded.diskActivePercent() == null,
                "capacity must not substitute for unsupported disk active time");
        check(unsupportedDecoded.diskCapacityUsagePercent() == 85d,
                "disk capacity remains available independently");
        try {
            TelemetryMapper.fromMap(Map.of());
            throw new AssertionError("malformed telemetry must be rejected");
        } catch (ProtocolException expected) { }
    }

    private static void testRealMeasurementsUnderLoad() throws Exception {
        try (OshiMetricsCollector collector = new OshiMetricsCollector()) {
            TelemetrySample baseline = collector.collect();
            Thread.sleep(250);
            AtomicBoolean running = new AtomicBoolean(true);
            int workers = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
            Thread[] burners = new Thread[workers];
            for (int i = 0; i < workers; i++) {
                burners[i] = new Thread(() -> {
                    double value = 0;
                    while (running.get()) value += Math.sqrt(value + 17.3);
                }, "real-cpu-load-" + i);
                burners[i].start();
            }
            Thread.sleep(900);
            TelemetrySample loaded = collector.collect();
            running.set(false);
            for (Thread burner : burners) burner.join(2_000);

            byte[] block = new byte[1024 * 1024];
            Path file = Files.createTempFile("lan-monitoring-disk-load-", ".bin");
            try (OutputStream output = Files.newOutputStream(file)) {
                for (int i = 0; i < 16; i++) output.write(block);
            }
            TelemetrySample diskObserved = collector.collect();
            Files.deleteIfExists(file);

            check(loaded.cpuUsagePercent() >= 0 && loaded.cpuUsagePercent() <= 100, "real CPU range");
            check(loaded.logicalProcessors() >= 1, "logical processor count");
            check(loaded.memoryTotalBytes() > 0 && loaded.memoryUsedBytes() + loaded.memoryFreeBytes()
                    == loaded.memoryTotalBytes(), "real RAM accounting");
            check(diskObserved.diskReadBytesPerSecond() >= 0
                    && diskObserved.diskWriteBytesPerSecond() >= 0, "real disk counters");
            check(diskObserved.diskCapacityUsagePercent() == null
                    || diskObserved.diskCapacityUsagePercent() >= 0, "capacity is separately reported");
            check(diskObserved.diskStatus() != MeasurementStatus.UNSUPPORTED
                    || diskObserved.diskActivePercent() == null,
                    "unsupported active time is not fabricated as zero");
            check(loaded.gpuStatus() == MeasurementStatus.OK
                    || loaded.gpuStatus() == MeasurementStatus.UNSUPPORTED
                    || loaded.gpuStatus() == MeasurementStatus.ERROR, "GPU has an explicit status");
            check(loaded.networkInterface() != null, "network interface result");
            System.out.printf("Observed actual metrics: CPU baseline=%.1f%% loaded=%.1f%%, "
                    + "RAM=%.1f%%, disk write=%.2f MB/s, GPU=%s%n",
                    baseline.cpuUsagePercent(), loaded.cpuUsagePercent(), loaded.memoryUsagePercent(),
                    diskObserved.diskWriteBytesPerSecond() / 1048576d, loaded.gpuStatus());
        }
    }

    private static TelemetrySample sample(long timestamp) {
        return new TelemetrySample(timestamp, 1_000, MeasurementStatus.OK, 25, 4, 8, 3200d,
                MeasurementStatus.OK, 50, 4_000, 4_000, 8_000,
                MeasurementStatus.UNSUPPORTED, null, null, null, null, "not supported",
                MeasurementStatus.OK, 12d, 1024, 2048, 70d,
                MeasurementStatus.OK, 512, 1024, "Ethernet", "eth0");
    }

    private static TelemetrySample sampleWithUnsupportedDiskActive() {
        return new TelemetrySample(System.currentTimeMillis(), 1_000,
                MeasurementStatus.OK, 25, 4, 8, 3200d,
                MeasurementStatus.OK, 50, 4_000, 4_000, 8_000,
                MeasurementStatus.UNSUPPORTED, null, null, null, null, "not supported",
                MeasurementStatus.UNSUPPORTED, null, 1024, 2048, 85d,
                MeasurementStatus.OK, 512, 1024, "Ethernet", "eth0");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
