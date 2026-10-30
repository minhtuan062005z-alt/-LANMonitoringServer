package lanmonitoringserver.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;

public final class ClientCard extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JCheckBox selectedBox = new JCheckBox();
    private final JLabel hostnameLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel idLabel = new JLabel();
    private final JLabel ipLabel = new JLabel();
    private final JLabel osLabel = new JLabel();
    private final JLabel rttLabel = new JLabel();
    private final JLabel distanceLabel = new JLabel();
    private final JLabel lastSeenLabel = new JLabel();
    private final JButton mapButton = new JButton("Open Room Map");
    private final MetricBar cpuBar = new MetricBar("CPU");
    private final MetricBar ramBar = new MetricBar("RAM");
    private final MetricBar gpuBar = new MetricBar("GPU");
    private final MetricBar diskActiveBar = new MetricBar("Disk Active");
    private final MetricBar diskCapacityBar = new MetricBar("Disk Capacity");
    private ClientSnapshot client;
    private ClientHealthLevel health = ClientHealthLevel.OFFLINE;
    private Consumer<ClientSnapshot> openDetail = ignored -> {};
    private Consumer<ClientSnapshot> openDistanceMap = ignored -> {};
    private Consumer<ClientCard> selectionChanged = ignored -> {};

    public ClientCard(ClientSnapshot client) {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        setPreferredSize(new Dimension(325, 445));
        setMinimumSize(new Dimension(300, 425));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(createHeader());
        content.add(Box.createVerticalStrut(10));
        content.add(idLabel);
        content.add(Box.createVerticalStrut(4));
        content.add(ipLabel);
        content.add(Box.createVerticalStrut(4));
        content.add(osLabel);
        content.add(Box.createVerticalStrut(12));
        content.add(cpuBar);
        content.add(Box.createVerticalStrut(8));
        content.add(ramBar);
        content.add(Box.createVerticalStrut(8));
        content.add(gpuBar);
        content.add(Box.createVerticalStrut(8));
        content.add(diskActiveBar);
        content.add(Box.createVerticalStrut(8));
        content.add(diskCapacityBar);
        content.add(Box.createVerticalStrut(12));
        content.add(rttLabel);
        content.add(Box.createVerticalStrut(4));
        content.add(distanceLabel);
        content.add(Box.createVerticalStrut(4));
        content.add(lastSeenLabel);
        content.add(Box.createVerticalStrut(8));
        mapButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        mapButton.addActionListener(event -> {
            if (client != null) openDistanceMap.accept(client);
        });
        content.add(mapButton);
        add(content, BorderLayout.CENTER);

        selectedBox.setOpaque(false);
        selectedBox.setToolTipText("Chọn client cho thao tác nhóm");
        selectedBox.addActionListener(event -> selectionChanged.accept(this));
        installOpenDetailHandler(this);
        updateClient(client);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        hostnameLabel.setForeground(DashboardColors.TEXT);
        hostnameLabel.setFont(hostnameLabel.getFont().deriveFont(Font.BOLD, 17f));
        statusLabel.setOpaque(true);
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel right = new JPanel(new BorderLayout(5, 0));
        right.setOpaque(false);
        right.add(statusLabel, BorderLayout.CENTER);
        right.add(selectedBox, BorderLayout.EAST);
        header.add(hostnameLabel, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    public void updateClient(ClientSnapshot updated) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateClient(updated));
            return;
        }
        client = updated;
        health = ClientHealthLevel.from(updated);
        hostnameLabel.setText(updated.hostname());
        hostnameLabel.setToolTipText(updated.hostname());
        statusLabel.setText(health.displayName().toUpperCase());
        if (updated.activeAlertCount() > 0) {
            statusLabel.setText(updated.activeAlertSeverity() + " · "
                    + updated.activeAlertCount() + " ALERT");
            statusLabel.setBackground(updated.activeAlertSeverity() == lanmonitoringserver.model.AlertSeverity.CRITICAL
                    ? DashboardColors.CRITICAL : DashboardColors.WARNING);
        } else {
            statusLabel.setBackground(health.color());
        }
        idLabel.setText("ID: " + abbreviate(updated.clientId(), 34));
        idLabel.setToolTipText(updated.clientId());
        ipLabel.setText("IP: " + updated.ipAddress());
        osLabel.setText("OS: " + abbreviate(updated.operatingSystem(), 38));
        osLabel.setToolTipText(updated.operatingSystem());
        styleSecondary(idLabel);
        styleSecondary(ipLabel);
        styleSecondary(osLabel);
        if (updated.status() == ClientStatus.ONLINE) {
            cpuBar.setReading(updated.metrics().cpuUsage());
            ramBar.setReading(updated.metrics().ramUsage());
            gpuBar.setReading(updated.metrics().gpuUsage());
            diskActiveBar.setReading(updated.metrics().diskActive());
            diskCapacityBar.setReading(updated.metrics().diskCapacity());
        } else {
            cpuBar.setReading(null);
            ramBar.setReading(null);
            gpuBar.setReading(null);
            diskActiveBar.setReading(null);
            diskCapacityBar.setReading(null);
        }
        rttLabel.setText("RTT: " + (updated.responseTimeMillis() == null
                ? "Chưa có dữ liệu" : updated.responseTimeMillis() + " ms"));
        var physicalDistance = updated.serverClientDistance();
        distanceLabel.setText("Distance from Server: " + (physicalDistance.distanceMeters() == null
                ? physicalDistance.status() + " (" + distanceSourceName(physicalDistance) + ")"
                : String.format(java.util.Locale.ROOT, "%.2f m (%s)",
                        physicalDistance.distanceMeters(),
                        distanceSourceName(physicalDistance))));
        lastSeenLabel.setText("Last Seen: " + TIME_FORMAT.format(Instant.ofEpochMilli(updated.lastSeenTime())));
        styleSecondary(rttLabel);
        styleSecondary(distanceLabel);
        styleSecondary(lastSeenLabel);
        repaint();
    }

    private void styleSecondary(JLabel label) {
        label.setForeground(DashboardColors.MUTED);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
    }

    private String abbreviate(String value, int maximum) {
        if (value == null || value.isBlank()) return "Không có dữ liệu";
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private void installOpenDetailHandler(Component component) {
        if (component == selectedBox || component == mapButton) return;
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (client != null) openDetail.accept(client);
            }
        });
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) installOpenDetailHandler(child);
        }
    }

    public String clientId() { return client.clientId(); }
    public ClientSnapshot client() { return client; }
    public ClientHealthLevel health() { return health; }
    String gpuDisplayText() { return gpuBar.displayedValue(); }
    String diskActiveDisplayText() { return diskActiveBar.displayedValue(); }
    String diskCapacityDisplayText() { return diskCapacityBar.displayedValue(); }
    String statusDisplayText() { return statusLabel.getText(); }
    String distanceDisplayText() { return distanceLabel.getText(); }
    void clickMapButton() { mapButton.doClick(); }
    public boolean isSelected() { return selectedBox.isSelected(); }
    public void setSelected(boolean selected) {
        selectedBox.setSelected(selected);
        selectionChanged.accept(this);
    }
    public void setOpenDetail(Consumer<ClientSnapshot> listener) {
        openDetail = listener == null ? ignored -> {} : listener;
    }
    public void setOpenDistanceMap(Consumer<ClientSnapshot> listener) {
        openDistanceMap = listener == null ? ignored -> {} : listener;
    }
    public void setSelectionChanged(Consumer<ClientCard> listener) {
        selectionChanged = listener == null ? ignored -> {} : listener;
    }

    private String distanceSourceName(lanmonitoringserver.model.ServerClientDistance distance) {
        if (distance.dataSource() == lanmonitoringserver.model.DistanceDataSource.WIFI_FINGERPRINT) {
            return "Wi-Fi Fingerprint Estimate";
        }
        return switch (distance.measurementMethod()) {
            case MANUAL_MEASUREMENT -> "Manual";
            case POSITION_BASED_ESTIMATION -> "Position-Based";
            case DIRECT_MEASUREMENT -> "Direct";
            case NOT_AVAILABLE -> "NOT_AVAILABLE";
        };
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        copy.setColor(DashboardColors.SURFACE);
        copy.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
        copy.setStroke(new BasicStroke(isSelected() ? 2f : 1f));
        copy.setColor(isSelected() ? DashboardColors.PRIMARY : health.color());
        copy.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
        copy.dispose();
        super.paintComponent(graphics);
    }
}
