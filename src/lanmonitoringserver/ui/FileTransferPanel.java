package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.security.AuthorizationContext;

/** Server UI for target selection, permission management and per-client progress/history. */
public final class FileTransferPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String FILES = "Receive files";
    private static final String ALERTS = "Admin alerts";
    private static final String EXPORT = "Export system info";

    private final JLabel fileLabel = new JLabel("No file selected");
    private final JLabel roleLabel = new JLabel();
    private final JLabel selectionLabel = new JLabel("Selected: 0 Clients");
    private final DefaultTableModel clientModel = readOnly("Client ID", "Hostname", "Status", FILES);
    private final DefaultTableModel transferModel = readOnly("Transfer", "Client", "File", "Progress",
            "Bytes", "Speed", "Status", "Detail", "Saved path");
    private final JTable clients = new JTable(clientModel);
    private final JTable transfers = new JTable(transferModel);
    private final JCheckBox receiveFiles = new JCheckBox(FILES);
    private final JCheckBox receiveAlerts = new JCheckBox(ALERTS);
    private final JCheckBox exportInfo = new JCheckBox(EXPORT);
    private final JButton loadButton = new JButton("Load");
    private final JButton applyButton = new JButton("Apply permissions");
    private final boolean canManagePermissions;
    private Path selectedFile;
    private List<ClientSnapshot> snapshots = List.of();
    private List<FileTransferProgress> progress = List.of();
    private Consumer<TransferRequest> sendAction = ignored -> {};
    private Consumer<String> cancelAction = ignored -> {};
    private BiConsumer<String, ClientPermissions> permissionAction = (ignored, value) -> {};
    private Function<String, ClientPermissions> permissionLookup = ignored -> ClientPermissions.defaults();
    private BulkConfirmation bulkConfirmation = (count, ids) -> JOptionPane.showConfirmDialog(this,
            "Apply the changed permissions to " + count + " selected Clients?\n" + String.join(", ", ids),
            "Confirm bulk permission update", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            == JOptionPane.YES_OPTION;
    private BiConsumer<String, Integer> messageHandler = (text, type) ->
            JOptionPane.showMessageDialog(this, text, "File Transfer", type);
    private boolean selectionInitialized;
    private boolean rebuildingClients;
    private boolean loadingPermissions;
    private boolean receiveFilesDirty;
    private boolean receiveAlertsDirty;
    private boolean exportInfoDirty;

    public FileTransferPanel(AuthorizationContext authorization) {
        super(new BorderLayout(8, 8));
        canManagePermissions = authorization.role().canManagePermissions();
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        roleLabel.setText("Signed in: " + authorization.principal() + " / " + authorization.role());
        add(toolbar(authorization), BorderLayout.NORTH);

        clients.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        clients.setCellSelectionEnabled(false);
        clients.setRowSelectionAllowed(true);
        clients.setColumnSelectionAllowed(false);
        clients.setAutoCreateRowSorter(true);
        clients.setSelectionBackground(new Color(0x2563EB));
        clients.setSelectionForeground(Color.WHITE);
        clients.getSelectionModel().addListSelectionListener(this::clientSelectionChanged);
        transfers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        transfers.getColumnModel().getColumn(3).setCellRenderer(new ProgressRenderer());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(clients), new JScrollPane(transfers));
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
        add(permissionBar(), BorderLayout.SOUTH);
        setPermissionControlsEnabled(false);
    }

    private JPanel toolbar(AuthorizationContext authorization) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton select = new JButton("Select File");
        select.addActionListener(event -> selectFile());
        JButton all = new JButton("Select All Online");
        all.addActionListener(event -> selectAllOnline());
        JButton send = new JButton("Send");
        send.setEnabled(authorization.role().canSendFiles());
        send.addActionListener(event -> sendSelected());
        JButton cancel = new JButton("Cancel selected transfer");
        cancel.setEnabled(authorization.role().canSendFiles());
        cancel.addActionListener(event -> cancelSelected());
        panel.add(select); panel.add(fileLabel); panel.add(all); panel.add(send); panel.add(cancel);
        panel.add(roleLabel);
        return panel;
    }

    private JPanel permissionBar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBorder(BorderFactory.createTitledBorder("Selected Client permissions (Server-owned)"));
        receiveFiles.addActionListener(event -> permissionEdited(Field.FILES));
        receiveAlerts.addActionListener(event -> permissionEdited(Field.ALERTS));
        exportInfo.addActionListener(event -> permissionEdited(Field.EXPORT));
        loadButton.addActionListener(event -> loadPermissions(true));
        applyButton.addActionListener(event -> applyPermissions());
        panel.add(selectionLabel); panel.add(receiveFiles); panel.add(receiveAlerts); panel.add(exportInfo);
        panel.add(loadButton); panel.add(applyButton);
        return panel;
    }

    public void setActions(Consumer<TransferRequest> send, Consumer<String> cancel,
            BiConsumer<String, ClientPermissions> updatePermissions,
            Function<String, ClientPermissions> lookup) {
        sendAction = send == null ? ignored -> {} : send;
        cancelAction = cancel == null ? ignored -> {} : cancel;
        permissionAction = updatePermissions == null ? (ignored, value) -> {} : updatePermissions;
        permissionLookup = lookup == null ? ignored -> ClientPermissions.defaults() : lookup;
    }

    /** Dashboard selection seeds the first display only; subsequent refreshes preserve local stable IDs. */
    public void setClients(List<ClientSnapshot> updated, Set<String> preselected) {
        List<ClientSnapshot> safeUpdated = updated == null ? List.of() : List.copyOf(updated);
        Set<String> safePreselected = preselected == null ? Set.of() : Set.copyOf(preselected);
        runOnEdt(() -> {
            Set<String> previous = selectedClientIds();
            boolean wasInitialized = selectionInitialized;
            Set<String> desired = wasInitialized ? previous : safePreselected;
            rebuildingClients = true;
            try {
                snapshots = safeUpdated;
                clientModel.setRowCount(0);
                List<Integer> selectedRows = new ArrayList<>();
                for (int modelRow = 0; modelRow < snapshots.size(); modelRow++) {
                    ClientSnapshot client = snapshots.get(modelRow);
                    ClientPermissions permission = permissionLookup.apply(client.clientId());
                    clientModel.addRow(new Object[]{client.clientId(), client.hostname(), client.status(),
                        permission.receiveFiles()});
                    if (desired.contains(client.clientId())) selectedRows.add(modelRow);
                }
                clients.clearSelection();
                for (int modelRow : selectedRows) {
                    int viewRow = clients.convertRowIndexToView(modelRow);
                    if (viewRow >= 0) clients.addRowSelectionInterval(viewRow, viewRow);
                }
                selectionInitialized = true;
            } finally {
                rebuildingClients = false;
            }
            Set<String> restored = selectedClientIds();
            updateSelectionLabel(restored);
            if (!wasInitialized || !previous.equals(restored) || restored.isEmpty()) loadPermissions(false);
            else setPermissionControlsEnabled(true); // preserve unsaved checkbox edits during refresh
        });
    }

    public void setTransfers(List<FileTransferProgress> updated) {
        runOnEdt(() -> {
            progress = updated == null ? List.of() : List.copyOf(updated);
            transferModel.setRowCount(0);
            for (FileTransferProgress item : progress) transferModel.addRow(new Object[]{
                item.transferId(), item.hostname() + " [" + item.clientId() + "]", item.fileName(),
                item.percent(), item.bytesTransferred() + "/" + item.fileSize(),
                humanRate(item.bytesPerSecond()), item.status(), item.detail(),
                item.savedPath() == null ? "" : item.savedPath()});
        });
    }

    private void clientSelectionChanged(ListSelectionEvent event) {
        if (rebuildingClients || event.getValueIsAdjusting()) return;
        updateSelectionLabel(selectedClientIds());
        loadPermissions(false);
    }

    private void updateSelectionLabel(Set<String> selected) {
        selectionLabel.setText(selected.size() == 1
                ? "Selected: 1 Client - " + selected.iterator().next()
                : "Selected: " + selected.size() + " Clients");
        selectionLabel.setToolTipText(selected.isEmpty() ? null : String.join(", ", selected));
    }

    private void selectFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.getSelectedFile().toPath();
            fileLabel.setText(selectedFile.getFileName().toString());
            fileLabel.setToolTipText(selectedFile.toString());
        }
    }

    private void selectAllOnline() {
        rebuildingClients = true;
        try {
            clients.clearSelection();
            for (int modelRow = 0; modelRow < snapshots.size(); modelRow++) {
                if (snapshots.get(modelRow).status() == ClientStatus.ONLINE) {
                    int viewRow = clients.convertRowIndexToView(modelRow);
                    if (viewRow >= 0) clients.addRowSelectionInterval(viewRow, viewRow);
                }
            }
        } finally { rebuildingClients = false; }
        updateSelectionLabel(selectedClientIds());
        loadPermissions(false);
    }

    private void sendSelected() {
        if (selectedFile == null) { error("Select a file first"); return; }
        Set<String> selected = selectedClientIds();
        if (selected.isEmpty()) { error("Select at least one Client"); return; }
        Set<String> eligible = new LinkedHashSet<>();
        List<String> denied = new ArrayList<>();
        for (String clientId : selected) {
            if (permissionLookup.apply(clientId).receiveFiles()) eligible.add(clientId);
            else denied.add(clientId + " (Receive files=false)");
        }
        if (eligible.isEmpty()) {
            error("No selected Client can receive files. Denied: " + String.join(", ", denied));
            return;
        }
        sendAction.accept(new TransferRequest(selectedFile, Set.copyOf(eligible)));
        if (!denied.isEmpty()) message("File send started for " + eligible.size()
                + " Client(s). Skipped: " + String.join(", ", denied), JOptionPane.WARNING_MESSAGE);
    }

    private void cancelSelected() {
        int viewRow = transfers.getSelectedRow();
        if (viewRow >= 0) cancelAction.accept((String) transferModel.getValueAt(
                transfers.convertRowIndexToModel(viewRow), 0));
    }

    private void loadPermissions(boolean warnWhenEmpty) {
        Set<String> ids = selectedClientIds();
        if (ids.isEmpty()) {
            setPermissionControlsEnabled(false);
            resetDirty(); resetLabels();
            if (warnWhenEmpty) error("Select at least one Client");
            return;
        }
        setPermissionControlsEnabled(true);
        List<ClientPermissions> values = ids.stream().map(permissionLookup).toList();
        loadingPermissions = true;
        try {
            loadField(receiveFiles, FILES, values.stream().map(ClientPermissions::receiveFiles).toList());
            loadField(receiveAlerts, ALERTS, values.stream().map(ClientPermissions::receiveAlerts).toList());
            loadField(exportInfo, EXPORT,
                    values.stream().map(ClientPermissions::exportSystemInformation).toList());
            resetDirty();
        } finally { loadingPermissions = false; }
    }

    private static void loadField(JCheckBox box, String label, List<Boolean> values) {
        boolean first = values.get(0);
        boolean mixed = values.stream().anyMatch(value -> value != first);
        box.setSelected(!mixed && first);
        box.setText(mixed ? label + " (mixed)" : label);
        box.setToolTipText(mixed ? "Selected Clients currently have different values" : null);
    }

    private void permissionEdited(Field field) {
        if (loadingPermissions) return;
        switch (field) {
            case FILES -> { receiveFilesDirty = true; receiveFiles.setText(FILES + " *"); }
            case ALERTS -> { receiveAlertsDirty = true; receiveAlerts.setText(ALERTS + " *"); }
            case EXPORT -> { exportInfoDirty = true; exportInfo.setText(EXPORT + " *"); }
        }
    }

    private void applyPermissions() {
        Set<String> ids = selectedClientIds();
        if (ids.isEmpty()) { error("Select at least one Client"); return; }
        if (ids.size() > 1 && !isDirty()) {
            error("No permission was changed. Bulk apply did not update any Client.");
            return;
        }
        if (ids.size() > 1 && !bulkConfirmation.confirm(ids.size(), ids)) return;
        for (String id : ids) {
            ClientPermissions current = permissionLookup.apply(id);
            ClientPermissions updated = ids.size() == 1
                    ? new ClientPermissions(receiveFiles.isSelected(), receiveAlerts.isSelected(), exportInfo.isSelected())
                    : new ClientPermissions(receiveFilesDirty ? receiveFiles.isSelected() : current.receiveFiles(),
                            receiveAlertsDirty ? receiveAlerts.isSelected() : current.receiveAlerts(),
                            exportInfoDirty ? exportInfo.isSelected() : current.exportSystemInformation());
            permissionAction.accept(id, updated);
        }
        resetDirty(); resetLabels();
    }

    private Set<String> selectedClientIds() {
        Set<String> result = new LinkedHashSet<>();
        for (int viewRow : clients.getSelectedRows()) {
            int row = clients.convertRowIndexToModel(viewRow);
            if (row >= 0 && row < clientModel.getRowCount()) result.add((String) clientModel.getValueAt(row, 0));
        }
        return result;
    }

    private boolean isDirty() { return receiveFilesDirty || receiveAlertsDirty || exportInfoDirty; }
    private void resetDirty() { receiveFilesDirty = receiveAlertsDirty = exportInfoDirty = false; }
    private void resetLabels() {
        receiveFiles.setText(FILES); receiveAlerts.setText(ALERTS); exportInfo.setText(EXPORT);
    }
    private void setPermissionControlsEnabled(boolean enabled) {
        receiveFiles.setEnabled(enabled); receiveAlerts.setEnabled(enabled); exportInfo.setEnabled(enabled);
        loadButton.setEnabled(enabled); applyButton.setEnabled(enabled && canManagePermissions);
    }
    private void error(String text) { message(text, JOptionPane.WARNING_MESSAGE); }
    private void message(String text, int type) { messageHandler.accept(text, type); }
    private static String humanRate(double value) { return String.format("%.1f KiB/s", value / 1024d); }
    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    public record TransferRequest(Path file, Set<String> clientIds) {}
    @FunctionalInterface interface BulkConfirmation { boolean confirm(int count, Set<String> ids); }
    private enum Field { FILES, ALERTS, EXPORT }

    int clientCount() { return clientModel.getRowCount(); }
    int transferCount() { return transferModel.getRowCount(); }
    JTable clientsTableForTest() { return clients; }
    JCheckBox receiveFilesForTest() { return receiveFiles; }
    JCheckBox receiveAlertsForTest() { return receiveAlerts; }
    JCheckBox exportInfoForTest() { return exportInfo; }
    JButton applyButtonForTest() { return applyButton; }
    JLabel selectionLabelForTest() { return selectionLabel; }
    Set<String> selectedClientIdsForTest() { return Set.copyOf(selectedClientIds()); }
    void selectedFileForTest(Path file) { selectedFile = file; }
    void sendSelectedForTest() { sendSelected(); }
    void setDialogHandlersForTest(BulkConfirmation confirmation, BiConsumer<String, Integer> messages) {
        if (confirmation != null) bulkConfirmation = confirmation;
        if (messages != null) messageHandler = messages;
    }

    private static final class ProgressRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final JProgressBar bar = new JProgressBar(0, 100);
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focus, int row, int column) {
            int percent = value instanceof Number number ? number.intValue() : 0;
            bar.setValue(percent); bar.setStringPainted(true); bar.setString(percent + "%");
            return bar;
        }
    }
}
