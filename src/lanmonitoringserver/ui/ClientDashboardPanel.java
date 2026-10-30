package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;

public final class ClientDashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int UPDATE_INTERVAL_MILLIS = 250;

    private final Map<String, ClientSnapshot> clients = new LinkedHashMap<>();
    private final Map<String, ClientCard> cards = new LinkedHashMap<>();
    private final Map<String, ClientSnapshot> pendingUpdates = new LinkedHashMap<>();
    private final Set<String> selectedClientIds = new LinkedHashSet<>();
    private final JPanel cardsPanel = new ScrollableWrapPanel(
            new WrapLayout(FlowLayout.LEFT, 16, 16));
    private final JTextField searchField = new JTextField();
    private final JComboBox<String> filterBox = new JComboBox<>(new String[]{
        "Tất cả", "Online", "Offline", "Warning", "Critical"
    });
    private final JLabel onlineValue = statValue(DashboardColors.ONLINE);
    private final JLabel offlineValue = statValue(DashboardColors.OFFLINE);
    private final JLabel alertValue = statValue(DashboardColors.WARNING);
    private final JLabel totalValue = statValue(DashboardColors.PRIMARY);
    private final JLabel selectedLabel = new JLabel("0 client đã chọn");
    private final JLabel emptyLabel = new JLabel("Chưa có client phù hợp", JLabel.CENTER);
    private final JButton detailButton = new JButton("Xem chi tiết");
    private final Timer updateTimer;
    private Consumer<ClientSnapshot> openDetail = ignored -> {};
    private Runnable openAlerts = () -> {};
    private Runnable openFileTransfer = () -> {};
    private Runnable openAnsible = () -> {};
    private Consumer<ClientSnapshot> openDistanceMap = ignored -> {};

    public ClientDashboardPanel() {
        super(new BorderLayout(0, 14));
        setBackground(DashboardColors.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setPreferredSize(new Dimension(1420, 610));
        cardsPanel.setBackground(DashboardColors.BACKGROUND);
        cardsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
        add(createTopSection(), BorderLayout.NORTH);
        add(createCardsScrollPane(), BorderLayout.CENTER);
        updateTimer = new Timer(UPDATE_INTERVAL_MILLIS, event -> applyPendingUpdates());
        updateTimer.setRepeats(false);
        installFilterListeners();
        updateSummary();
    }

    private JPanel createTopSection() {
        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        JLabel title = new JLabel("CLIENT DASHBOARD");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(DashboardColors.TEXT);
        JLabel subtitle = new JLabel("Theo dõi trạng thái thiết bị trong mạng LAN theo thời gian thực");
        subtitle.setForeground(DashboardColors.MUTED);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new javax.swing.BoxLayout(heading, javax.swing.BoxLayout.Y_AXIS));
        heading.add(title);
        heading.add(subtitle);
        titleRow.add(heading, BorderLayout.WEST);
        top.add(titleRow, BorderLayout.NORTH);

        JPanel statsAndTools = new JPanel(new BorderLayout(16, 0));
        statsAndTools.setOpaque(false);
        JPanel stats = new JPanel(new GridLayout(1, 4, 10, 0));
        stats.setOpaque(false);
        stats.add(statTile("Tổng client", totalValue));
        stats.add(statTile("Đang kết nối", onlineValue));
        stats.add(statTile("Offline", offlineValue));
        stats.add(statTile("Có cảnh báo", alertValue));
        statsAndTools.add(stats, BorderLayout.CENTER);
        statsAndTools.add(createToolbar(), BorderLayout.SOUTH);
        top.add(statsAndTools, BorderLayout.CENTER);
        return top;
    }

    private JPanel statTile(String title, JLabel value) {
        JPanel tile = new JPanel(new BorderLayout(8, 0));
        tile.setBackground(DashboardColors.SURFACE);
        tile.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DashboardColors.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(DashboardColors.MUTED);
        tile.add(titleLabel, BorderLayout.WEST);
        tile.add(value, BorderLayout.EAST);
        return tile;
    }

    private static JLabel statValue(Color color) {
        JLabel label = new JLabel("0");
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 20f));
        return label;
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setOpaque(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        searchField.setToolTipText("Tìm theo hostname, Client ID, IP hoặc hệ điều hành");
        searchField.putClientProperty("JTextField.placeholderText", "Tìm kiếm client...");
        searchField.setPreferredSize(new Dimension(360, 34));
        filterBox.setPreferredSize(new Dimension(130, 34));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(new JLabel("Tìm kiếm:"));
        left.add(searchField);
        left.add(new JLabel("Lọc:"));
        left.add(filterBox);
        toolbar.add(left, BorderLayout.WEST);

        selectedLabel.setForeground(DashboardColors.MUTED);
        detailButton.setEnabled(false);
        detailButton.addActionListener(event -> openFirstSelected());
        JButton clearButton = new JButton("Bỏ chọn");
        clearButton.addActionListener(event -> clearSelection());
        JButton alertsButton = new JButton("Alerts");
        alertsButton.addActionListener(event -> openAlerts.run());
        JButton filesButton = new JButton("File Transfer");
        filesButton.addActionListener(event -> openFileTransfer.run());
        JButton ansibleButton = new JButton("Ansible Management");
        ansibleButton.addActionListener(event -> openAnsible.run());
        JButton distancesButton = new JButton("Distance Map");
        distancesButton.addActionListener(event -> openDistanceMap.accept(null));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(selectedLabel);
        right.add(ansibleButton);
        right.add(distancesButton);
        right.add(filesButton);
        right.add(detailButton);
        right.add(alertsButton);
        right.add(clearButton);
        toolbar.add(right, BorderLayout.EAST);
        return toolbar;
    }

    private JScrollPane createCardsScrollPane() {
        emptyLabel.setForeground(DashboardColors.MUTED);
        emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.BOLD, 15f));
        cardsPanel.add(emptyLabel);
        JScrollPane scroll = new JScrollPane(cardsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(DashboardColors.BACKGROUND);
        return scroll;
    }

    private void installFilterListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        filterBox.addActionListener(event -> applyFilter());
    }

    /** May be called from any thread. Updates are coalesced before touching Swing components. */
    public void updateClients(List<ClientSnapshot> snapshots) {
        List<ClientSnapshot> immutableCopy = List.copyOf(snapshots);
        Runnable enqueue = () -> {
            for (ClientSnapshot snapshot : immutableCopy) {
                pendingUpdates.put(snapshot.clientId(), snapshot);
            }
            if (!updateTimer.isRunning()) updateTimer.start();
        };
        if (SwingUtilities.isEventDispatchThread()) enqueue.run();
        else SwingUtilities.invokeLater(enqueue);
    }

    private void applyPendingUpdates() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyPendingUpdates);
            return;
        }
        if (pendingUpdates.isEmpty()) return;
        List<ClientSnapshot> updates = new ArrayList<>(pendingUpdates.values());
        pendingUpdates.clear();
        for (ClientSnapshot snapshot : updates) {
            ClientSnapshot previous = clients.put(snapshot.clientId(), snapshot);
            ClientCard card = cards.get(snapshot.clientId());
            if (card == null) {
                card = new ClientCard(snapshot);
                card.setOpenDetail(openDetail);
                card.setOpenDistanceMap(openDistanceMap);
                card.setSelectionChanged(this::selectionChanged);
                cards.put(snapshot.clientId(), card);
                cardsPanel.add(card);
            } else if (!snapshot.equals(previous)) {
                card.updateClient(snapshot);
            }
        }
        updateSummary();
        applyFilter();
    }

    private void applyFilter() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyFilter);
            return;
        }
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String filter = (String) filterBox.getSelectedItem();
        int visible = 0;
        for (Map.Entry<String, ClientCard> entry : cards.entrySet()) {
            ClientSnapshot client = clients.get(entry.getKey());
            ClientHealthLevel health = ClientHealthLevel.from(client);
            String searchable = (client.clientId() + " " + client.hostname() + " "
                    + client.ipAddress() + " " + client.operatingSystem()).toLowerCase(Locale.ROOT);
            boolean queryMatches = query.isEmpty() || searchable.contains(query);
            boolean filterMatches = "Tất cả".equals(filter)
                    || health.displayName().equalsIgnoreCase(filter);
            boolean show = queryMatches && filterMatches;
            entry.getValue().setVisible(show);
            if (show) visible++;
        }
        emptyLabel.setVisible(visible == 0);
        cardsPanel.revalidate();
        cardsPanel.repaint();
    }

    private void updateSummary() {
        int online = 0;
        int offline = 0;
        int alerts = 0;
        for (ClientSnapshot client : clients.values()) {
            if (client.status() == ClientStatus.ONLINE) online++; else offline++;
            ClientHealthLevel level = ClientHealthLevel.from(client);
            if (level == ClientHealthLevel.WARNING || level == ClientHealthLevel.CRITICAL) alerts++;
        }
        totalValue.setText(String.valueOf(clients.size()));
        onlineValue.setText(String.valueOf(online));
        offlineValue.setText(String.valueOf(offline));
        alertValue.setText(String.valueOf(alerts));
    }

    private void selectionChanged(ClientCard card) {
        if (card.isSelected()) selectedClientIds.add(card.clientId());
        else selectedClientIds.remove(card.clientId());
        selectedLabel.setText(selectedClientIds.size() + " client đã chọn");
        detailButton.setEnabled(!selectedClientIds.isEmpty());
        card.repaint();
    }

    private void clearSelection() {
        for (ClientCard card : cards.values()) {
            if (card.isSelected()) card.setSelected(false);
        }
    }

    private void openFirstSelected() {
        if (selectedClientIds.isEmpty()) return;
        ClientSnapshot selected = clients.get(selectedClientIds.iterator().next());
        if (selected != null) openDetail.accept(selected);
    }

    public void setOpenDetail(Consumer<ClientSnapshot> listener) {
        openDetail = listener == null ? ignored -> {} : listener;
        for (ClientCard card : cards.values()) card.setOpenDetail(openDetail);
    }

    public void setOpenAlerts(Runnable listener) {
        openAlerts = listener == null ? () -> {} : listener;
    }

    public void setOpenFileTransfer(Runnable listener) {
        openFileTransfer = listener == null ? () -> {} : listener;
    }

    public void setOpenAnsible(Runnable listener) {
        openAnsible = listener == null ? () -> {} : listener;
    }

    public void setOpenDistanceMap(Consumer<ClientSnapshot> listener) {
        openDistanceMap = listener == null ? ignored -> {} : listener;
        for (ClientCard card : cards.values()) card.setOpenDistanceMap(openDistanceMap);
    }

    public Set<String> selectedClientIds() {
        return Set.copyOf(selectedClientIds);
    }

    /** Explicit removal only; offline snapshots are intentionally retained. */
    public void removeClient(String clientId) {
        Runnable remove = () -> {
            clients.remove(clientId);
            pendingUpdates.remove(clientId);
            selectedClientIds.remove(clientId);
            ClientCard card = cards.remove(clientId);
            if (card != null) cardsPanel.remove(card);
            updateSummary();
            applyFilter();
            selectedLabel.setText(selectedClientIds.size() + " client đã chọn");
            detailButton.setEnabled(!selectedClientIds.isEmpty());
        };
        if (SwingUtilities.isEventDispatchThread()) remove.run();
        else SwingUtilities.invokeLater(remove);
    }

    // Package-private diagnostics used by the headless Swing tests.
    void flushPendingUpdates() { applyPendingUpdates(); }
    int cardCount() { return cards.size(); }
    int visibleCardCount() {
        return (int) cards.values().stream().filter(ClientCard::isVisible).count();
    }
    ClientCard card(String clientId) { return cards.get(clientId); }
    void setSearchText(String text) { searchField.setText(text); }
    void setFilter(String filter) { filterBox.setSelectedItem(filter); }
    int onlineCount() { return Integer.parseInt(onlineValue.getText()); }
    int offlineCount() { return Integer.parseInt(offlineValue.getText()); }
    int alertCount() { return Integer.parseInt(alertValue.getText()); }
}
