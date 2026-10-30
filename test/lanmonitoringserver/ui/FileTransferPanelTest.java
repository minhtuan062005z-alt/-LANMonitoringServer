package lanmonitoringserver.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.security.AuthorizationContext;

/** Headless regression checks for stable single/multi Client selection and safe bulk permissions. */
public final class FileTransferPanelTest {
    private FileTransferPanelTest() {}

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(FileTransferPanelTest::run);
        System.out.println("FileTransferPanelTest: PASS");
    }

    private static void run() {
        Map<String, ClientPermissions> permissions = new LinkedHashMap<>();
        permissions.put("client-a", new ClientPermissions(false, true, false));
        permissions.put("client-b", new ClientPermissions(true, false, true));
        permissions.put("client-c", new ClientPermissions(true, true, true));
        List<Map.Entry<String, ClientPermissions>> updates = new ArrayList<>();
        AtomicInteger confirmations = new AtomicInteger();
        List<String> messages = new ArrayList<>();
        AtomicReference<FileTransferPanel.TransferRequest> transfer = new AtomicReference<>();

        FileTransferPanel panel = new FileTransferPanel(
                new AuthorizationContext("admin", UserRole.ADMIN, Set.of()));
        panel.setActions(transfer::set, ignored -> {}, (id, value) -> {
            updates.add(Map.entry(id, value));
            permissions.put(id, value);
        }, permissions::get);
        panel.setDialogHandlersForTest((count, ids) -> {
            confirmations.incrementAndGet();
            check(count == 2, "bulk confirmation reports selected Client count");
            return true;
        }, (text, type) -> messages.add(text));

        List<ClientSnapshot> initial = List.of(snapshot("client-a", "ALPHA"),
                snapshot("client-b", "BRAVO"), snapshot("client-c", "CHARLIE"));
        panel.setClients(initial, Set.of("client-a", "client-b"));
        JTable table = panel.clientsTableForTest();
        check(table.getSelectionModel().getSelectionMode()
                == ListSelectionModel.MULTIPLE_INTERVAL_SELECTION,
                "Ctrl/Shift multi-selection mode is enabled");
        check(table.getSelectedRowCount() == 2, "initial Dashboard selection is seeded once");

        selectOnly(table, "client-a");
        check(table.getSelectedRowCount() == 1, "single click semantics select exactly one row");
        check(panel.selectedClientIdsForTest().equals(Set.of("client-a")),
                "single selection resolves the stable Client ID");
        check(panel.selectionLabelForTest().getText().contains("client-a"),
                "single-selection label identifies the Client");

        // A realtime refresh with a different Dashboard preselection must preserve local selection.
        panel.setClients(List.of(snapshot("client-c", "CHARLIE"), snapshot("client-a", "ALPHA"),
                snapshot("client-b", "BRAVO")), Set.of("client-a", "client-b"));
        check(panel.selectedClientIdsForTest().equals(Set.of("client-a")),
                "refresh/reorder preserves only the local stable Client selection");

        table.getRowSorter().toggleSortOrder(1);
        selectOnly(table, "client-b");
        panel.setClients(initial, Set.of("client-a", "client-c"));
        check(panel.selectedClientIdsForTest().equals(Set.of("client-b")),
                "sort and refresh preserve selection by Client ID, not view row");

        // Single apply targets exactly the selected Client.
        updates.clear();
        panel.receiveAlertsForTest().doClick(); // false -> true for client-b
        panel.applyButtonForTest().doClick();
        check(updates.size() == 1 && updates.get(0).getKey().equals("client-b"),
                "single permission apply targets exactly one selected Client");
        check(updates.get(0).getValue().equals(new ClientPermissions(true, true, true)),
                "single permission apply uses the loaded values for that Client");

        // Ctrl-style non-contiguous selection and a bulk update of one explicitly changed field.
        permissions.put("client-a", new ClientPermissions(false, true, false));
        permissions.put("client-b", new ClientPermissions(true, false, true));
        selectOnly(table, "client-a");
        addSelection(table, "client-b");
        check(panel.selectedClientIdsForTest().equals(Set.of("client-a", "client-b")),
                "multiple distinct Clients can be selected");
        check(panel.selectionLabelForTest().getText().contains("2 Clients"),
                "bulk-selection count is visible");
        updates.clear();
        panel.receiveAlertsForTest().doClick(); // mixed -> explicit true
        panel.applyButtonForTest().doClick();
        check(confirmations.get() == 1, "bulk apply requires confirmation");
        check(updates.size() == 2, "bulk apply updates every selected Client");
        check(permissions.get("client-a").equals(new ClientPermissions(false, true, false)),
                "bulk apply preserves unedited permissions on first Client");
        check(permissions.get("client-b").equals(new ClientPermissions(true, true, true)),
                "bulk apply changes only the explicitly edited permission on second Client");

        // Send filters Receive files=false Clients and reports them explicitly.
        permissions.put("client-a", new ClientPermissions(false, true, false));
        permissions.put("client-b", new ClientPermissions(true, true, true));
        panel.selectedFileForTest(Path.of("sample.txt"));
        messages.clear();
        panel.sendSelectedForTest();
        check(transfer.get() != null && transfer.get().clientIds().equals(Set.of("client-b")),
                "Send targets only selected Clients with Receive files=true");
        check(messages.stream().anyMatch(text -> text.contains("client-a")
                        && text.contains("Receive files=false")),
                "Send reports the selected Client denied by file permission");

        // Disappearing Clients are removed from selection without selecting another row by index.
        panel.setClients(List.of(snapshot("client-c", "CHARLIE")), Set.of("client-c"));
        check(panel.selectedClientIdsForTest().isEmpty(),
                "selection clears when all selected Clients disappear");
        check(!panel.applyButtonForTest().isEnabled(),
                "permission Apply is disabled with no selected Client");
    }

    private static void selectOnly(JTable table, String id) {
        int row = viewRow(table, id);
        table.setRowSelectionInterval(row, row);
    }

    private static void addSelection(JTable table, String id) {
        int row = viewRow(table, id);
        table.addRowSelectionInterval(row, row);
    }

    private static int viewRow(JTable table, String id) {
        for (int view = 0; view < table.getRowCount(); view++) {
            int model = table.convertRowIndexToModel(view);
            if (id.equals(table.getModel().getValueAt(model, 0))) return view;
        }
        throw new AssertionError("Missing Client row " + id);
    }

    private static ClientSnapshot snapshot(String id, String hostname) {
        long now = System.currentTimeMillis();
        return new ClientSnapshot(id, hostname, "192.0.2.10", "Test OS", ClientStatus.ONLINE,
                5L, 10, 20, null, 30d, now, null);
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError("Failed assertion: " + description);
    }
}
