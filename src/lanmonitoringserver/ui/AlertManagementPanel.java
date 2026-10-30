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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.service.LanServerService.NotificationSendResult;

/** Server-side current-alert/history view and rule editor. */
public final class AlertManagementPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String[] COLUMNS = {"Alert ID", "Client", "Hostname", "Resource",
        "Severity", "Value", "Threshold", "Status", "Delivery", "Created", "Handled"};

    private final DefaultTableModel currentModel = model();
    private final DefaultTableModel historyModel = model();
    private final JTable currentTable = new JTable(currentModel);
    private final JButton acknowledgeButton = new JButton("Acknowledge");
    private final JButton resolveButton = new JButton("Resolve");
    private final JTextField clientFilter = new JTextField(16);
    private final JComboBox<String> metricFilter = new JComboBox<>();
    private final JComboBox<String> severityFilter = new JComboBox<>(new String[]{"ALL", "WARNING", "CRITICAL"});
    private final JComboBox<String> timeFilter = new JComboBox<>(new String[]{"ALL TIME", "24 HOURS", "7 DAYS"});
    private final JComboBox<AlertMetric> ruleMetric = new JComboBox<>(new AlertMetric[]{
        AlertMetric.CPU, AlertMetric.GPU, AlertMetric.DISK_ACTIVE, AlertMetric.DISK_CAPACITY});
    private final JCheckBox enabled = new JCheckBox("Enabled", true);
    private final JSpinner warning = spinner(80, 0, 100, 1);
    private final JSpinner critical = spinner(90, 0, 100, 1);
    private final JSpinner duration = spinner(5, 0, 3600, 1);
    private final JSpinner hysteresis = spinner(5, 0, 100, 1);
    private final JSpinner cooldown = spinner(60, 0, 86400, 1);
    private List<AlertRecord> alerts = List.of();
    private List<AlertRule> rules = List.of();
    private Consumer<String> acknowledgeAction = ignored -> {};
    private Consumer<String> resolveAction = ignored -> {};
    private Consumer<AlertRule> ruleAction = ignored -> {};
    private final boolean managementAllowed;
    private final AdminNotificationPanel notificationPanel;

    public AlertManagementPanel() {
        this(true);
    }

    public AlertManagementPanel(boolean managementAllowed) {
        super(new BorderLayout(0, 10));
        this.managementAllowed = managementAllowed;
        notificationPanel = new AdminNotificationPanel(managementAllowed);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        metricFilter.addItem("ALL");
        for (AlertMetric metric : AlertMetric.values()) metricFilter.addItem(metric.name());
        add(filters(), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Current Alerts", tablePanel(currentTable, true));
        tabs.addTab("Alert History", tablePanel(new JTable(historyModel), false));
        tabs.addTab("Send Notification", notificationPanel);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, ruleEditor());
        split.setResizeWeight(0.75);
        add(split, BorderLayout.CENTER);
        clientFilter.getDocument().addDocumentListener(new SimpleDocumentListener(this::refresh));
        metricFilter.addActionListener(event -> refresh());
        severityFilter.addActionListener(event -> refresh());
        timeFilter.addActionListener(event -> refresh());
        ruleMetric.addActionListener(event -> loadSelectedRule());
        acknowledgeButton.setEnabled(managementAllowed);
        resolveButton.setEnabled(managementAllowed);
        enabled.setEnabled(managementAllowed);
        ruleMetric.setEnabled(managementAllowed);
        warning.setEnabled(managementAllowed);
        critical.setEnabled(managementAllowed);
        duration.setEnabled(managementAllowed);
        hysteresis.setEnabled(managementAllowed);
        cooldown.setEnabled(managementAllowed);
    }

    private JPanel filters() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.add(new JLabel("Client:")); panel.add(clientFilter);
        panel.add(new JLabel("Resource:")); panel.add(metricFilter);
        panel.add(new JLabel("Severity:")); panel.add(severityFilter);
        panel.add(new JLabel("Time:")); panel.add(timeFilter);
        return panel;
    }

    private JPanel tablePanel(JTable table, boolean actions) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        table.setAutoCreateRowSorter(true);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        if (actions) {
            acknowledgeButton.setToolTipText("Admin đã xem; cảnh báo vẫn mở cho tới khi hồi phục/Resolve");
            acknowledgeButton.addActionListener(event -> selected(currentTable, acknowledgeAction));
            resolveButton.setToolTipText("Đóng cảnh báo thủ công và bắt đầu cooldown");
            resolveButton.addActionListener(event -> selected(currentTable, resolveAction));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(acknowledgeButton); buttons.add(resolveButton);
            panel.add(buttons, BorderLayout.SOUTH);
        }
        return panel;
    }

    private JPanel ruleEditor() {
        JPanel panel = new JPanel(new GridLayout(2, 7, 8, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Alert Rule Configuration"));
        for (String label : new String[]{"Resource", "Warning > %", "Critical > %",
                "Duration (s)", "Hysteresis %", "Cooldown (s)", "State"}) panel.add(new JLabel(label));
        panel.add(ruleMetric); panel.add(warning); panel.add(critical); panel.add(duration);
        panel.add(hysteresis); panel.add(cooldown);
        JPanel action = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        action.add(enabled);
        JButton apply = new JButton("Apply");
        apply.setEnabled(managementAllowed);
        apply.addActionListener(event -> applyRule());
        action.add(apply);
        panel.add(action);
        return panel;
    }

    public void setAlerts(List<AlertRecord> updated) {
        runOnEdt(() -> {
            alerts = List.copyOf(updated); refresh(); notificationPanel.setNotifications(alerts);
        });
    }

    public void setNotificationClients(List<ClientSnapshot> clients,
            Function<String, ClientPermissions> permissions) {
        notificationPanel.setClients(clients, permissions);
    }

    public void setNotificationAction(Function<AdminNotificationPanel.Request,
            CompletableFuture<NotificationSendResult>> action) {
        notificationPanel.setAction(action);
    }

    public void setRules(List<AlertRule> updated) {
        runOnEdt(() -> { rules = List.copyOf(updated); loadSelectedRule(); });
    }

    public void setActions(Consumer<String> acknowledge, Consumer<String> resolve,
            Consumer<AlertRule> updateRule) {
        acknowledgeAction = acknowledge == null ? ignored -> {} : acknowledge;
        resolveAction = resolve == null ? ignored -> {} : resolve;
        ruleAction = updateRule == null ? ignored -> {} : updateRule;
    }

    private void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        currentModel.setRowCount(0);
        historyModel.setRowCount(0);
        for (AlertRecord alert : alerts) {
            if (!matches(alert)) continue;
            (alert.status() == AlertStatus.RESOLVED ? historyModel : currentModel).addRow(row(alert));
        }
    }

    private boolean matches(AlertRecord alert) {
        String query = clientFilter.getText().trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty() && !(alert.clientId() + " " + alert.hostname())
                .toLowerCase(Locale.ROOT).contains(query)) return false;
        if (!"ALL".equals(metricFilter.getSelectedItem())
                && !alert.metric().name().equals(metricFilter.getSelectedItem())) return false;
        if (!"ALL".equals(severityFilter.getSelectedItem())
                && !alert.severity().name().equals(severityFilter.getSelectedItem())) return false;
        long age = System.currentTimeMillis() - alert.createdAt();
        if ("24 HOURS".equals(timeFilter.getSelectedItem()) && age > 86_400_000L) return false;
        return !"7 DAYS".equals(timeFilter.getSelectedItem()) || age <= 604_800_000L;
    }

    private Object[] row(AlertRecord alert) {
        return new Object[]{alert.id(), alert.clientId(), alert.hostname(), alert.metric().displayName(),
            alert.severity(), String.format("%.1f%%", alert.value()),
            String.format("> %.1f%%", alert.threshold()), alert.status(), alert.deliveryStatus(),
            TIME.format(Instant.ofEpochMilli(alert.createdAt())),
            alert.resolvedAt() == null ? "" : TIME.format(Instant.ofEpochMilli(alert.resolvedAt()))};
    }

    private void selected(JTable table, Consumer<String> action) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        action.accept((String) currentModel.getValueAt(modelRow, 0));
    }

    private void loadSelectedRule() {
        AlertMetric metric = (AlertMetric) ruleMetric.getSelectedItem();
        AlertRule rule = rules.stream().filter(value -> value.metric() == metric)
                .findFirst().orElse(metric == null ? AlertRule.defaults(AlertMetric.CPU) : AlertRule.defaults(metric));
        enabled.setSelected(rule.enabled()); warning.setValue(rule.warningThreshold());
        critical.setValue(rule.criticalThreshold()); duration.setValue(rule.durationMillis() / 1000d);
        hysteresis.setValue(rule.hysteresisPercent()); cooldown.setValue(rule.cooldownMillis() / 1000d);
    }

    private void applyRule() {
        try {
            AlertRule rule = new AlertRule((AlertMetric) ruleMetric.getSelectedItem(), enabled.isSelected(),
                    number(warning), number(critical), Math.round(number(duration) * 1000),
                    number(hysteresis), Math.round(number(cooldown) * 1000));
            List<AlertRule> updated = new ArrayList<>(rules);
            updated.removeIf(existing -> existing.metric() == rule.metric());
            updated.add(rule);
            rules = List.copyOf(updated);
            ruleAction.accept(rule);
        } catch (IllegalArgumentException exception) {
            javax.swing.JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Invalid Alert Rule", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private static DefaultTableModel model() {
        return new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
    private static JSpinner spinner(double value, double min, double max, double step) {
        return new JSpinner(new SpinnerNumberModel(value, min, max, step));
    }
    private static double number(JSpinner spinner) { return ((Number) spinner.getValue()).doubleValue(); }
    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    int currentCount() { return currentModel.getRowCount(); }
    int historyCount() { return historyModel.getRowCount(); }
    void setClientFilter(String value) { clientFilter.setText(value); }
    void setMetricFilter(String value) { metricFilter.setSelectedItem(value); }
    void setSeverityFilter(String value) { severityFilter.setSelectedItem(value); }
    void setTimeFilter(String value) { timeFilter.setSelectedItem(value); }
    void selectCurrentRow(int row) { currentTable.setRowSelectionInterval(row, row); }
    void clickAcknowledge() { acknowledgeButton.doClick(); }
    void clickResolve() { resolveButton.doClick(); }
    boolean managementControlsEnabled() {
        return acknowledgeButton.isEnabled() && resolveButton.isEnabled() && ruleMetric.isEnabled();
    }

    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable action;
        private SimpleDocumentListener(Runnable action) { this.action = action; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
    }
}
