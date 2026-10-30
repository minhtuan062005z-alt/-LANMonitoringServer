package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.util.SwingTextLimiter;
import lanmonitoringserver.model.AnsibleHostResult;
import lanmonitoringserver.model.AnsibleJobResult;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.service.AnsibleService;

/** Server tab for explicit Ansible hosts, source content and per-host results. */
public final class AnsibleManagementPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final DefaultTableModel hostModel = readOnly("Inventory", "Name", "Platform",
            "Address", "Destination", "Enabled", "Java Client ID");
    private final DefaultTableModel resultModel = readOnly("Host", "Platform", "Status",
            "Changed", "SHA-256", "Detail");
    private final JTable hostsTable = new JTable(hostModel);
    private final JTable resultTable = new JTable(resultModel);
    private final JTextArea content = new JTextArea("Managed by LANMonitoringServer\n", 8, 60);
    private final JTextArea logs = new JTextArea(8, 60);
    private final JLabel sourceStatus = new JLabel("Source file has not been generated");
    private final JButton generate = new JButton("Generate File");
    private final JButton deploy = new JButton("Deploy / Sync to Clients");
    private final JButton verify = new JButton("Verify Synchronization");
    private final JButton cancel = new JButton("Cancel Job");
    private final JButton reload = new JButton("Reload Managed Hosts");
    private final boolean managementAllowed;
    private List<AnsibleManagedHost> hostValues = List.of();
    private Function<String, CompletableFuture<AnsibleService.GeneratedSource>> generateAction =
            ignored -> CompletableFuture.failedFuture(new IllegalStateException("Not configured"));
    private Function<Set<String>, CompletableFuture<AnsibleJobResult>> deployAction =
            ignored -> CompletableFuture.failedFuture(new IllegalStateException("Not configured"));
    private Function<Set<String>, CompletableFuture<AnsibleJobResult>> verifyAction = deployAction;
    private Runnable cancelAction = () -> {};
    private Supplier<List<AnsibleManagedHost>> reloadAction = List::of;

    public AnsibleManagementPanel(AuthorizationContext authorization) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        boolean allowed = authorization.role().canManageAnsible();
        managementAllowed = allowed;
        generate.setEnabled(allowed); deploy.setEnabled(allowed); verify.setEnabled(allowed);
        cancel.setEnabled(false);
        logs.setEditable(false); logs.setLineWrap(true); content.setLineWrap(true);
        hostsTable.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        reload.addActionListener(event -> reloadHostsAsync());
        generate.addActionListener(event -> generate());
        deploy.addActionListener(event -> run(false));
        verify.addActionListener(event -> run(true));
        cancel.addActionListener(event -> cancelAction.run());
        toolbar.add(new JLabel("Role: " + authorization.principal() + " / " + authorization.role()));
        toolbar.add(reload); toolbar.add(generate); toolbar.add(deploy); toolbar.add(verify); toolbar.add(cancel);
        add(toolbar, BorderLayout.NORTH);

        JPanel editor = new JPanel(new BorderLayout(4, 4));
        editor.setBorder(BorderFactory.createTitledBorder("UTF-8 content for lan_monitoring_sync.txt"));
        editor.add(new JScrollPane(content), BorderLayout.CENTER);
        editor.add(sourceStatus, BorderLayout.SOUTH);
        JPanel lower = new JPanel(new GridLayout(1, 2, 8, 0));
        lower.add(new JScrollPane(resultTable)); lower.add(new JScrollPane(logs));
        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(hostsTable), new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, lower));
        vertical.setResizeWeight(0.3);
        add(vertical, BorderLayout.CENTER);
    }

    public void setActions(Function<String, CompletableFuture<AnsibleService.GeneratedSource>> generate,
            Function<Set<String>, CompletableFuture<AnsibleJobResult>> deploy,
            Function<Set<String>, CompletableFuture<AnsibleJobResult>> verify,
            Runnable cancel, Supplier<List<AnsibleManagedHost>> reload) {
        this.generateAction = generate; this.deployAction = deploy; this.verifyAction = verify;
        this.cancelAction = cancel; this.reloadAction = reload;
    }

    public void setHosts(List<AnsibleManagedHost> updated) {
        runOnEdt(() -> {
            hostValues = List.copyOf(updated);
            hostModel.setRowCount(0);
            for (AnsibleManagedHost host : hostValues) hostModel.addRow(new Object[]{
                host.inventoryName(), host.displayName(), host.platform(), host.address() + ":" + host.port(),
                host.destinationPath(), host.enabled(), host.applicationClientId()});
        });
    }

    public void appendLog(String line) {
        runOnEdt(() -> {
            SwingTextLimiter.append(logs, line + "\n");
            logs.setCaretPosition(logs.getDocument().getLength());
        });
    }

    private void reloadHostsAsync() {
        reload.setEnabled(false);
        CompletableFuture.supplyAsync(reloadAction).whenComplete((updated, error) -> runOnEdt(() -> {
            reload.setEnabled(true);
            if (error != null) showError(error); else setHosts(updated);
        }));
    }

    public void updateHostResult(AnsibleHostResult result) {
        runOnEdt(() -> {
            int row = -1;
            for (int index = 0; index < resultModel.getRowCount(); index++) {
                if (result.inventoryName().equals(resultModel.getValueAt(index, 0))) { row = index; break; }
            }
            Object[] values = {result.inventoryName(), result.platform(), result.status(), result.changed(),
                result.sha256(), result.detail()};
            if (row < 0) resultModel.addRow(values);
            else for (int column = 0; column < values.length; column++) resultModel.setValueAt(values[column], row, column);
        });
    }

    private void generate() {
        setBusy(true);
        generateAction.apply(content.getText()).whenComplete((source, error) -> runOnEdt(() -> {
            setBusy(false);
            if (error != null) showError(error);
            else sourceStatus.setText(source.path() + " | " + source.bytes() + " bytes | SHA-256 " + source.sha256());
        }));
    }

    private void run(boolean verificationOnly) {
        Set<String> selected = selectedHosts();
        if (selected.isEmpty()) { JOptionPane.showMessageDialog(this, "Select at least one enabled managed host"); return; }
        resultModel.setRowCount(0);
        setBusy(true);
        Function<Set<String>, CompletableFuture<AnsibleJobResult>> action = verificationOnly ? verifyAction : deployAction;
        action.apply(selected).whenComplete((job, error) -> runOnEdt(() -> {
            setBusy(false);
            if (error != null) showError(error);
            else {
                job.hosts().values().forEach(this::updateHostResult);
                appendLog("Job " + job.jobId() + " completed; success=" + job.successful());
            }
        }));
    }

    private Set<String> selectedHosts() {
        Set<String> selected = new HashSet<>();
        for (int viewRow : hostsTable.getSelectedRows()) {
            int row = hostsTable.convertRowIndexToModel(viewRow);
            if (Boolean.TRUE.equals(hostModel.getValueAt(row, 5))) selected.add((String) hostModel.getValueAt(row, 0));
        }
        return Set.copyOf(selected);
    }

    private void setBusy(boolean busy) {
        generate.setEnabled(managementAllowed && !busy);
        deploy.setEnabled(managementAllowed && !busy);
        verify.setEnabled(managementAllowed && !busy);
        cancel.setEnabled(managementAllowed && busy);
    }
    private void showError(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        JOptionPane.showMessageDialog(this, cause.getMessage(), "Ansible Management", JOptionPane.ERROR_MESSAGE);
        appendLog("ERROR: " + cause.getMessage());
    }
    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    int hostCount() { return hostModel.getRowCount(); }
    int resultCount() { return resultModel.getRowCount(); }
    int logLength() { return logs.getDocument().getLength(); }
}
