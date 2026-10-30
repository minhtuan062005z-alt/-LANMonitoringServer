package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.EstimatedClientPosition;
import lanmonitoringserver.model.FingerprintSample;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.WifiScanSnapshot;

/** Survey and diagnostics UI for the server-owned Wi-Fi Radio Map. */
public final class WifiFingerprintPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final DefaultTableModel clientsModel = readOnly(new String[]{"Client", "Scan Status",
        "APs", "Position Status", "Estimated Position", "Confidence / Error", "Updated"});
    private final DefaultTableModel samplesModel = readOnly(new String[]{"Sample ID", "Client Scope", "Reference",
        "X", "Y", "Z", "APs", "Captured"});
    private final JTable clientsTable = new JTable(clientsModel);
    private final JTable samplesTable = new JTable(samplesModel);
    private final JLabel status = new JLabel("Radio Map: 0 samples");
    private final JButton addButton = new JButton("Add Survey Sample");
    private final JButton removeButton = new JButton("Remove Selected Sample");
    private List<ClientSnapshot> clients = List.of();
    private List<FingerprintSample> samples = List.of();
    private BiConsumer<String, SpatialPosition> addAction = (id, position) -> {};
    private Consumer<String> removeAction = id -> {};
    private Supplier<SpatialPosition> surveyPrompt = this::promptForSurveyPosition;

    public WifiFingerprintPanel() {
        super(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setBackground(DashboardColors.BACKGROUND);
        clientsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientsTable.setCellSelectionEnabled(false);
        clientsTable.setRowSelectionAllowed(true);
        clientsTable.setColumnSelectionAllowed(false);
        clientsTable.setSelectionBackground(DashboardColors.PRIMARY);
        clientsTable.setSelectionForeground(java.awt.Color.WHITE);
        samplesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientsTable.setAutoCreateRowSorter(true);
        samplesTable.setAutoCreateRowSorter(true);
        addButton.setEnabled(false);
        clientsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) addButton.setEnabled(selectedClientId() != null);
        });
        addButton.addActionListener(event -> addSample());
        removeButton.addActionListener(event -> removeSample());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setOpaque(false);
        buttons.add(status); buttons.add(addButton); buttons.add(removeButton);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("Latest Client Scans / Position Estimates", clientsTable),
                titled("Surveyed Radio Map", samplesTable));
        split.setResizeWeight(0.5); split.setDividerLocation(270);
        JLabel note = new JLabel("Requires >=3 real RSSI APs and >=3 surveyed coordinates; results are POSITION_BASED_ESTIMATION, not direct ranging.");
        note.setForeground(DashboardColors.MUTED);
        add(buttons, BorderLayout.NORTH); add(split, BorderLayout.CENTER); add(note, BorderLayout.SOUTH);
    }

    public void setActions(BiConsumer<String, SpatialPosition> add,
            Consumer<String> remove) {
        addAction = add == null ? (id, position) -> {} : add;
        removeAction = remove == null ? id -> {} : remove;
    }

    public void setData(List<ClientSnapshot> newClients, List<FingerprintSample> newSamples,
            java.util.function.Function<String, WifiScanSnapshot> scans,
            java.util.function.Function<String, EstimatedClientPosition> estimates) {
        List<ClientSnapshot> clientCopy = List.copyOf(newClients == null ? List.of() : newClients);
        List<FingerprintSample> sampleCopy = List.copyOf(newSamples == null ? List.of() : newSamples);
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setData(clientCopy, sampleCopy, scans, estimates));
            return;
        }
        String selectedClientId = selectedClientId();
        clients = clientCopy; samples = sampleCopy;
        clientsModel.setRowCount(0);
        long now = System.currentTimeMillis();
        for (ClientSnapshot client : clients) {
            WifiScanSnapshot scan = scans.apply(client.clientId());
            EstimatedClientPosition estimate = estimates.apply(client.clientId());
            MeasurementStatus scanStatus = scanStatusAt(scan, now);
            MeasurementStatus positionStatus = estimate == null
                    ? MeasurementStatus.NOT_AVAILABLE : estimate.statusAt(now);
            String position = positionText(estimate, positionStatus);
            String confidence = confidenceText(estimate, positionStatus);
            clientsModel.addRow(new Object[]{client.hostname() + " [" + client.clientId() + "]",
                scanStatus, scan == null ? 0 : scan.accessPoints().size(), positionStatus,
                position, confidence,
                scan == null ? "NOT_AVAILABLE" : TIME.format(Instant.ofEpochMilli(scan.timestamp()))});
        }
        restoreClientSelection(selectedClientId);
        samplesModel.setRowCount(0);
        for (FingerprintSample sample : samples) {
            SpatialPosition p = sample.position();
            samplesModel.addRow(new Object[]{sample.sampleId(), sample.clientId(), p.referencePoint(), p.xMeters(),
                p.yMeters(), p.zMeters() == null ? "" : p.zMeters(), sample.rssiByTarget().size(),
                TIME.format(Instant.ofEpochMilli(sample.capturedAt()))});
        }
        status.setText("Radio Map: " + samples.size() + " samples");
    }

    private void addSample() {
        String clientId = selectedClientId();
        if (clientId == null) { message("Select a Client with a fresh multi-AP scan first."); return; }
        SpatialPosition position = surveyPrompt.get();
        if (position != null) addAction.accept(clientId, position);
    }

    private SpatialPosition promptForSurveyPosition() {
        JTextField reference = new JTextField();
        JTextField x = new JTextField(); JTextField y = new JTextField(); JTextField z = new JTextField();
        JPanel form = new JPanel(new GridLayout(0, 2, 7, 7));
        form.add(new JLabel("Reference point / room:")); form.add(reference);
        form.add(new JLabel("Measured X (m):")); form.add(x);
        form.add(new JLabel("Measured Y (m):")); form.add(y);
        form.add(new JLabel("Measured Z (optional, m):")); form.add(z);
        if (JOptionPane.showConfirmDialog(this, form, "Survey Radio Map Point",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
        try {
            Double zValue = z.getText().isBlank() ? null : Double.valueOf(z.getText().trim());
            return new SpatialPosition(reference.getText(),
                    Double.parseDouble(x.getText().trim()), Double.parseDouble(y.getText().trim()),
                    zValue, System.currentTimeMillis());
        } catch (RuntimeException error) {
            message("Invalid survey coordinate: " + error.getMessage());
            return null;
        }
    }

    private String selectedClientId() {
        int viewRow = clientsTable.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = clientsTable.convertRowIndexToModel(viewRow);
        return modelRow >= 0 && modelRow < clients.size() ? clients.get(modelRow).clientId() : null;
    }

    private void restoreClientSelection(String clientId) {
        if (clientId == null) {
            clientsTable.clearSelection();
            addButton.setEnabled(false);
            return;
        }
        for (int modelRow = 0; modelRow < clients.size(); modelRow++) {
            if (!clientId.equals(clients.get(modelRow).clientId())) continue;
            int viewRow = clientsTable.convertRowIndexToView(modelRow);
            if (viewRow >= 0) {
                clientsTable.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                clientsTable.scrollRectToVisible(clientsTable.getCellRect(viewRow, 0, true));
                addButton.setEnabled(true);
                return;
            }
        }
        clientsTable.clearSelection();
        addButton.setEnabled(false);
    }

    private void removeSample() {
        int view = samplesTable.getSelectedRow();
        if (view < 0) { message("Select a Radio Map sample first."); return; }
        FingerprintSample sample = samples.get(samplesTable.convertRowIndexToModel(view));
        if (JOptionPane.showConfirmDialog(this, "Remove selected surveyed sample?",
                "Radio Map", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            removeAction.accept(sample.sampleId());
        }
    }
    private void message(String value) {
        JOptionPane.showMessageDialog(this, value, "Wi-Fi Fingerprinting", JOptionPane.INFORMATION_MESSAGE);
    }
    private static MeasurementStatus scanStatusAt(WifiScanSnapshot scan, long now) {
        if (scan == null) return MeasurementStatus.NOT_AVAILABLE;
        return scan.status() == MeasurementStatus.OK && scan.isStale(now)
                ? MeasurementStatus.STALE : scan.status();
    }
    private static String positionText(EstimatedClientPosition estimate, MeasurementStatus status) {
        if (estimate == null || estimate.position() == null) return status.name();
        String coordinate = String.format(Locale.ROOT, "(%.2f, %.2f) [%s]",
                estimate.position().xMeters(), estimate.position().yMeters(),
                estimate.position().referencePoint());
        return status == MeasurementStatus.OK ? coordinate : "HISTORICAL " + coordinate;
    }
    private static String confidenceText(EstimatedClientPosition estimate, MeasurementStatus status) {
        if (estimate == null) return status.name();
        String error = estimate.estimatedErrorMeters() == null ? ""
                : String.format(Locale.ROOT, " / +/- %.2f m", estimate.estimatedErrorMeters());
        return status + " / " + estimate.confidence() + error;
    }
    private static JScrollPane titled(String title, JTable table) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }
    private static DefaultTableModel readOnly(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
    int clientRowCount() { return clientsModel.getRowCount(); }
    int sampleRowCount() { return samplesModel.getRowCount(); }
    Object clientCell(int row, int column) { return clientsModel.getValueAt(row, column); }
    JTable clientsTableForTest() { return clientsTable; }
    String selectedClientIdForTest() { return selectedClientId(); }
    boolean addButtonEnabledForTest() { return addButton.isEnabled(); }
    void setSurveyPromptForTest(Supplier<SpatialPosition> prompt) { surveyPrompt = prompt; }
    void clickAddForTest() { addButton.doClick(); }
}
