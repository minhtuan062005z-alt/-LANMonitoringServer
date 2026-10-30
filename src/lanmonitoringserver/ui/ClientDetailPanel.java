package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.protocol.SystemInfoMapper;
import lanmonitoringserver.util.SystemInfoTextFormatter;

public final class ClientDetailPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JLabel titleLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel overviewId = new JLabel();
    private final JLabel overviewIp = new JLabel();
    private final JLabel overviewOs = new JLabel();
    private final JLabel overviewLastSeen = new JLabel();
    private final SystemInfoPanel systemInfoPanel = new SystemInfoPanel();
    private final JButton refreshButton = new JButton("Refresh System Info");
    private final JButton exportJsonButton = new JButton("Export JSON");
    private final JButton exportTextButton = new JButton("Export TXT");
    private final PerformancePanel performancePanel = new PerformancePanel();
    private final JLabel networkIp = new JLabel();
    private final JLabel networkRtt = new JLabel();
    private final JLabel networkUpdated = new JLabel();
    private final WirelessNetworkPanel wirelessNetworkPanel = new WirelessNetworkPanel();
    private final ServerClientDistancePanel serverClientDistancePanel = new ServerClientDistancePanel();
    private volatile ClientSnapshot client;
    private Consumer<String> refreshAction = ignored -> {};

    public ClientDetailPanel() {
        super(new BorderLayout(0, 14));
        setBackground(DashboardColors.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        add(createHeader(), BorderLayout.NORTH);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13f));
        tabs.addTab("Overview", createOverviewTab());
        tabs.addTab("System Information", systemInfoPanel);
        tabs.addTab("Performance", performancePanel);
        tabs.addTab("File Transfer", placeholder("File Transfer"));
        tabs.addTab("Alerts", alertHelpTab());
        tabs.addTab("Network", wirelessNetworkPanel);
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 22f));
        titleLabel.setForeground(DashboardColors.TEXT);
        statusLabel.setOpaque(true);
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        refreshButton.addActionListener(event -> {
            if (client != null) refreshAction.accept(client.clientId());
        });
        exportJsonButton.addActionListener(event -> exportSystemInfo("json"));
        exportTextButton.addActionListener(event -> exportSystemInfo("txt"));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(refreshButton);
        actions.add(exportJsonButton);
        actions.add(exportTextButton);
        actions.add(statusLabel);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel createOverviewTab() {
        JPanel panel = tabPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (JLabel label : new JLabel[]{overviewId, overviewIp, overviewOs, overviewLastSeen}) {
            label.setFont(label.getFont().deriveFont(14f));
            label.setForeground(DashboardColors.TEXT);
            label.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
            panel.add(label);
        }
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createNetworkTab() {
        JPanel panel = tabPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(networkIp);
        panel.add(Box.createVerticalStrut(12));
        panel.add(networkRtt);
        panel.add(Box.createVerticalStrut(12));
        panel.add(networkUpdated);
        panel.add(Box.createVerticalStrut(24));
        JLabel note = new JLabel("Quản lý Wi-Fi: Chưa triển khai");
        note.setForeground(DashboardColors.MUTED);
        panel.add(note);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel placeholder(String feature) {
        JPanel panel = tabPanel();
        panel.setLayout(new BorderLayout());
        JLabel label = new JLabel(feature + " - Chưa triển khai", SwingConstants.CENTER);
        label.setForeground(DashboardColors.MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private JPanel alertHelpTab() {
        JPanel panel = tabPanel();
        panel.setLayout(new BorderLayout());
        JLabel label = new JLabel("Xem Current Alerts và Alert History từ nút Alerts trên Dashboard",
                SwingConstants.CENTER);
        label.setForeground(DashboardColors.MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private JPanel tabPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(DashboardColors.SURFACE);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        return panel;
    }

    public void updateClient(ClientSnapshot updated) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateClient(updated));
            return;
        }
        client = updated;
        ClientHealthLevel health = ClientHealthLevel.from(updated);
        titleLabel.setText(updated.hostname());
        statusLabel.setText(health.displayName().toUpperCase());
        statusLabel.setBackground(health.color());
        overviewId.setText("Client ID: " + updated.clientId());
        overviewIp.setText("Địa chỉ IP: " + updated.ipAddress());
        overviewOs.setText("Hệ điều hành: " + valueOrUnavailable(updated.operatingSystem()));
        overviewLastSeen.setText("Cập nhật cuối: " + formatTime(updated.lastSeenTime()));
        systemInfoPanel.updateInformation(updated.systemInformation());
        refreshButton.setEnabled(updated.status() == ClientStatus.ONLINE);
        boolean canExport = updated.systemInformation() != null;
        exportJsonButton.setEnabled(canExport);
        exportTextButton.setEnabled(canExport);
        networkIp.setText("IP Address: " + updated.ipAddress());
        networkRtt.setText("Ping / RTT: " + (updated.responseTimeMillis() == null
                ? "Chưa có dữ liệu" : updated.responseTimeMillis() + " ms"));
        networkUpdated.setText("Last Seen: " + formatTime(updated.lastSeenTime()));
        wirelessNetworkPanel.setLatency(updated.responseTimeMillis() == null ? null
                : new lanmonitoringserver.model.NetworkLatencySample(updated.lastSeenTime(),
                        lanmonitoringserver.model.MeasurementStatus.OK,
                        updated.responseTimeMillis(), "Application PING/PONG latency"));
        serverClientDistancePanel.setDistance(updated.serverClientDistance());
    }

    public ClientSnapshot client() { return client; }

    public void setTelemetryHistory(java.util.List<lanmonitoringserver.model.TelemetrySample> history) {
        performancePanel.setHistory(history);
    }

    public void acceptTelemetry(lanmonitoringserver.model.TelemetrySample sample) {
        performancePanel.accept(sample);
    }

    public void setWirelessHistory(java.util.List<lanmonitoringserver.model.WirelessObservation> history) {
        wirelessNetworkPanel.setHistory(history);
    }

    public void acceptWireless(lanmonitoringserver.model.WirelessObservation observation) {
        wirelessNetworkPanel.accept(observation);
    }

    public void setWirelessCalibrationAction(
            java.util.function.Consumer<java.util.List<lanmonitoringserver.model.WirelessCalibrationPoint>> action) {
        wirelessNetworkPanel.setCalibrationAction(action);
    }

    public void setRefreshAction(Consumer<String> action) {
        refreshAction = action == null ? ignored -> {} : action;
    }

    public void setServerPositionAction(Consumer<lanmonitoringserver.model.SpatialPosition> action) {
        serverClientDistancePanel.setServerPositionAction(action);
    }

    public void setClientPositionAction(Consumer<lanmonitoringserver.model.SpatialPosition> action) {
        serverClientDistancePanel.setClientPositionAction(action);
    }

    public void setManualDistanceAction(
            Consumer<ServerClientDistancePanel.ManualDistanceInput> action) {
        serverClientDistancePanel.setManualDistanceAction(action);
    }

    public void setClearDistanceAction(Runnable action) {
        serverClientDistancePanel.setClearAction(action);
    }

    public void setClearServerPositionAction(Runnable action) {
        serverClientDistancePanel.setClearServerAction(action);
    }

    private void exportSystemInfo(String extension) {
        SystemInformation information = client == null ? null : client.systemInformation();
        if (information == null) {
            JOptionPane.showMessageDialog(this, "Chưa có SYSTEM_INFO để export.",
                    "Export", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(client.hostname() + "-system-info." + extension));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String content = "json".equals(extension) ? SystemInfoMapper.toJson(information)
                    : SystemInfoTextFormatter.format(information);
            Files.writeString(chooser.getSelectedFile().toPath(), content, StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "Đã export: " + chooser.getSelectedFile(),
                    "Export", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "Không thể export: " + exception.getMessage(),
                    "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    int tabCount() { return tabs.getTabCount(); }
    String tabTitle(int index) { return tabs.getTitleAt(index); }
    SystemInformation displayedSystemInformation() { return systemInfoPanel.information(); }

    private String formatTime(long timestamp) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    private String valueOrUnavailable(String value) {
        return value == null || value.isBlank() ? "Không có dữ liệu" : value;
    }
}
