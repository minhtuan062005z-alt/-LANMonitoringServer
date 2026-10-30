package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.NotificationTemplate;
import lanmonitoringserver.service.LanServerService.NotificationSendResult;

/** Fixed-template ADMIN notification sender; it intentionally contains no free-form command input. */
public final class AdminNotificationPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final DefaultTableModel clientModel = model("Client ID", "Hostname", "Status", "Admin alerts");
    private final DefaultTableModel resultModel = model("Client", "Hostname", "Template", "Delivery/ACK", "Time");
    private final JTable clientTable = new JTable(clientModel);
    private final JTable resultTable = new JTable(resultModel);
    private final JComboBox<NotificationTemplate> templates = new JComboBox<>(NotificationTemplate.values());
    private final JButton sendButton = new JButton("Send");
    private final JLabel selectionLabel = new JLabel("Selected: 0 Clients");
    private final JLabel statusLabel = new JLabel(" ");
    private final boolean allowed;
    private boolean initialized;
    private boolean rebuilding;
    private Function<String, ClientPermissions> permissionLookup = ignored -> ClientPermissions.defaults();
    private Function<Request, CompletableFuture<NotificationSendResult>> sendAction = request ->
            CompletableFuture.failedFuture(new IllegalStateException("Notification action is not configured"));

    public AdminNotificationPanel(boolean allowed) {
        super(new BorderLayout(8, 8));
        this.allowed = allowed;
        clientTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        clientTable.setCellSelectionEnabled(false);
        clientTable.setRowSelectionAllowed(true);
        clientTable.setColumnSelectionAllowed(false);
        clientTable.setAutoCreateRowSorter(true);
        clientTable.getSelectionModel().addListSelectionListener(event -> {
            if (!rebuilding && !event.getValueIsAdjusting()) updateSelectionLabel();
        });
        resultTable.setAutoCreateRowSorter(true);
        add(toolbar(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("Online Clients", clientTable), titled("Send status / ACK", resultTable));
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(statusLabel, BorderLayout.SOUTH);
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAll = new JButton("Select all ONLINE");
        selectAll.addActionListener(event -> {
            if (clientTable.getRowCount() > 0) clientTable.selectAll();
            updateSelectionLabel();
        });
        JButton clear = new JButton("Clear selection");
        clear.addActionListener(event -> clientTable.clearSelection());
        sendButton.setEnabled(allowed);
        sendButton.addActionListener(event -> send());
        panel.add(new JLabel("Template:")); panel.add(templates); panel.add(selectAll);
        panel.add(clear); panel.add(selectionLabel); panel.add(sendButton);
        return panel;
    }

    public void setAction(Function<Request, CompletableFuture<NotificationSendResult>> action) {
        if (action != null) sendAction = action;
    }

    public void setClients(List<ClientSnapshot> clients,
            Function<String, ClientPermissions> permissions) {
        List<ClientSnapshot> safe = clients == null ? List.of() : List.copyOf(clients);
        if (permissions != null) permissionLookup = permissions;
        Runnable update = () -> {
            Set<String> previous = selectedIds();
            rebuilding = true;
            try {
                clientModel.setRowCount(0);
                for (ClientSnapshot client : safe) {
                    if (client.status() != ClientStatus.ONLINE) continue;
                    clientModel.addRow(new Object[]{client.clientId(), client.hostname(),
                        client.status(), permissionLookup.apply(client.clientId()).receiveAlerts()});
                }
                clientTable.clearSelection();
                if (initialized) restore(previous);
                initialized = true;
            } finally { rebuilding = false; }
            updateSelectionLabel();
        };
        runOnEdt(update);
    }

    public void setNotifications(List<AlertRecord> alerts) {
        List<AlertRecord> safe = alerts == null ? List.of() : List.copyOf(alerts);
        runOnEdt(() -> {
            resultModel.setRowCount(0);
            safe.stream().filter(AlertRecord::manualNotification)
                    .sorted(java.util.Comparator.comparingLong(AlertRecord::createdAt).reversed())
                    .forEach(alert -> resultModel.addRow(new Object[]{alert.clientId(), alert.hostname(),
                        alert.templateName(), alert.deliveryStatus(),
                        TIME.format(Instant.ofEpochMilli(alert.updatedAt()))}));
        });
    }

    private void send() {
        Set<String> ids = selectedIds();
        if (ids.isEmpty()) { statusLabel.setText("Select at least one ONLINE Client."); return; }
        NotificationTemplate template = (NotificationTemplate) templates.getSelectedItem();
        sendButton.setEnabled(false); // prevents double clicks while the request is in flight
        statusLabel.setText("Sending " + template.displayName() + " to " + ids.size() + " Client(s)...");
        CompletableFuture<NotificationSendResult> future;
        try { future = sendAction.apply(new Request(template, ids)); }
        catch (RuntimeException exception) { future = CompletableFuture.failedFuture(exception); }
        future.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            sendButton.setEnabled(allowed);
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException
                        && error.getCause() != null ? error.getCause() : error;
                statusLabel.setText("Failed: " + cause.getMessage());
                return;
            }
            statusLabel.setText("Created: " + result.created().size() + "; rejected: "
                    + result.rejected().size() + (result.rejected().isEmpty() ? ""
                    : " - " + result.rejected()));
        }));
    }

    private Set<String> selectedIds() {
        Set<String> result = new LinkedHashSet<>();
        for (int view : clientTable.getSelectedRows()) {
            int row = clientTable.convertRowIndexToModel(view);
            result.add((String) clientModel.getValueAt(row, 0));
        }
        return result;
    }

    private void restore(Set<String> ids) {
        for (int modelRow = 0; modelRow < clientModel.getRowCount(); modelRow++) {
            if (ids.contains(clientModel.getValueAt(modelRow, 0))) {
                int view = clientTable.convertRowIndexToView(modelRow);
                if (view >= 0) clientTable.addRowSelectionInterval(view, view);
            }
        }
    }

    private void updateSelectionLabel() {
        selectionLabel.setText("Selected: " + selectedIds().size() + " Client(s)");
    }
    private static JScrollPane titled(String title, JTable table) {
        JScrollPane pane = new JScrollPane(table);
        pane.setBorder(BorderFactory.createTitledBorder(title));
        return pane;
    }
    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    public record Request(NotificationTemplate template, Set<String> clientIds) {
        public Request { clientIds = Set.copyOf(clientIds); }
    }

    JTable clientTableForTest() { return clientTable; }
    JComboBox<NotificationTemplate> templatesForTest() { return templates; }
    JButton sendButtonForTest() { return sendButton; }
    JLabel statusLabelForTest() { return statusLabel; }
    int resultCountForTest() { return resultModel.getRowCount(); }
}
