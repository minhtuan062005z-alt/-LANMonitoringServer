package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ServerClientDistance;

/** Multi-Client distance table and real-coordinate room map. */
public final class ServerClientDistanceManagementPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String[] COLUMNS = {"Client ID", "Hostname", "IP",
        "Distance from Server", "Measurement Method", "Data Status", "Last Updated"};
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        private static final long serialVersionUID = 1L;
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final RoomMapPanel map = new RoomMapPanel();
    private final JLabel summary = new JLabel();
    private List<ClientSnapshot> clients = List.of();

    public ServerClientDistanceManagementPanel() {
        super(new BorderLayout(0, 10));
        setBackground(DashboardColors.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) map.selectClient(null);
            else map.selectClient(clients.get(table.convertRowIndexToModel(viewRow)).clientId());
        });
        JButton zoomIn = new JButton("Zoom +");
        JButton zoomOut = new JButton("Zoom -");
        JButton fit = new JButton("Fit to View");
        zoomIn.addActionListener(event -> map.zoomIn());
        zoomOut.addActionListener(event -> map.zoomOut());
        fit.addActionListener(event -> map.fitToView());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        controls.setOpaque(false);
        controls.add(summary);
        controls.add(zoomOut);
        controls.add(zoomIn);
        controls.add(fit);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(map));
        split.setResizeWeight(0.34);
        split.setDividerLocation(210);
        add(controls, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    public void setClients(List<ClientSnapshot> values, String selectedClientId) {
        List<ClientSnapshot> copy = new ArrayList<>(values == null ? List.of() : values);
        copy.sort(Comparator.comparing(ClientSnapshot::hostname, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ClientSnapshot::clientId));
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setClients(copy, selectedClientId));
            return;
        }
        clients = List.copyOf(copy);
        model.setRowCount(0);
        int available = 0;
        for (ClientSnapshot client : clients) {
            ServerClientDistance distance = client.serverClientDistance();
            if (distance.distanceMeters() != null) available++;
            model.addRow(new Object[]{client.clientId(), client.hostname(), client.ipAddress(),
                distance.distanceMeters() == null ? distance.status().name()
                        : String.format(Locale.ROOT, "%.2f m", distance.distanceMeters()),
                distance.measurementMethod(), distance.status(),
                distance.lastUpdated() <= 0 ? "NOT_AVAILABLE"
                        : TIME.format(Instant.ofEpochMilli(distance.lastUpdated()))});
        }
        summary.setText(available + "/" + clients.size() + " distances available");
        map.setClients(clients, selectedClientId);
        selectClient(selectedClientId);
    }

    public void selectClient(String clientId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> selectClient(clientId));
            return;
        }
        map.selectClient(clientId);
        table.clearSelection();
        if (clientId == null) return;
        for (int modelRow = 0; modelRow < clients.size(); modelRow++) {
            if (!clientId.equals(clients.get(modelRow).clientId())) continue;
            int viewRow = table.convertRowIndexToView(modelRow);
            table.setRowSelectionInterval(viewRow, viewRow);
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
            break;
        }
    }

    public String selectedClientIdForRefresh() { return map.selectedClientId(); }

    int rowCount() { return model.getRowCount(); }
    Object cell(int row, int column) { return model.getValueAt(row, column); }
    int plottedClientCount() { return map.plottedClientCount(); }
    String selectedClientId() { return map.selectedClientId(); }
    double zoomFactor() { return map.zoomFactor(); }
}
