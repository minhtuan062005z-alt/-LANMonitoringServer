package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ServerSocketFactory;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.security.InMemoryCredentialRegistry;

public final class AlertEngineTest {
    public static void main(String[] args) throws Exception {
        sustainedThresholdAndRecovery();
        staleGapBreaksPendingDuration();
        thresholdsAreStrictlyGreaterThanDefaults();
        cooldownPreventsImmediateRepeat();
        unsupportedAndStaleMetricsAreIgnored();
        staleOrUnsupportedDoesNotResolveAnActiveAlert();
        diskActiveAndCapacityRulesRemainIndependent();
        disconnectedClientKeepsActiveAlert();
        deliveryAckIsBoundToClient();
        administratorAcknowledgeAndResolveHaveDistinctStates();
        criticalEscalationCreatesOnlyOneAdditionalNotification();
        automaticRamCycleIsContinuousIndependentAndRepeatable();
        manualTemplatePersistencePreservesGeneratedText();
        persistenceRoundTrip();
        serverLifecyclePersistsAcrossRestart();
        persistenceFailureModesAreSafe();
        System.out.println("AlertEngineTest: PASS");
    }

    private static void thresholdsAreStrictlyGreaterThanDefaults() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 1_000));
        check(engine.evaluate("exact-warning", "PC-80",
                sample(1_000, 80, null, null, 40), 1_000).notifications().isEmpty(),
                "exactly 80 percent does not cross the warning threshold");
        AlertRecord warning = only(engine.evaluate("exact-critical", "PC-90",
                sample(2_000, 90, null, null, 40), 2_000).notifications());
        check(warning.severity() == lanmonitoringserver.model.AlertSeverity.WARNING,
                "exactly 90 percent is warning, not critical");
        AlertRecord critical = only(engine.evaluate("above-critical", "PC-91",
                sample(3_000, 90.1, null, null, 40), 3_000).notifications());
        check(critical.severity() == lanmonitoringserver.model.AlertSeverity.CRITICAL,
                "a value greater than 90 percent is critical");
    }

    private static void staleGapBreaksPendingDuration() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 5_000, 5, 1_000));
        engine.evaluate("gap", "PC-GAP", sample(1_000, 85, null, null, 40), 1_000);
        engine.evaluate("gap", "PC-GAP", sample(1_000, 85, null, null, 40), 10_000);
        check(engine.evaluate("gap", "PC-GAP", sample(10_000, 85, null, null, 40), 10_000)
                .notifications().isEmpty(),
                "stale telemetry gap resets pending continuous duration");
        check(engine.evaluate("gap", "PC-GAP", sample(15_000, 85, null, null, 40), 15_000)
                .notifications().size() == 1,
                "duration restarts from the first fresh sample after a gap");
    }

    private static void sustainedThresholdAndRecovery() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 5_000, 5, 10_000));
        check(engine.evaluate("c1", "PC-1", sample(1_000, 85, null, null, 40), 1_000)
                .notifications().isEmpty(), "short spike does not alert");
        check(engine.evaluate("c1", "PC-1", sample(5_999, 85, null, null, 40), 5_999)
                .notifications().isEmpty(), "duration not reached");
        AlertRecord alert = only(engine.evaluate("c1", "PC-1",
                sample(6_000, 85, null, null, 40), 6_000).notifications());
        check(alert.status() == AlertStatus.ACTIVE, "alert becomes active");
        check(engine.evaluate("c1", "PC-1", sample(7_000, 78, null, null, 40), 7_000)
                .changed().isEmpty(), "hysteresis keeps alert active");
        AlertRecord resolved = only(engine.evaluate("c1", "PC-1",
                sample(8_000, 75, null, null, 40), 8_000).changed());
        check(resolved.status() == AlertStatus.RESOLVED, "recovery resolves alert");
    }

    private static void cooldownPreventsImmediateRepeat() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 10_000));
        AlertRecord first = only(engine.evaluate("c1", "PC-1",
                sample(1_000, 85, null, null, 40), 1_000).notifications());
        engine.evaluate("c1", "PC-1", sample(2_000, 70, null, null, 40), 2_000);
        check(engine.evaluate("c1", "PC-1", sample(3_000, 85, null, null, 40), 3_000)
                .notifications().isEmpty(), "cooldown suppresses repeat");
        AlertRecord second = only(engine.evaluate("c1", "PC-1",
                sample(12_001, 85, null, null, 40), 12_001).notifications());
        check(!first.id().equals(second.id()), "new lifecycle after cooldown");
    }

    private static void unsupportedAndStaleMetricsAreIgnored() {
        AlertEngine engine = new AlertEngine();
        check(engine.evaluate("c1", "PC-1", sample(1_000, 20, null, null, 95), 1_000)
                .notifications().isEmpty(), "unsupported GPU/disk active ignored");
        check(engine.evaluate("c1", "PC-1", sample(1_000, 99, 99d, 99d, 99), 10_000)
                .notifications().isEmpty(), "stale sample ignored");
    }

    private static void staleOrUnsupportedDoesNotResolveAnActiveAlert() {
        AlertEngine cpuEngine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 1_000));
        cpuEngine.evaluate("c1", "PC-1", sample(1_000, 95, null, null, 40), 1_000);
        check(cpuEngine.evaluate("c1", "PC-1", sample(1_000, 20, null, null, 40), 10_000)
                .changed().isEmpty(), "stale telemetry does not resolve an active alert");
        check(cpuEngine.summary("c1").activeCount() == 1,
                "active alert remains open when telemetry becomes stale");

        AlertEngine gpuEngine = engine(rule(AlertMetric.GPU, 80, 90, 0, 5, 1_000));
        gpuEngine.evaluate("c2", "PC-2", sample(2_000, 20, 95d, null, 40), 2_000);
        check(gpuEngine.evaluate("c2", "PC-2", sample(3_000, 20, null, null, 40), 3_000)
                .changed().isEmpty(), "unsupported metric does not resolve an active alert");
        check(gpuEngine.summary("c2").activeCount() == 1,
                "active alert remains open when metric becomes unsupported");
    }

    private static void disconnectedClientKeepsActiveAlert() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 10_000));
        engine.evaluate("c1", "PC-1", sample(1_000, 95, null, null, 40), 1_000);
        // No fabricated recovery sample is fed when the client disconnects.
        check(engine.summary("c1").activeCount() == 1, "disconnect does not auto-resolve alert");
    }

    private static void diskActiveAndCapacityRulesRemainIndependent() {
        AlertEngine engine = new AlertEngine(List.of(
                rule(AlertMetric.DISK_ACTIVE, 80, 90, 0, 5, 1_000),
                rule(AlertMetric.DISK_CAPACITY, 80, 90, 0, 5, 1_000)), List.of());
        List<AlertRecord> capacityOnly = engine.evaluate("c1", "PC-1",
                sample(1_000, 20, null, 70d, 95), 1_000).notifications();
        check(capacityOnly.size() == 1 && capacityOnly.get(0).metric() == AlertMetric.DISK_CAPACITY,
                "capacity threshold does not become disk active alert");
        List<AlertRecord> activeOnly = engine.evaluate("c2", "PC-2",
                sample(2_000, 20, null, 95d, 70), 2_000).notifications();
        check(activeOnly.size() == 1 && activeOnly.get(0).metric() == AlertMetric.DISK_ACTIVE,
                "disk active threshold is evaluated independently");
    }

    private static void deliveryAckIsBoundToClient() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 10_000));
        AlertRecord alert = only(engine.evaluate("c1", "PC-1",
                sample(1_000, 95, null, null, 40), 1_000).notifications());
        check(engine.updateDelivery(alert.id(), "another-client",
                AlertDeliveryStatus.ACKNOWLEDGED, "request", 2_000) == null,
                "another client cannot ACK alert");
        AlertRecord acknowledged = engine.updateDelivery(alert.id(), "c1",
                AlertDeliveryStatus.ACKNOWLEDGED, "request", 2_000);
        check(acknowledged.deliveryStatus() == AlertDeliveryStatus.ACKNOWLEDGED,
                "delivery ACK recorded");
        check(acknowledged.status() == AlertStatus.ACTIVE,
                "delivery ACK does not perform administrator acknowledgement");
        check(acknowledged.updatedAt() == alert.updatedAt(),
                "delivery retry/ACK preserves stable occurrence time for reconnect deduplication");
    }

    private static void administratorAcknowledgeAndResolveHaveDistinctStates() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 10_000));
        AlertRecord alert = only(engine.evaluate("c1", "PC-1",
                sample(1_000, 95, null, null, 40), 1_000).notifications());
        check(alert.severity() == lanmonitoringserver.model.AlertSeverity.CRITICAL,
                "critical threshold creates critical severity");
        AlertRecord acknowledged = engine.acknowledge(alert.id(), 2_000);
        check(acknowledged.status() == AlertStatus.ACKNOWLEDGED,
                "admin acknowledge keeps alert open");
        check(engine.summary("c1").activeCount() == 1, "acknowledged alert remains current");
        AlertRecord resolved = engine.resolve(alert.id(), 3_000);
        check(resolved.status() == AlertStatus.RESOLVED, "admin resolve closes alert");
        check(engine.summary("c1").activeCount() == 0, "resolved alert leaves current list");
    }

    private static void criticalEscalationCreatesOnlyOneAdditionalNotification() {
        AlertEngine engine = engine(rule(AlertMetric.CPU, 80, 90, 0, 5, 10_000));
        AlertRecord warningAlert = only(engine.evaluate("c1", "PC-1",
                sample(1_000, 85, null, null, 40), 1_000).notifications());
        AlertRecord criticalAlert = only(engine.evaluate("c1", "PC-1",
                sample(2_000, 95, null, null, 40), 2_000).notifications());
        check(warningAlert.id().equals(criticalAlert.id()), "escalation keeps one lifecycle");
        check(criticalAlert.deliveryStatus() == AlertDeliveryStatus.PENDING,
                "escalation requires a new delivery ACK");
        check(engine.evaluate("c1", "PC-1", sample(3_000, 96, null, null, 40), 3_000)
                .notifications().isEmpty(), "continued critical samples do not spam notifications");
    }

    private static void automaticRamCycleIsContinuousIndependentAndRepeatable() {
        AlertEngine engine = new AlertEngine();
        check(engine.evaluate("ram-a", "RAM-A", ramSample(1_000, 91), 1_000)
                .notifications().isEmpty(), "RAM spike does not alert immediately");
        check(engine.evaluate("ram-b", "RAM-B", ramSample(30_000, 95), 30_000)
                .notifications().isEmpty(), "RAM state is independent per Client");
        check(engine.evaluate("ram-a", "RAM-A", ramSample(60_999, 92), 60_999)
                .notifications().isEmpty(), "RAM requires a full continuous 60 seconds");
        AlertRecord first = only(engine.evaluate("ram-a", "RAM-A", ramSample(61_000, 92), 61_000)
                .notifications());
        check(first.metric() == AlertMetric.RAM && first.automaticRamAlert(),
                "sustained RAM creates an automatic RAM alert");
        check(engine.evaluate("ram-a", "RAM-A", ramSample(120_999, 93), 120_999)
                .notifications().isEmpty(), "RAM reminder is not early");
        AlertRecord reminder = only(engine.evaluate("ram-a", "RAM-A", ramSample(121_000, 93), 121_000)
                .notifications());
        check(reminder.id().equals(first.id()), "RAM reminder keeps one alert lifecycle");
        check(engine.evaluate("ram-a", "RAM-A", ramSample(181_999, 80), 181_999)
                .changed().isEmpty(), "RAM recovery also requires 60 continuous seconds");
        AlertRecord resolved = only(engine.evaluate("ram-a", "RAM-A", ramSample(241_999, 80), 241_999)
                .changed());
        check(resolved.status() == AlertStatus.RESOLVED, "stable RAM recovery resets the cycle");
        check(engine.evaluate("ram-a", "RAM-A", ramSample(242_000, 99), 242_000)
                .notifications().isEmpty(), "new RAM cycle again waits 60 seconds");
        check(engine.evaluate("ram-b", "RAM-B", ramSample(90_000, 95), 90_000)
                .notifications().size() == 1, "second Client reaches its own 60-second deadline");
    }

    private static void manualTemplatePersistencePreservesGeneratedText() throws Exception {
        Path directory = Files.createTempDirectory("lan-template-alert-");
        Path file = directory.resolve("alerts.json");
        try {
            var reading = lanmonitoringserver.model.MetricReading.ok(87.5, 1_000,
                    lanmonitoringserver.model.MetricSource.TELEMETRY);
            AlertEngine engine = new AlertEngine();
            AlertRecord created = engine.createManualNotification("c1", "PC-1",
                    lanmonitoringserver.model.NotificationTemplate.CPU_HIGH, reading, 1_000);
            FileAlertStore store = new FileAlertStore(file);
            store.save(new AlertStoreSnapshot(engine.rules(), engine.alerts()));
            AlertRecord loaded = store.load().alerts().get(0);
            check(loaded.manualNotification() && loaded.title().equals(created.title())
                            && loaded.content().contains("87.5%"),
                    "manual template title/content and real metric persist");
            String unavailable = lanmonitoringserver.model.NotificationTemplate.GPU_HIGH
                    .content("PC-1", lanmonitoringserver.model.MetricReading.unavailable(
                            MeasurementStatus.UNSUPPORTED, 0,
                            lanmonitoringserver.model.MetricSource.UNKNOWN));
            check(unavailable.contains("không khả dụng") && !unavailable.matches(".*\\d+[.,]\\d+%.*"),
                    "unavailable metric never fabricates a percentage");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void persistenceRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("lan-alert-store-test-");
        Path file = directory.resolve("alerts.json");
        try {
            AlertRule savedRule = rule(AlertMetric.DISK_CAPACITY, 70, 95, 2_000, 4, 9_000);
            AlertRecord savedAlert = new AlertRecord("a1", "c1", "PC-1",
                    AlertMetric.DISK_CAPACITY, lanmonitoringserver.model.AlertSeverity.WARNING,
                    81, 70, AlertStatus.ACKNOWLEDGED, AlertDeliveryStatus.ACKNOWLEDGED,
                    1_000, 2_000L, null, 2_000, "request-1");
            FileAlertStore store = new FileAlertStore(file);
            store.save(new AlertStoreSnapshot(List.of(savedRule), List.of(savedAlert)));
            AlertStoreSnapshot loaded = store.load();
            check(loaded.rules().equals(List.of(savedRule)), "rules persist");
            check(loaded.alerts().equals(List.of(savedAlert)), "alert history persists");
            AlertEngine restored = new AlertEngine(loaded.rules(), loaded.alerts());
            check(restored.summary("c1").activeCount() == 1,
                    "acknowledged active alert survives server restart");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void persistenceFailureModesAreSafe() throws Exception {
        Path directory = Files.createTempDirectory("lan-alert-store-failure-test-");
        Path missing = directory.resolve("missing.json");
        Path invalid = directory.resolve("invalid.json");
        Path parentFile = directory.resolve("not-a-directory");
        try {
            check(new FileAlertStore(missing).load().equals(AlertStoreSnapshot.empty()),
                    "missing alert store starts empty");

            String invalidContent = "{\"version\":1,\"rules\":[";
            Files.writeString(invalid, invalidContent, StandardCharsets.UTF_8);
            expectIOException(() -> new FileAlertStore(invalid).load(),
                    "malformed alert store is rejected");
            check(Files.readString(invalid, StandardCharsets.UTF_8).equals(invalidContent),
                    "malformed alert store is not modified by load");

            Files.writeString(invalid, "{\"version\":2,\"rules\":[],\"alerts\":[]}",
                    StandardCharsets.UTF_8);
            expectIOException(() -> new FileAlertStore(invalid).load(),
                    "unsupported alert store version is rejected");

            Files.writeString(parentFile, "blocks-directory-creation", StandardCharsets.UTF_8);
            expectIOException(() -> new FileAlertStore(parentFile.resolve("alerts.json"))
                    .save(AlertStoreSnapshot.empty()),
                    "unwritable alert store path reports an I/O error");

            AtomicInteger saves = new AtomicInteger();
            AlertStore corruptStore = new AlertStore() {
                @Override public AlertStoreSnapshot load() throws IOException {
                    throw new IOException("fixture is corrupt");
                }
                @Override public void save(AlertStoreSnapshot snapshot) { saves.incrementAndGet(); }
            };
            try (LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                    ServerSocketFactory.getDefault(), corruptStore)) {
                check(!server.isAlertPersistenceAvailable(),
                        "server marks persistence unavailable after load failure");
                server.updateAlertRule(rule(AlertMetric.CPU, 75, 90, 0, 5, 1_000));
            }
            check(saves.get() == 0,
                    "server never overwrites a store that failed to load");

            AlertStore unwritableStore = new AlertStore() {
                @Override public AlertStoreSnapshot load() { return AlertStoreSnapshot.empty(); }
                @Override public void save(AlertStoreSnapshot snapshot) throws IOException {
                    throw new IOException("write denied");
                }
            };
            try (LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                    ServerSocketFactory.getDefault(), unwritableStore)) {
                server.updateAlertRule(rule(AlertMetric.CPU, 75, 90, 0, 5, 1_000));
                long deadline = System.currentTimeMillis() + 2_000;
                while (server.isAlertPersistenceAvailable() && System.currentTimeMillis() < deadline) {
                    Thread.onSpinWait();
                }
                check(!server.isAlertPersistenceAvailable(),
                        "server exposes asynchronous save failure without stopping alert logic");
                check("write denied".equals(server.alertPersistenceError()),
                        "server retains persistence diagnostic");
            }
        } finally {
            Files.deleteIfExists(invalid);
            Files.deleteIfExists(parentFile);
            Files.deleteIfExists(missing);
            Files.deleteIfExists(directory);
        }
    }

    private static void serverLifecyclePersistsAcrossRestart() throws Exception {
        Path directory = Files.createTempDirectory("lan-alert-server-restart-test-");
        Path file = directory.resolve("alerts.json");
        AlertRule savedRule = rule(AlertMetric.CPU, 77, 93, 3_000, 4, 8_000);
        AlertRecord active = new AlertRecord("restart-alert", "restart-client", "RESTART-PC",
                AlertMetric.CPU, lanmonitoringserver.model.AlertSeverity.WARNING,
                82, 77, AlertStatus.ACTIVE, AlertDeliveryStatus.ACKNOWLEDGED,
                1_000, null, null, 1_000, "delivery-request");
        try {
            FileAlertStore store = new FileAlertStore(file);
            store.save(new AlertStoreSnapshot(List.of(savedRule), List.of(active)));

            try (LanServerService first = new LanServerService(new InMemoryCredentialRegistry(),
                    ServerSocketFactory.getDefault(), new FileAlertStore(file))) {
                check(first.alertRules().contains(savedRule), "server restores rule after restart");
                check(first.alerts().size() == 1 && first.alerts().get(0).status() == AlertStatus.ACTIVE,
                        "server restores open alert after restart");
                check(first.acknowledgeAlert("restart-alert"),
                        "administrator acknowledgement succeeds after restore");
            }

            try (LanServerService second = new LanServerService(new InMemoryCredentialRegistry(),
                    ServerSocketFactory.getDefault(), new FileAlertStore(file))) {
                check(second.alerts().get(0).status() == AlertStatus.ACKNOWLEDGED,
                        "administrator acknowledgement persists across restart");
                check(second.resolveAlert("restart-alert"),
                        "restored alert can be resolved");
            }

            try (LanServerService third = new LanServerService(new InMemoryCredentialRegistry(),
                    ServerSocketFactory.getDefault(), new FileAlertStore(file))) {
                check(third.alerts().get(0).status() == AlertStatus.RESOLVED,
                        "resolved history persists across a second restart");
                check(third.alertRules().contains(savedRule),
                        "configured rule persists across repeated restarts");
            }
        } finally {
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void expectIOException(IoAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Expected failure path.
        }
    }

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }

    private static AlertEngine engine(AlertRule rule) {
        return new AlertEngine(List.of(rule), List.of());
    }

    private static AlertRule rule(AlertMetric metric, double warning, double critical,
            long duration, double hysteresis, long cooldown) {
        return new AlertRule(metric, true, warning, critical, duration, hysteresis, cooldown);
    }

    private static TelemetrySample sample(long timestamp, double cpu, Double gpu,
            Double diskActive, double diskCapacity) {
        return new TelemetrySample(timestamp, 1_000, MeasurementStatus.OK, cpu, 4, 8, null,
                MeasurementStatus.OK, 40, 4_000, 6_000, 10_000,
                gpu == null ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK,
                gpu, null, null, null, "test",
                diskActive == null ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK,
                diskActive, 0, 0, diskCapacity,
                MeasurementStatus.OK, 0, 0, "test", "test");
    }

    private static TelemetrySample ramSample(long timestamp, double ram) {
        return new TelemetrySample(timestamp, 1_000, MeasurementStatus.OK, 20, 4, 8, null,
                MeasurementStatus.OK, ram, 9_100, 900, 10_000,
                MeasurementStatus.UNSUPPORTED, null, null, null, null, "test",
                MeasurementStatus.UNSUPPORTED, null, 0, 0, 40d,
                MeasurementStatus.OK, 0, 0, "test", "test");
    }

    private static AlertRecord only(List<AlertRecord> values) {
        check(values.size() == 1, "expected exactly one alert change, got " + values.size());
        return values.get(0);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
