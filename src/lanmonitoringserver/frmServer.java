/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package lanmonitoringserver;
import java.io.IOException;
import java.awt.BorderLayout;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.security.PreferencesCredentialRegistry;
import lanmonitoringserver.service.LanServerService;
import lanmonitoringserver.service.ServerEventListener;
import lanmonitoringserver.ui.ClientDashboardPanel;
import lanmonitoringserver.ui.ClientDetailPanel;
import lanmonitoringserver.ui.AlertManagementPanel;
import lanmonitoringserver.ui.FileTransferPanel;
import lanmonitoringserver.ui.AnsibleManagementPanel;
import lanmonitoringserver.ui.ServerClientDistanceManagementPanel;
import lanmonitoringserver.ui.WifiFingerprintPanel;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.WirelessObservation;
import lanmonitoringserver.service.AnsibleService;
import lanmonitoringserver.service.AnsibleEventListener;
import lanmonitoringserver.util.InputValidator;
import lanmonitoringserver.security.TlsSupport;
import lanmonitoringserver.util.SwingTextLimiter;
import lanmonitoringserver.util.BuildInfo;

/**
 *
 * @author Lenovo
 */
public class frmServer extends javax.swing.JFrame {
    private static final long serialVersionUID = 1L;

    private final LanServerService serverService = new LanServerService(
            new PreferencesCredentialRegistry(frmServer.class), TlsSupport.serverSocketFactory());
    private final AnsibleService ansibleService = AnsibleService.createDefault(serverService.authorization());
    private final Map<String, String> clientChoices = new ConcurrentHashMap<>();
    private List<String> lastClientChoices = List.of();
    private DefaultTableModel tableModel;
    private ClientDashboardPanel dashboardPanel;
    private ClientDetailPanel clientDetailPanel;
    private JDialog clientDetailDialog;
    private AlertManagementPanel alertManagementPanel;
    private JDialog alertManagementDialog;
    private FileTransferPanel fileTransferPanel;
    private AnsibleManagementPanel ansibleManagementPanel;
    private lanmonitoringserver.ui.HotspotDistanceManagementPanel hotspotDistancePanel;
    private JDialog hotspotDistanceDialog;
    private ServerClientDistanceManagementPanel distanceManagementPanel;
    private JDialog distanceManagementDialog;
    private WifiFingerprintPanel fingerprintPanel;
    private JTabbedPane managementTabs;
    private final javax.swing.Timer addressRefreshTimer = new javax.swing.Timer(
            3_000, event -> refreshServerAddress());

    /**
     * Creates new form frmServer
     */
    public frmServer() {
        initComponents();
        initCustomComponents();
    }
    
