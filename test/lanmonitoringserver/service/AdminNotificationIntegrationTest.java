package lanmonitoringserver.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.net.SocketFactory;
import lanmonitoringserver.model.AlertNotification;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.NotificationTemplate;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.monitor.LocalSystemMonitor;
import lanmonitoringserver.monitor.metrics.MetricsCollector;
import lanmonitoringserver.monitor.system.GenericSystemInfoCollector;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.InMemoryCredentialRegistry;

/** Multi-client template, permission, deduplication and ALERT_ACK integration. */
public final class AdminNotificationIntegrationTest {
    private AdminNotificationIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lan-admin-notification-");
        ClientPermissionStore permissions = new ClientPermissionStore(root.resolve("permissions.json"));
        List<ClientCredentials> identities = List.of(ClientCredentials.generate(),
                ClientCredentials.generate(), ClientCredentials.generate());
        permissions.put(identities.get(0).clientId(), new ClientPermissions(false, true, true));
        permissions.put(identities.get(1).clientId(), new ClientPermissions(false, true, true));
        permissions.put(identities.get(2).clientId(), new ClientPermissions(false, false, true));
        AuthorizationContext admin = new AuthorizationContext("admin", UserRole.ADMIN, Set.of());
        LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                javax.net.ServerSocketFactory.getDefault(), new InMemoryAlertStore(), permissions,
                new AuditLogger(root.resolve("audit.jsonl")), admin);
        List<LanClientService> clients = new ArrayList<>();
        List<AlertNotification> received = new CopyOnWriteArrayList<>();
        int port = server.start(0);
        try {
            for (int index = 0; index < identities.size(); index++) {
                LanClientService client = new LanClientService(identities.get(index),
                        new LocalSystemMonitor(), SocketFactory.getDefault(),
                        new GenericSystemInfoCollector(), fixedMetrics());
                client.setListener(new ClientEventListener() {
                    @Override public void onAlert(AlertNotification alert) { received.add(alert); }
                });
                clients.add(client);
                client.connect("127.0.0.1", port, "NOTICE-PC-" + (index + 1), "127.0.0.1");
            }
            await(Duration.ofSeconds(10), () -> server.clients().stream()
                    .filter(value -> value.status() == ClientStatus.ONLINE).count() == 3,
                    "three notification Clients online");
            await(Duration.ofSeconds(10), () -> server.telemetryHistory(
                    identities.get(0).clientId()).size() > 0, "fresh metrics available");

            Set<String> all = Set.of(identities.get(0).clientId(), identities.get(1).clientId(),
                    identities.get(2).clientId());
            LanServerService.NotificationSendResult sent = server.sendAdminNotification(
                    NotificationTemplate.CPU_HIGH, all);
            check(sent.created().size() == 2, "manual template is created for two permitted Clients");
            check(sent.rejected().get(identities.get(2).clientId()).contains("Admin alerts=false"),
                    "permission-disabled Client is explicitly rejected");
            await(Duration.ofSeconds(5), () -> received.size() == 2,
                    "two permitted Clients receive their own popup event");
            check(received.stream().allMatch(value -> value.templated()
                            && value.content().contains("73.5%") && !value.mandatory()),
                    "template uses each Client's real latest CPU metric");
            await(Duration.ofSeconds(5), () -> sent.created().stream().allMatch(created ->
                            server.alerts().stream().anyMatch(alert -> alert.id().equals(created.id())
                                    && alert.status() == AlertStatus.RESOLVED)),
                    "ALERT_ACK is tracked per Client and closes manual notification");

            LanServerService.NotificationSendResult duplicate = server.sendAdminNotification(
                    NotificationTemplate.CPU_HIGH, all);
            check(duplicate.created().isEmpty()
                            && duplicate.rejected().values().stream().anyMatch(value -> value.contains("Duplicate")),
                    "rapid repeated Send is suppressed per template and Client");
            check(received.size() == 2, "duplicate Send creates no extra popup");

            authorizationDenied(root, identities.get(0).clientId());
            System.out.println("AdminNotificationIntegrationTest: PASS");
        } finally {
            clients.forEach(LanClientService::close);
            server.close();
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void authorizationDenied(Path root, String clientId) throws Exception {
        AuthorizationContext viewer = new AuthorizationContext("viewer", UserRole.VIEWER, Set.of(clientId));
        try (LanServerService service = new LanServerService(new InMemoryCredentialRegistry(),
                javax.net.ServerSocketFactory.getDefault(), new InMemoryAlertStore(),
                new ClientPermissionStore(root.resolve("viewer-permissions.json")),
                new AuditLogger(root.resolve("viewer-audit.jsonl")), viewer)) {
            try {
                service.sendAdminNotification(NotificationTemplate.GENERAL, Set.of(clientId));
                throw new AssertionError("VIEWER must not send administrative notifications");
            } catch (SecurityException expected) {
                check(expected.getMessage().contains("ADMIN"), "RBAC denial is explicit");
            }
        }
    }

    private static MetricsCollector fixedMetrics() {
        return new MetricsCollector() {
            @Override public TelemetrySample collect() {
                long now = System.currentTimeMillis();
                return new TelemetrySample(now, 1_000, MeasurementStatus.OK, 73.5, 2, 4, null,
                        MeasurementStatus.OK, 20, 2, 8, 10,
                        MeasurementStatus.UNSUPPORTED, null, null, null, null, "test",
                        MeasurementStatus.UNSUPPORTED, null, 0, 0, 55d,
                        MeasurementStatus.OK, 0, 0, "test", "test");
            }
            @Override public String collectorName() { return "NotificationFixedMetrics"; }
        };
    }

    private static void await(Duration timeout, BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
