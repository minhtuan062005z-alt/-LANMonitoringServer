package lanmonitoringserver.ui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertSeverity;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.NotificationTemplate;
import lanmonitoringserver.service.LanServerService.NotificationSendResult;

/** Headless selection/refresh/template/status regression for Send Notification. */
public final class AdminNotificationPanelTest {
    private AdminNotificationPanelTest() {}

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(AdminNotificationPanelTest::run);
        System.out.println("AdminNotificationPanelTest: PASS");
    }

    private static void run() {
        AdminNotificationPanel panel = new AdminNotificationPanel(true);
        AtomicReference<AdminNotificationPanel.Request> request = new AtomicReference<>();
        panel.setAction(value -> {
            request.set(value);
            return CompletableFuture.completedFuture(new NotificationSendResult(List.of(), Map.of()));
        });
        List<ClientSnapshot> initial = List.of(snapshot("a", "ALPHA", ClientStatus.ONLINE),
                snapshot("b", "BRAVO", ClientStatus.ONLINE),
                snapshot("c", "CHARLIE", ClientStatus.OFFLINE));
        panel.setClients(initial, ignored -> ClientPermissions.defaults());
        JTable table = panel.clientTableForTest();
        check(table.getRowCount() == 2, "only ONLINE Clients are selectable");
        check(panel.templatesForTest().getItemCount() == 7, "all seven fixed templates are available");
        table.setRowSelectionInterval(0, 0);
        table.addRowSelectionInterval(1, 1);
        panel.setClients(List.of(snapshot("b", "BRAVO", ClientStatus.ONLINE),
                snapshot("a", "ALPHA", ClientStatus.ONLINE)), ignored -> ClientPermissions.defaults());
        check(table.getSelectedRowCount() == 2, "multi-selection survives realtime reorder by Client ID");
        panel.templatesForTest().setSelectedItem(NotificationTemplate.SYSTEM_MAINTENANCE);
        panel.sendButtonForTest().doClick();
        check(request.get() != null && request.get().clientIds().equals(Set.of("a", "b"))
                        && request.get().template() == NotificationTemplate.SYSTEM_MAINTENANCE,
                "Send forwards the chosen fixed template and exact selected Clients");

        AlertRecord acknowledged = new AlertRecord("notice", "a", "ALPHA", AlertMetric.SYSTEM,
                AlertSeverity.WARNING, 0, 0, AlertStatus.RESOLVED,
                AlertDeliveryStatus.ACKNOWLEDGED, 1_000, null, 2_000L, 2_000,
                "request", NotificationTemplate.GENERAL.name(), "Thông báo", "Nội dung");
        panel.setNotifications(List.of(acknowledged));
        check(panel.resultCountForTest() == 1, "per-Client ACK status is rendered");
    }

    private static ClientSnapshot snapshot(String id, String hostname, ClientStatus status) {
        return new ClientSnapshot(id, hostname, "192.0.2.1", "Test", status, 1L,
                10, 20, null, 30d, System.currentTimeMillis(), null);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