    private void initCustomComponents() {
        // Lấy IP máy Server tự động điền vào ô Server IP
        refreshServerAddress();
        txtServerIP.setEditable(false);
        addressRefreshTimer.start();
        
        txtPort.setText("5000");
        lblServerStatus.setText("● Chưa khởi động");
        btnStopServer.setEnabled(false);
        
        // Cấu hình bảng JTable 8 cột
        tableModel = new DefaultTableModel(new Object[]{"ID", "Hostname", "IP Address",
                "Status", "Response", "CPU", "RAM", "LastSeen"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        tblClients.setModel(tableModel);
        tblClients.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tblClients.setAutoCreateRowSorter(true);
        cboClient.removeAllItems();
        updateClientStatsDisplay();
        installDashboard();

        serverService.setListener(new ServerEventListener() {
            @Override
            public void onLog(String message) { log(message); }

            @Override
            public void onClientsChanged(List<ClientSnapshot> clients) {
                updateServerGUI(clients);
                FileTransferPanel panel = fileTransferPanel;
                if (panel != null) panel.setClients(clients, dashboardPanel.selectedClientIds());
                AlertManagementPanel alerts = alertManagementPanel;
                if (alerts != null) alerts.setNotificationClients(clients, serverService::clientPermissions);
            }

            @Override
            public void onAcknowledgement(String clientId, String hostname, String requestId) {
                SwingUtilities.invokeLater(() -> lblAck.setText("✓ " + hostname
                        + " đã nhận thông điệp (ACK)"));
            }

            @Override
            public void onTelemetry(String clientId, TelemetrySample sample) {
                ClientDetailPanel detail = clientDetailPanel;
                if (detail != null && detail.client() != null
                        && clientId.equals(detail.client().clientId())) {
                    detail.acceptTelemetry(sample);
                }
            }

            @Override
            public void onWirelessSignal(String clientId, WirelessObservation observation) {
                if (hotspotDistancePanel != null) hotspotDistancePanel.accept(clientId, observation);
                SwingUtilities.invokeLater(() -> {
                    ClientDetailPanel detail = clientDetailPanel;
                    if (detail != null && detail.client() != null
                            && clientId.equals(detail.client().clientId())) {
                        detail.acceptWireless(observation);
                    }
                });
            }

            @Override
            public void onAlertsChanged(List<AlertRecord> alerts) {
                AlertManagementPanel panel = alertManagementPanel;
                if (panel != null) panel.setAlerts(alerts);
            }

            @Override
            public void onFileTransfersChanged(List<FileTransferProgress> transfers) {
                FileTransferPanel panel = fileTransferPanel;
                if (panel != null) panel.setTransfers(transfers);
            }
        });
        log("Runtime: " + BuildInfo.runtimeDescription(frmServer.class));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                addressRefreshTimer.stop();
                serverService.close();
                ansibleService.close();
            }
        });
    }

    private void installDashboard() {
        dashboardPanel = new ClientDashboardPanel();
        dashboardPanel.setOpenDetail(this::showClientDetail);
        dashboardPanel.setOpenAlerts(this::showAlertManagement);
        dashboardPanel.setOpenFileTransfer(this::showFileTransfer);
        dashboardPanel.setOpenAnsible(() -> managementTabs.setSelectedComponent(ansibleManagementPanel));
        dashboardPanel.setOpenDistanceMap(this::showHotspotDistanceManagement);
        ansibleManagementPanel = new AnsibleManagementPanel(serverService.authorization());
        ansibleManagementPanel.setActions(content -> CompletableFuture.supplyAsync(() -> {
            try { return ansibleService.generateFile(content); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }), ansibleService::deploy, ansibleService::verify,
                () -> CompletableFuture.runAsync(ansibleService::cancelCurrentJob),
                ansibleService::reloadHosts);
        ansibleManagementPanel.setHosts(ansibleService.hosts());
        if (ansibleService.configurationError() != null) {
            ansibleManagementPanel.appendLog("Configuration error: " + ansibleService.configurationError());
        }
        ansibleManagementPanel.appendLog("Managed-host config: "
                + ansibleService.inventoryConfigurationPath());
        ansibleService.setListener(new AnsibleEventListener() {
            @Override public void onLog(String line) { ansibleManagementPanel.appendLog(line); }
            @Override public void onHostResult(lanmonitoringserver.model.AnsibleHostResult result) {
                ansibleManagementPanel.updateHostResult(result);
            }
        });
        JPanel clientListPanel = new JPanel(new BorderLayout(6, 6));
        clientListPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        clientListPanel.add(jScrollPane1, BorderLayout.CENTER);
        JPanel listFooter = new JPanel();
        listFooter.setLayout(new BoxLayout(listFooter, BoxLayout.Y_AXIS));
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        actions.add(btnDisconnectSelected);
        actions.add(btnReconnectSelected);
        listFooter.add(actions);
        JPanel statistics = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 14, 2));
        statistics.add(jLabel7);
        statistics.add(lblTotalClients);
        statistics.add(jLabel8);
        statistics.add(lblOnline);
        statistics.add(jLabel9);
        statistics.add(lblOffline);
        listFooter.add(statistics);
        clientListPanel.add(listFooter, BorderLayout.SOUTH);
        jPanel2.removeAll();
        jPanel2.setBorder(BorderFactory.createEmptyBorder());
        jPanel2.setLayout(new BorderLayout());
        managementTabs = new JTabbedPane();
        managementTabs.addTab("Danh sách Client", clientListPanel);
        managementTabs.addTab("Dashboard", dashboardPanel);
        ensureFileTransferPanel();
        managementTabs.addTab("Gửi file & Phân quyền", fileTransferPanel);
        managementTabs.addTab("Ansible Management", ansibleManagementPanel);
        jPanel2.add(managementTabs, BorderLayout.CENTER);
        jPanel2.revalidate();
        jPanel2.repaint();
    }

    private void showFileTransfer() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::showFileTransfer);
            return;
        }
        ensureFileTransferPanel();
        fileTransferPanel.setClients(serverService.clients(), dashboardPanel.selectedClientIds());
        fileTransferPanel.setTransfers(serverService.fileTransfers());
        managementTabs.setSelectedComponent(fileTransferPanel);
    }

    /** File transfer is embedded in frmServer, not displayed in a separate dialog. */
    private void ensureFileTransferPanel() {
        if (fileTransferPanel == null) {
            fileTransferPanel = new FileTransferPanel(serverService.authorization());
            fileTransferPanel.setActions(request -> CompletableFuture.supplyAsync(() -> {
                try { return serverService.sendFile(request.file(), request.clientIds()); }
                catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
            }).whenComplete((ids, error) -> SwingUtilities.invokeLater(() -> {
                if (error != null) showTransferError(error);
                else log("Đã tạo " + ids.size() + " lượt truyền file.");
            })), transferId -> CompletableFuture.runAsync(() ->
                    serverService.cancelFileTransfer(transferId))
                    .exceptionally(error -> {
                        SwingUtilities.invokeLater(() -> showTransferError(error));
                        return null;
                    }), (clientId, permissions) -> CompletableFuture.runAsync(() -> {
                try { serverService.setClientPermissions(clientId, permissions); }
                catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
            }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                if (error != null) showTransferError(error);
                else fileTransferPanel.setClients(serverService.clients(), dashboardPanel.selectedClientIds());
            })), serverService::clientPermissions);
        }
    }

    private void showTransferError(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        JOptionPane.showMessageDialog(this, cause.getMessage(), "File Transfer", JOptionPane.ERROR_MESSAGE);
    }

    private void showAlertManagement() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::showAlertManagement);
            return;
        }
        if (alertManagementDialog == null) {
            alertManagementPanel = new AlertManagementPanel(
                    serverService.authorization().role().canManageAlerts());
            alertManagementPanel.setActions(serverService::acknowledgeAlert,
                    serverService::resolveAlert, serverService::updateAlertRule);
            alertManagementPanel.setNotificationAction(request -> CompletableFuture.supplyAsync(() ->
                    serverService.sendAdminNotification(request.template(), request.clientIds())));
            alertManagementDialog = new JDialog(this, "Alert Management", false);
            alertManagementDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            alertManagementDialog.setContentPane(alertManagementPanel);
            alertManagementDialog.setSize(1180, 720);
            alertManagementDialog.setMinimumSize(new java.awt.Dimension(900, 600));
        }
        alertManagementPanel.setAlerts(serverService.alerts());
        alertManagementPanel.setRules(serverService.alertRules());
        alertManagementPanel.setNotificationClients(serverService.clients(), serverService::clientPermissions);
        alertManagementDialog.setLocationRelativeTo(this);
        alertManagementDialog.setVisible(true);
        alertManagementDialog.toFront();
    }

    private void showClientDetail(ClientSnapshot client) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showClientDetail(client));
            return;
        }
        if (clientDetailDialog == null) {
            clientDetailPanel = new ClientDetailPanel();
            clientDetailPanel.setRefreshAction(this::requestSystemInfoRefresh);
            clientDetailPanel.setWirelessCalibrationAction(points -> {
                ClientSnapshot selected = clientDetailPanel.client();
                if (selected == null) return;
                CompletableFuture.runAsync(() -> serverService.calibrateWireless(selected.clientId(), points))
                        .whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                            if (error != null) showTransferError(error);
                            else clientDetailPanel.setWirelessHistory(
                                    serverService.wirelessHistory(selected.clientId()));
                        }));
            });
            clientDetailPanel.setServerPositionAction(position -> runDistanceMutation(() ->
                    serverService.setServerPosition(position)));
            clientDetailPanel.setClearServerPositionAction(() -> runDistanceMutation(() ->
                    serverService.clearServerPosition()));
            clientDetailPanel.setClientPositionAction(position -> {
                ClientSnapshot selected = clientDetailPanel.client();
                if (selected != null) runDistanceMutation(() ->
                        serverService.setClientPosition(selected.clientId(), position));
            });
            clientDetailPanel.setManualDistanceAction(input -> {
                ClientSnapshot selected = clientDetailPanel.client();
                if (selected != null) runDistanceMutation(() -> serverService.setManualClientDistance(
                        selected.clientId(), input.meters(), input.estimatedErrorMeters(),
                        input.measuredAt()));
            });
            clientDetailPanel.setClearDistanceAction(() -> {
                ClientSnapshot selected = clientDetailPanel.client();
                if (selected != null) runDistanceMutation(() ->
                        serverService.clearClientDistance(selected.clientId()));
            });
            clientDetailDialog = new JDialog(this, "Chi tiết Client", false);
            clientDetailDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            clientDetailDialog.setContentPane(clientDetailPanel);
            clientDetailDialog.setSize(880, 620);
            clientDetailDialog.setMinimumSize(new java.awt.Dimension(760, 520));
        }
        clientDetailPanel.updateClient(client);
        clientDetailPanel.setTelemetryHistory(serverService.telemetryHistory(client.clientId()));
        List<WirelessObservation> wirelessHistory = serverService.wirelessHistory(client.clientId());
        clientDetailPanel.setWirelessHistory(wirelessHistory);
        log("Network UI loaded " + wirelessHistory.size() + " wireless observation(s) for "
                + client.hostname() + ".");
        clientDetailDialog.setTitle("Chi tiết Client - " + client.hostname());
        clientDetailDialog.setLocationRelativeTo(this);
        clientDetailDialog.setVisible(true);
        clientDetailDialog.toFront();
    }

    private void showHotspotDistanceManagement(ClientSnapshot selected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showHotspotDistanceManagement(selected));
            return;
        }
        if (hotspotDistanceDialog == null) {
            hotspotDistancePanel = new lanmonitoringserver.ui.HotspotDistanceManagementPanel();
            hotspotDistanceDialog = new JDialog(this, "Client - Hotspot RSSI", false);
            hotspotDistanceDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            hotspotDistanceDialog.setContentPane(hotspotDistancePanel);
            hotspotDistanceDialog.setSize(1020, 620);
        }
        for (ClientSnapshot c : serverService.clients()) {
            var history = serverService.wirelessHistory(c.clientId());
            if (!history.isEmpty()) hotspotDistancePanel.accept(c.clientId(), history.get(history.size()-1));
        }
        hotspotDistancePanel.setClients(serverService.clients());
        hotspotDistanceDialog.setLocationRelativeTo(this);
        hotspotDistanceDialog.setVisible(true);
    }

    private void showDistanceManagement(ClientSnapshot selected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showDistanceManagement(selected));
            return;
        }
        if (distanceManagementDialog == null) {
            distanceManagementPanel = new ServerClientDistanceManagementPanel();
            fingerprintPanel = new WifiFingerprintPanel();
            fingerprintPanel.setActions((clientId, position) -> runDistanceMutation(() -> {
                serverService.addRadioMapSample(clientId, position);
                refreshFingerprintPanel();
            }), sampleId -> runDistanceMutation(() -> {
                serverService.removeRadioMapSample(sampleId);
                refreshFingerprintPanel();
            }));
            distanceManagementDialog = new JDialog(this, "Server-Client Distance Map", false);
            distanceManagementDialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Distance & Room Map", distanceManagementPanel);
            tabs.addTab("Wi-Fi Radio Map Survey", fingerprintPanel);
            distanceManagementDialog.setContentPane(tabs);
            distanceManagementDialog.setSize(1120, 760);
            distanceManagementDialog.setMinimumSize(new java.awt.Dimension(850, 600));
        }
        String selectedId = selected == null ? null : selected.clientId();
        distanceManagementPanel.setClients(serverService.clients(), selectedId);
        refreshFingerprintPanel();
        distanceManagementDialog.setLocationRelativeTo(this);
        distanceManagementDialog.setVisible(true);
        distanceManagementDialog.toFront();
    }

    private void refreshFingerprintPanel() {
        WifiFingerprintPanel panel = fingerprintPanel;
        if (panel == null) return;
        panel.setData(serverService.clients(), serverService.radioMapSamples(),
                serverService::latestWifiScan, serverService::estimatedWifiPosition);
    }

    private void requestSystemInfoRefresh(String clientId) {
        CompletableFuture.runAsync(() -> {
            try {
                serverService.requestSystemInfo(clientId);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((ignored, error) -> {
            if (error == null) return;
            SwingUtilities.invokeLater(() -> {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                JOptionPane.showMessageDialog(this, cause.getMessage(),
                        "Refresh System Info", JOptionPane.ERROR_MESSAGE);
            });
        });
    }

    private void runDistanceMutation(DistanceMutation mutation) {
        CompletableFuture.runAsync(() -> {
            try { mutation.run(); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) showTransferError(error);
        }));
    }

    @FunctionalInterface
    private interface DistanceMutation { void run() throws Exception; }
    
    private void log(String text) {
        SwingUtilities.invokeLater(() -> {
            String timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            SwingTextLimiter.append(txtLog, "[" + timeStr + "] " + text + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    private void refreshServerAddress() {
        try {
            List<AddressCandidate> candidates = new ArrayList<>();
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                var addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        int priority = (network.isVirtual() ? 4 : 0)
                                + (address.isSiteLocalAddress() ? 0 : 2);
                        candidates.add(new AddressCandidate(address.getHostAddress(),
                                network.getDisplayName(), priority));
                    }
                }
            }
            candidates.sort(Comparator.comparingInt(AddressCandidate::priority)
                    .thenComparing(AddressCandidate::address));
            if (candidates.isEmpty()) {
                txtServerIP.setText("127.0.0.1");
                txtServerIP.setToolTipText("No active non-loopback IPv4 detected");
                return;
            }
            txtServerIP.setText(candidates.get(0).address());
            txtServerIP.setToolTipText("Active IPv4: " + candidates.stream()
                    .map(item -> item.address() + " [" + item.adapter() + "]")
                    .collect(java.util.stream.Collectors.joining("; ")));
        } catch (SocketException error) {
            txtServerIP.setText("127.0.0.1");
            txtServerIP.setToolTipText("IPv4 detection failed: " + error.getMessage());
        }
    }

    private record AddressCandidate(String address, String adapter, int priority) {}

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jScrollPane3 = new javax.swing.JScrollPane();
        jPanel5 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        jPanel1 = new javax.swing.JPanel();
        jLabel3 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        txtServerIP = new javax.swing.JTextField();
        txtPort = new javax.swing.JTextField();
        lblServerStatus = new javax.swing.JLabel();
        btnStartServer = new javax.swing.JButton();
        jPanel2 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        tblClients = new javax.swing.JTable();
        jLabel7 = new javax.swing.JLabel();
        jLabel8 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        lblTotalClients = new javax.swing.JLabel();
        lblOnline = new javax.swing.JLabel();
        lblOffline = new javax.swing.JLabel();
        btnDisconnectSelected = new javax.swing.JButton();
        btnReconnectSelected = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        jLabel13 = new javax.swing.JLabel();
        cboClient = new javax.swing.JComboBox<>();
        txtMessage = new javax.swing.JTextField();
        btnSendMessage = new javax.swing.JButton();
        jLabel14 = new javax.swing.JLabel();
        lblAck = new javax.swing.JLabel();
        jPanel4 = new javax.swing.JPanel();
        jScrollPane2 = new javax.swing.JScrollPane();
        txtLog = new javax.swing.JTextArea();
        btnStopServer = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setBackground(new java.awt.Color(102, 204, 255));

        jLabel1.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        jLabel1.setText("LAN MONITORING SERVER");

        jLabel2.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        jLabel2.setText(" Hệ thống giám sát máy tính trong mạng LAN");

        jPanel1.setBorder(javax.swing.BorderFactory.createTitledBorder("THÔNG TIN SERVER "));

        jLabel3.setText("Server IP:");

        jLabel4.setText("Port:");

        jLabel5.setText("Trạng thái Server:");

        txtPort.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtPortActionPerformed(evt);
            }
        });

        lblServerStatus.setText("Chưa khởi động");

        btnStartServer.setText("Khởi động");
        btnStartServer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnStartServerActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addGap(18, 18, 18)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(jLabel3)
                        .addGap(18, 18, 18)
                        .addComponent(txtServerIP, javax.swing.GroupLayout.PREFERRED_SIZE, 646, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(jLabel4)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(txtPort, javax.swing.GroupLayout.PREFERRED_SIZE, 652, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(jLabel5)
                        .addGap(18, 18, 18)
                        .addComponent(lblServerStatus, javax.swing.GroupLayout.PREFERRED_SIZE, 158, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(299, 299, 299)
                        .addComponent(btnStartServer, javax.swing.GroupLayout.PREFERRED_SIZE, 375, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel3)
                    .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(txtServerIP, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(jLabel4)
                        .addComponent(txtPort, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 43, Short.MAX_VALUE)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(lblServerStatus)
                    .addComponent(btnStartServer))
                .addGap(17, 17, 17))
        );

        jPanel2.setBorder(javax.swing.BorderFactory.createTitledBorder("DANH SÁCH CLIENT"));

        tblClients.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {
                {null, null, null, null, null, null, null, null},
                {null, null, null, null, null, null, null, null},
                {null, null, null, null, null, null, null, null},
                {null, null, null, null, null, null, null, null}
            },
            new String [] {
                "ID", "Hostname", "IP Address", "Status", "Response", "CPU", "RAM", "LastSeen"
            }
        ));
        jScrollPane1.setViewportView(tblClients);

        jLabel7.setText("Tổng client:");

        jLabel8.setText("Online:");

        jLabel9.setText("Offline:");

        lblOnline.setText("jLabel11");

        lblOffline.setText("jLabel12");

        btnDisconnectSelected.setText("Ngắt kết nối");
        btnDisconnectSelected.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDisconnectSelectedActionPerformed(evt);
            }
        });

        btnReconnectSelected.setText("Cho phép kết nối lại");
        btnReconnectSelected.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnReconnectSelectedActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addGap(59, 59, 59)
                .addComponent(jLabel7)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(lblTotalClients, javax.swing.GroupLayout.PREFERRED_SIZE, 59, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jLabel8, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(lblOnline)
                .addGap(225, 225, 225)
                .addComponent(jLabel9)
                .addGap(18, 18, 18)
                .addComponent(lblOffline)
                .addGap(291, 291, 291))
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 1460, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(btnDisconnectSelected)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnReconnectSelected)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 152, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnDisconnectSelected)
                    .addComponent(btnReconnectSelected))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel7)
                    .addComponent(jLabel8)
                    .addComponent(jLabel9)
                    .addComponent(lblTotalClients)
                    .addComponent(lblOnline)
                    .addComponent(lblOffline))
                .addContainerGap(12, Short.MAX_VALUE))
        );

        jPanel3.setBorder(javax.swing.BorderFactory.createTitledBorder("GỬI THÔNG ĐIỆP "));

        jLabel13.setText("Client:");

        cboClient.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "HIEU-PC", "HUY-PC" }));

        btnSendMessage.setText("Gửi");
        btnSendMessage.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendMessageActionPerformed(evt);
            }
        });

        jLabel14.setText("Phản hồi:");

        lblAck.setText("Client HIEU-PC đã nhận thông điệp (ACK)");

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGap(31, 31, 31)
                .addComponent(jLabel13)
                .addGap(18, 18, 18)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(cboClient, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                            .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel3Layout.createSequentialGroup()
                                .addComponent(jLabel14)
                                .addGap(18, 18, 18)
                                .addComponent(lblAck, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                            .addComponent(txtMessage, javax.swing.GroupLayout.PREFERRED_SIZE, 438, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(46, 46, 46)
                        .addComponent(btnSendMessage)))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(cboClient, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel13))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(txtMessage, javax.swing.GroupLayout.PREFERRED_SIZE, 31, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnSendMessage))
                .addGap(26, 26, 26)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel14)
                    .addComponent(lblAck))
                .addContainerGap(27, Short.MAX_VALUE))
        );

        jPanel4.setBorder(javax.swing.BorderFactory.createTitledBorder("NHẬT KÝ HỆ THỐNG"));

        txtLog.setColumns(20);
        txtLog.setRows(5);
        jScrollPane2.setViewportView(txtLog);

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane2)
                .addContainerGap())
        );
        jPanel4Layout.setVerticalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 195, Short.MAX_VALUE)
                .addContainerGap())
        );

        btnStopServer.setText("Dừng Server");
        btnStopServer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnStopServerActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel5Layout = new javax.swing.GroupLayout(jPanel5);
        jPanel5.setLayout(jPanel5Layout);
        jPanel5Layout.setHorizontalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel5Layout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(btnStopServer, javax.swing.GroupLayout.PREFERRED_SIZE, 279, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(194, 194, 194))
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel5Layout.createSequentialGroup()
                            .addContainerGap()
                            .addGroup(jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                .addComponent(jPanel1, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(jPanel4, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(jPanel3, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addGap(617, 617, 617)
                        .addComponent(jLabel1))
                    .addGroup(jPanel5Layout.createSequentialGroup()
                        .addGap(540, 540, 540)
                        .addComponent(jLabel2)))
                .addContainerGap(83, Short.MAX_VALUE))
        );
        jPanel5Layout.setVerticalGroup(
            jPanel5Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel5Layout.createSequentialGroup()
                .addGap(47, 47, 47)
                .addComponent(jLabel1)
                .addGap(18, 18, 18)
                .addComponent(jLabel2)
                .addGap(18, 18, 18)
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(btnStopServer)
                .addContainerGap(235, Short.MAX_VALUE))
        );

        jScrollPane3.setViewportView(jPanel5);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane3, javax.swing.GroupLayout.DEFAULT_SIZE, 1512, Short.MAX_VALUE)
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane3, javax.swing.GroupLayout.PREFERRED_SIZE, 986, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void txtPortActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtPortActionPerformed
    }//GEN-LAST:event_txtPortActionPerformed

    private void btnStartServerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnStartServerActionPerformed
        refreshServerAddress();
        final int port;
        try {
            port = InputValidator.port(txtPort.getText());
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Cảnh báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        btnStartServer.setEnabled(false);
        txtPort.setEnabled(false);
        lblServerStatus.setText("● Đang khởi động...");
        CompletableFuture.runAsync(() -> {
            try {
                int actualPort = serverService.start(port);
                SwingUtilities.invokeLater(() -> {
                    lblServerStatus.setText("● Đang chạy (Port: " + actualPort + ")");
                    log("Current LAN IPv4 for Clients: " + txtServerIP.getText()
                            + "; TLS certificate identity is independent of this address.");
                    btnStopServer.setEnabled(true);
                });
            } catch (IOException exception) {
                log("Không thể khởi động Server: " + exception.getMessage());
                SwingUtilities.invokeLater(() -> {
                    btnStartServer.setEnabled(true);
                    txtPort.setEnabled(true);
                    lblServerStatus.setText("● Khởi động thất bại");
                    JOptionPane.showMessageDialog(this, "Lỗi khởi động: "
                            + exception.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }//GEN-LAST:event_btnStartServerActionPerformed

    private void btnStopServerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnStopServerActionPerformed
        stopServer();
    }//GEN-LAST:event_btnStopServerActionPerformed

    private void btnDisconnectSelectedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDisconnectSelectedActionPerformed
        disconnectSelectedClients();
    }//GEN-LAST:event_btnDisconnectSelectedActionPerformed

    private void btnReconnectSelectedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnReconnectSelectedActionPerformed
        allowSelectedClientsToReconnect();
    }//GEN-LAST:event_btnReconnectSelectedActionPerformed

    private void btnSendMessageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendMessageActionPerformed
        String selectedClient = (String) cboClient.getSelectedItem();
        String clientId = clientChoices.get(selectedClient);
        if (clientId == null) {
            JOptionPane.showMessageDialog(this, "Chưa chọn Client nhận thông điệp!", "Cảnh báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String message = InputValidator.message(txtMessage.getText());
            btnSendMessage.setEnabled(false);
            CompletableFuture.supplyAsync(() -> {
                try {
                    return serverService.sendMessage(clientId, message);
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }).whenComplete((requestId, error) -> SwingUtilities.invokeLater(() -> {
                btnSendMessage.setEnabled(true);
                if (error == null) {
                    lblAck.setText("Đã gửi, chờ ACK... (" + requestId + ")");
                } else {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    JOptionPane.showMessageDialog(this, cause.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                }
            }));
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }//GEN-LAST:event_btnSendMessageActionPerformed

    private void stopServer() {
        btnStopServer.setEnabled(false);
        lblServerStatus.setText("● Đang dừng...");
        CompletableFuture.runAsync(serverService::stop).whenComplete((ignored, error) ->
            SwingUtilities.invokeLater(() -> {
                lblServerStatus.setText("● Đã dừng");
                btnStartServer.setEnabled(true);
                txtPort.setEnabled(true);
                cboClient.removeAllItems();
                clientChoices.clear();
                lastClientChoices = List.of();
            })
        );
    }

    private void updateServerGUI(List<ClientSnapshot> clients) {
        dashboardPanel.updateClients(clients);
        SwingUtilities.invokeLater(() -> {
            LinkedHashSet<String> selectedIds = new LinkedHashSet<>(selectedClientIds());
            tableModel.setRowCount(0);
            for (ClientSnapshot client : clients) {
                tableModel.addRow(new Object[]{client.clientId(), client.hostname(), client.ipAddress(),
                    client.status(), client.responseTimeMillis() == null ? "-"
                            : client.responseTimeMillis() + " ms",
                    String.format(java.util.Locale.ROOT, "%.1f%%", client.cpuPercent()),
                    String.format(java.util.Locale.ROOT, "%.1f%%", client.ramPercent()),
                    java.time.Instant.ofEpochMilli(client.lastSeenTime()).atZone(
                            java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0)});
            }
            restoreClientSelection(selectedIds);
            long onlineCount = clients.stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE).count();
            lblTotalClients.setText(Integer.toString(clients.size()));
            lblOnline.setText(Long.toString(onlineCount));
            lblOffline.setText(Long.toString(clients.size() - onlineCount));
            if (hotspotDistancePanel != null) hotspotDistancePanel.setClients(clients);
            if (distanceManagementPanel != null) {
                distanceManagementPanel.setClients(clients,
                        distanceManagementPanel.selectedClientIdForRefresh());
            }
            refreshFingerprintPanel();
            List<String> currentChoices = clients.stream()
                    .filter(client -> client.status() == ClientStatus.ONLINE)
                    .map(client -> client.hostname() + " [" + client.clientId() + "]")
                    .toList();
            if (!currentChoices.equals(lastClientChoices)) {
                String selectedClient = (String) cboClient.getSelectedItem();
                cboClient.removeAllItems();
                clientChoices.clear();
                for (ClientSnapshot client : clients) {
                    if (client.status() != ClientStatus.ONLINE) continue;
                    String choice = client.hostname() + " [" + client.clientId() + "]";
                    cboClient.addItem(choice);
                    clientChoices.put(choice, client.clientId());
                }
                lastClientChoices = currentChoices;
                if (selectedClient != null && clientChoices.containsKey(selectedClient)) {
                    cboClient.setSelectedItem(selectedClient);
                }
            }
            for (ClientSnapshot client : clients) {
                if (clientDetailPanel != null && clientDetailPanel.client() != null
                        && client.clientId().equals(clientDetailPanel.client().clientId())) {
                    clientDetailPanel.updateClient(client);
                }
            }
        });
    }

    private List<String> selectedClientIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int viewRow : tblClients.getSelectedRows()) {
            int modelRow = tblClients.convertRowIndexToModel(viewRow);
            Object value = tableModel.getValueAt(modelRow, 0);
            if (value != null) result.add(value.toString());
        }
        return List.copyOf(result);
    }

    private void restoreClientSelection(java.util.Set<String> clientIds) {
        tblClients.clearSelection();
        if (clientIds.isEmpty()) return;
        for (int modelRow = 0; modelRow < tableModel.getRowCount(); modelRow++) {
            Object value = tableModel.getValueAt(modelRow, 0);
            if (value != null && clientIds.contains(value.toString())) {
                int viewRow = tblClients.convertRowIndexToView(modelRow);
                if (viewRow >= 0) tblClients.addRowSelectionInterval(viewRow, viewRow);
            }
        }
    }

    private void disconnectSelectedClients() {
        List<String> clientIds = selectedClientIds();
        if (clientIds.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn ít nhất một Client.",
                    "Ngắt kết nối", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn ngắt kết nối " + clientIds.size() + " Client đã chọn?",
                "Xác nhận ngắt kết nối", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        runClientControl(() -> serverService.disconnectClients(clientIds), true);
    }

    private void allowSelectedClientsToReconnect() {
        List<String> selected = selectedClientIds();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn ít nhất một Client.",
                    "Cho phép kết nối lại", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<String> blocked = selected.stream().filter(serverService::isReconnectBlocked).toList();
        if (blocked.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Các Client đã chọn không ở trạng thái bị Server chặn.",
                    "Cho phép kết nối lại", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Bạn có muốn cho phép " + blocked.size() + " Client kết nối lại?",
                "Xác nhận cho phép kết nối lại", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        runClientControl(() -> serverService.allowReconnect(blocked), false);
    }

    private void runClientControl(
            java.util.function.Supplier<LanServerService.ClientControlResult> action,
            boolean disconnecting) {
        btnDisconnectSelected.setEnabled(false);
        btnReconnectSelected.setEnabled(false);
        CompletableFuture.supplyAsync(action).whenComplete((result, error) ->
                SwingUtilities.invokeLater(() -> {
                    btnDisconnectSelected.setEnabled(true);
                    btnReconnectSelected.setEnabled(true);
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        JOptionPane.showMessageDialog(this, cause.getMessage(), "Lỗi",
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    String summary = disconnecting
                            ? "Đã ngắt kết nối " + result.completed() + "/" + result.requested()
                                    + " Client thành công."
                            : "Đã cho phép " + result.completed()
                                    + " Client kết nối lại. Đang chờ Client thiết lập kết nối.";
                    if (!result.failures().isEmpty()) {
                        summary += "\nKhông xử lý được:\n- "
                                + String.join("\n- ", result.failures());
                    }
                    log(summary.replace('\n', ' '));
                    JOptionPane.showMessageDialog(this, summary,
                            disconnecting ? "Ngắt kết nối" : "Cho phép kết nối lại",
                            result.failures().isEmpty() ? JOptionPane.INFORMATION_MESSAGE
                                    : JOptionPane.WARNING_MESSAGE);
                }));
    }

    private void updateClientStatsDisplay() {
        lblTotalClients.setText("0");
        lblOnline.setText("0");
        lblOffline.setText("0");
    }
    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(frmServer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(frmServer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(frmServer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(frmServer.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new frmServer().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnDisconnectSelected;
    private javax.swing.JButton btnReconnectSelected;
    private javax.swing.JButton btnSendMessage;
    private javax.swing.JButton btnStartServer;
    private javax.swing.JButton btnStopServer;
    private javax.swing.JComboBox<String> cboClient;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JLabel lblAck;
    private javax.swing.JLabel lblOffline;
    private javax.swing.JLabel lblOnline;
    private javax.swing.JLabel lblServerStatus;
    private javax.swing.JLabel lblTotalClients;
    private javax.swing.JTable tblClients;
    private javax.swing.JTextArea txtLog;
    private javax.swing.JTextField txtMessage;
    private javax.swing.JTextField txtPort;
    private javax.swing.JTextField txtServerIP;
    // End of variables declaration//GEN-END:variables
}
