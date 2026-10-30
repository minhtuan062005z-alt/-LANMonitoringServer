package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.WirelessObservation;

/** RSSI-based distance to phone hotspot; never claims server-to-client ranging. */
public final class HotspotDistanceManagementPanel extends JPanel {
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Client", "IP", "RSSI (dBm)", "Client - Hotspot", "Status", "Updated"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final java.util.Map<String, WirelessObservation> observations = new java.util.concurrent.ConcurrentHashMap<>();
    private final JLabel summary = new JLabel("Waiting for Wi-Fi measurements");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public HotspotDistanceManagementPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(new JLabel("Client to PHONE HOTSPOT (RSSI estimate). Calibration required; not distance to Server."), BorderLayout.NORTH);
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);
    }

    public void accept(String clientId, WirelessObservation observation) {
        if (clientId != null && observation != null) observations.put(clientId, observation);
    }

    public void setClients(List<ClientSnapshot> clients) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setClients(clients));
            return;
        }
        model.setRowCount(0);
        int ready = 0;
        for (ClientSnapshot c : clients) {
            WirelessObservation o = observations.get(c.clientId());
            if (o == null) {
                model.addRow(new Object[]{c.hostname(), c.ipAddress(), "--", "NOT_AVAILABLE", "NO_DATA", "--"});
                continue;
            }
            var sig = o.signal();
            var d = o.distance();
            boolean stale = sig.isStale(System.currentTimeMillis());
            Double meters = stale ? null : d.estimatedMeters();
            if (meters != null) ready++;
            model.addRow(new Object[]{c.hostname(), c.ipAddress(),
                    sig.rssiDbm() == null ? "--" : String.format(Locale.ROOT, "%.0f", sig.rssiDbm()),
                    meters == null ? "NOT_AVAILABLE" : String.format(Locale.ROOT, "%.2f m", meters),
                    stale ? "STALE" : d.status().name(),
                    sig.timestamp() <= 0 ? "--" : CLOCK.format(Instant.ofEpochMilli(sig.timestamp()))});
        }
        summary.setText(ready + "/" + clients.size() + " clients with calibrated RSSI distance");
    }
}
