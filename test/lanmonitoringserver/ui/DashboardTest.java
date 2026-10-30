package lanmonitoringserver.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.model.ClientMetrics;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.MetricReading;
import lanmonitoringserver.model.MetricSource;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertSeverity;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.monitor.system.GenericSystemInfoCollector;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.FileTransferStatus;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.model.AnsiblePlatform;
import lanmonitoringserver.model.AnsibleConnectionType;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.model.DistanceDataSource;
import lanmonitoringserver.model.ServerClientDistance;
import lanmonitoringserver.model.ServerClientDistanceMethod;
import lanmonitoringserver.model.ServerClientDistanceStatus;

public final class DashboardTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicReference<ClientDashboardPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new ClientDashboardPanel()));
        ClientDashboardPanel panel = panelReference.get();

        SwingUtilities.invokeAndWait(() -> testAddUpdateFilterAndRemove(panel));
        SwingUtilities.invokeAndWait(DashboardTest::testClientDetailTabs);
        SwingUtilities.invokeAndWait(DashboardTest::testMetricStatusRendering);
        SwingUtilities.invokeAndWait(DashboardTest::testServerClientDistanceRendering);
        SwingUtilities.invokeAndWait(DashboardTest::testAlertCardPriority);
        SwingUtilities.invokeAndWait(DashboardTest::testAlertManagementTabs);
        SwingUtilities.invokeAndWait(DashboardTest::testFileTransferPanels);
        SwingUtilities.invokeAndWait(DashboardTest::testAnsibleManagementPanel);
        testHighVolumeUpdatesStayAsynchronous(panel);
        System.out.println("DashboardTest: PASS");
    }

    private static void testAddUpdateFilterAndRemove(ClientDashboardPanel panel) {
        long now = System.currentTimeMillis();
        ClientSnapshot online = snapshot("id-1", "ALPHA-PC", ClientStatus.ONLINE,
                22, 45, null, 51.0, 12L, now);
        ClientSnapshot warning = snapshot("id-2", "BETA-PC", ClientStatus.ONLINE,
                82, 60, 33.0, 70.0, 24L, now);
        ClientSnapshot offline = snapshot("id-3", "GAMMA-PC", ClientStatus.OFFLINE,
                0, 0, null, null, null, now - 5_000);
        panel.updateClients(List.of(online, warning, offline));
        panel.flushPendingUpdates();

        check(panel.cardCount() == 3, "three cards are added");
        check(panel.onlineCount() == 2, "online summary");
        check(panel.offlineCount() == 1, "offline summary");
        check(panel.alertCount() == 1, "warning summary");
        ClientCard originalCard = panel.card("id-1");

        ClientSnapshot disconnected = snapshot("id-1", "ALPHA-PC", ClientStatus.OFFLINE,
                22, 45, null, 51.0, 12L, now + 1_000);
        panel.updateClients(List.of(disconnected));
        panel.flushPendingUpdates();
        check(panel.card("id-1") == originalCard, "status update reuses the same card");
        check(originalCard.health() == ClientHealthLevel.OFFLINE, "card changes to offline");

        ClientSnapshot reconnected = snapshot("id-1", "ALPHA-PC", ClientStatus.ONLINE,
                91, 45, null, 51.0, 15L, now + 2_000);
        panel.updateClients(List.of(reconnected));
        panel.flushPendingUpdates();
        check(panel.card("id-1") == originalCard, "reconnect does not duplicate card");
        check(originalCard.health() == ClientHealthLevel.CRITICAL, "critical color state");

        panel.setSearchText("beta");
        check(panel.visibleCardCount() == 1, "search by hostname");
        panel.setSearchText("");
        panel.setFilter("Offline");
        check(panel.visibleCardCount() == 1, "offline filter");
        panel.setFilter("Tất cả");

        panel.card("id-1").setSelected(true);
        panel.card("id-2").setSelected(true);
        check(panel.selectedClientIds().size() == 2, "multi-client selection");

        panel.removeClient("id-3");
        check(panel.cardCount() == 2, "explicit card removal");
    }

    private static void testHighVolumeUpdatesStayAsynchronous(ClientDashboardPanel panel)
            throws Exception {
        List<ClientSnapshot> manyClients = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int index = 0; index < 75; index++) {
            manyClients.add(snapshot("bulk-" + index, "LAB-PC-" + index,
                    ClientStatus.ONLINE, index % 100, (index * 2) % 100,
                    null, 40.0, (long) index, now));
        }

        long started = System.nanoTime();
        Thread producer = new Thread(() -> panel.updateClients(manyClients), "dashboard-test-producer");
        producer.start();
        producer.join(1_000);
        long enqueueMillis = (System.nanoTime() - started) / 1_000_000;
        check(!producer.isAlive(), "background producer returns without waiting for card rendering");
        check(enqueueMillis < 500, "dashboard update enqueue remains responsive");
        SwingUtilities.invokeAndWait(panel::flushPendingUpdates);
        SwingUtilities.invokeAndWait(() -> {
            check(panel.cardCount() == 77, "many clients are displayed");
            panel.setSearchText("LAB-PC-");
            check(panel.visibleCardCount() == 75, "many-client search");
            panel.setSearchText("");
        });
    }

    private static void testClientDetailTabs() {
        ClientDetailPanel detail = new ClientDetailPanel();
        SystemInformation information = new GenericSystemInfoCollector().collect();
        ClientSnapshot selected = new ClientSnapshot("detail-id", "DETAIL-PC", "192.168.1.10",
                "Test OS", ClientStatus.ONLINE, 8L, 20, 30, null, 40.0,
                System.currentTimeMillis(), information);
        detail.updateClient(selected);
        String[] expected = {"Overview", "System Information", "Performance",
            "File Transfer", "Alerts", "Network", "Server-Client Distance"};
        check(detail.tabCount() == expected.length, "detail contains seven tabs");
        for (int index = 0; index < expected.length; index++) {
            check(expected[index].equals(detail.tabTitle(index)), "detail tab " + expected[index]);
        }
        check(detail.client() != null && "detail-id".equals(detail.client().clientId()),
                "detail displays selected client");
        check(information.equals(detail.displayedSystemInformation()),
                "System Information tab displays structured inventory");
    }

    private static void testMetricStatusRendering() {
        long now = System.currentTimeMillis();
        ClientMetrics metrics = new ClientMetrics(
                MetricReading.ok(20, now, MetricSource.TELEMETRY),
                MetricReading.ok(30, now, MetricSource.TELEMETRY),
                new MetricReading(62d, MeasurementStatus.STALE, now - 5_000,
                        MetricSource.TELEMETRY),
                MetricReading.unavailable(MeasurementStatus.UNSUPPORTED, now,
                        MetricSource.TELEMETRY),
                MetricReading.ok(74, now, MetricSource.HEARTBEAT));
        ClientSnapshot snapshot = new ClientSnapshot("metric-id", "METRIC-PC", "192.168.1.20",
                "Test OS", ClientStatus.ONLINE, 5L, 20, 30, 62d, 74d,
                now, null, metrics);
        ClientCard card = new ClientCard(snapshot);
        check(card.gpuDisplayText().startsWith("STALE"), "stale GPU is visible on the card");
        check("N/A".equals(card.diskActiveDisplayText()), "unsupported disk active is N/A");
        check(card.diskCapacityDisplayText().contains("74")
                        && card.diskCapacityDisplayText().endsWith("%"),
                "disk capacity is displayed separately");
    }

    private static void testServerClientDistanceRendering() {
        long now = System.currentTimeMillis();
        ServerClientDistance physical = new ServerClientDistance("distance-id", 5d,
                ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                DistanceDataSource.ADMIN_COORDINATES, ServerClientDistanceStatus.OK,
                null, now, "test-lab", null, null);
        ClientSnapshot snapshot = new ClientSnapshot("distance-id", "DISTANCE-PC", "192.0.2.5",
                "Test OS", ClientStatus.OFFLINE, null, 0, 0, null, null, now, null,
                null, null, 0, physical);
        ClientCard card = new ClientCard(snapshot);
        check(card.distanceDisplayText().contains("5.00 m")
                        && card.distanceDisplayText().contains("Position-Based"),
                "card displays physical distance and provenance while Client is offline");
        ServerClientDistancePanel panel = new ServerClientDistancePanel();
        panel.setDistance(physical);
        check(panel.distanceText().contains("5.00 m"), "distance detail renders metres");
        check(panel.methodText().contains("POSITION_BASED_ESTIMATION"),
                "distance detail renders method");
    }

    private static void testAlertManagementTabs() {
        long now = System.currentTimeMillis();
        AlertManagementPanel panel = new AlertManagementPanel();
        AlertRecord active = new AlertRecord("a1", "c1", "PC-1", AlertMetric.CPU,
                AlertSeverity.WARNING, 85, 80, AlertStatus.ACTIVE,
                AlertDeliveryStatus.ACKNOWLEDGED, now, null, null, now, "r1");
        AlertRecord resolved = new AlertRecord("a2", "c2", "PC-2", AlertMetric.DISK_ACTIVE,
                AlertSeverity.CRITICAL, 95, 90, AlertStatus.RESOLVED,
                AlertDeliveryStatus.ACKNOWLEDGED, now - 1_000, null, now, now, "r2");
        panel.setAlerts(List.of(active, resolved));
        check(panel.currentCount() == 1, "current alerts tab contains open alerts");
        check(panel.historyCount() == 1, "alert history tab contains resolved alerts");

        panel.setSeverityFilter("WARNING");
        check(panel.currentCount() == 1 && panel.historyCount() == 0,
                "severity filter applies to current and history tables");
        panel.setSeverityFilter("ALL");
        panel.setMetricFilter("DISK_ACTIVE");
        check(panel.currentCount() == 0 && panel.historyCount() == 1,
                "resource filter applies to current and history tables");
        panel.setMetricFilter("ALL");
        panel.setClientFilter("PC-1");
        check(panel.currentCount() == 1 && panel.historyCount() == 0,
                "client filter matches hostname");
        panel.setClientFilter("");

        AtomicReference<String> acknowledged = new AtomicReference<>();
        AtomicReference<String> manuallyResolved = new AtomicReference<>();
        panel.setActions(acknowledged::set, manuallyResolved::set, ignored -> {});
        panel.selectCurrentRow(0);
        panel.clickAcknowledge();
        panel.clickResolve();
        check("a1".equals(acknowledged.get()), "Acknowledge button targets selected alert");
        check("a1".equals(manuallyResolved.get()), "Resolve button targets selected alert");
    }

    private static void testAlertCardPriority() {
        long now = System.currentTimeMillis();
        ClientMetrics healthy = new ClientMetrics(
                MetricReading.ok(10, now, MetricSource.TELEMETRY),
                MetricReading.ok(20, now, MetricSource.TELEMETRY),
                MetricReading.unavailable(MeasurementStatus.UNSUPPORTED, now, MetricSource.TELEMETRY),
                MetricReading.unavailable(MeasurementStatus.UNSUPPORTED, now, MetricSource.TELEMETRY),
                MetricReading.ok(30, now, MetricSource.TELEMETRY));
        ClientSnapshot alerted = new ClientSnapshot("alerted", "ALERT-PC", "192.168.1.30",
                "Test OS", ClientStatus.ONLINE, 5L, 10, 20, null, 30d,
                now, null, healthy, AlertSeverity.CRITICAL, 2);
        ClientCard card = new ClientCard(alerted);
        check(card.health() == ClientHealthLevel.CRITICAL,
                "active critical alert overrides healthy metric card color");
        check(card.statusDisplayText().contains("CRITICAL")
                        && card.statusDisplayText().contains("2 ALERT"),
                "card badge exposes alert severity and count");
    }

    private static void testFileTransferPanels() {
        long now = System.currentTimeMillis();
        FileTransferPanel serverPanel = new FileTransferPanel(
                new AuthorizationContext("admin", UserRole.ADMIN, java.util.Set.of()));
        serverPanel.setClients(List.of(
                snapshot("file-1", "FILE-PC-1", ClientStatus.ONLINE, 10, 20, null, 30d, 5L, now),
                snapshot("file-2", "FILE-PC-2", ClientStatus.OFFLINE, 0, 0, null, 30d, null, now)),
                java.util.Set.of("file-1"));
        FileTransferProgress progress = new FileTransferProgress("transfer", "file-id", "file-1",
                "FILE-PC-1", "sample.bin", 100, "0".repeat(64), 50, 1024,
                FileTransferStatus.IN_PROGRESS, "Sending", null, now, now);
        serverPanel.setTransfers(List.of(progress));
        check(serverPanel.clientCount() == 2, "file transfer Client selector renders Clients");
        check(serverPanel.transferCount() == 1, "file transfer history renders per-Client progress");

        ClientFileTransferPanel clientPanel = new ClientFileTransferPanel();
        clientPanel.updateTransfer(progress);
        check(clientPanel.transferCount() == 1, "Client receive panel renders progress");
    }

    private static void testAnsibleManagementPanel() {
        AnsibleManagementPanel panel = new AnsibleManagementPanel(
                new AuthorizationContext("admin", UserRole.ADMIN, java.util.Set.of()));
        panel.setHosts(List.of(new AnsibleManagedHost("linux_01", "Linux 01", "client-1",
                AnsiblePlatform.LINUX, "192.0.2.10", 22, AnsibleConnectionType.SSH,
                "/opt/lan/lan_monitoring_sync.txt", true)));
        check(panel.hostCount() == 1, "Ansible Management renders explicit managed hosts");
        panel.updateHostResult(new lanmonitoringserver.model.AnsibleHostResult("linux_01",
                AnsiblePlatform.LINUX, lanmonitoringserver.model.AnsibleHostStatus.SUCCESS,
                false, "0".repeat(64), "Already synchronized"));
        check(panel.resultCount() == 1, "Ansible Management renders per-host result");
    }

    private static ClientSnapshot snapshot(String id, String hostname, ClientStatus status,
            double cpu, double ram, Double gpu, Double disk, Long rtt, long lastSeen) {
        return new ClientSnapshot(id, hostname, "192.168.1.10", "Test OS",
                status, rtt, cpu, ram, gpu, disk, lastSeen, null);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
