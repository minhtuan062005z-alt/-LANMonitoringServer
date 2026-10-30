/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package lanmonitoringserver;
import javax.swing.JOptionPane;
import java.net.InetAddress;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientConnectionState;
import lanmonitoringserver.model.AlertNotification;
import lanmonitoringserver.model.SystemStats;
import lanmonitoringserver.monitor.LocalSystemMonitor;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.PreferencesClientIdentityStore;
import lanmonitoringserver.service.ClientEventListener;
import lanmonitoringserver.service.LanClientService;
import lanmonitoringserver.util.InputValidator;
import lanmonitoringserver.security.TlsSupport;
import lanmonitoringserver.ui.ClientFileTransferPanel;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.ReceivedFileInfo;
import lanmonitoringserver.util.SwingTextLimiter;
import lanmonitoringserver.util.BuildInfo;

/**
 *
 * @author Lenovo
 */
public class frmClient extends javax.swing.JFrame {
    private static final long serialVersionUID = 1L;
    
    private final ClientCredentials credentials =
            new PreferencesClientIdentityStore(frmClient.class).loadOrCreate();
    private final LocalSystemMonitor systemMonitor = new LocalSystemMonitor();
    private final LanClientService clientService = new LanClientService(credentials, systemMonitor,
            TlsSupport.clientSocketFactory());
    private String clientID = credentials.clientId();
    private String hostname = "";
    private String clientIP = "";
    private ClientFileTransferPanel fileTransferPanel;
    private javax.swing.JDialog fileTransferDialog;
    private javax.swing.JDialog activeAlertDialog;

    /**
     * Creates new form frmClient
     */
    public frmClient() {
        initComponents();
        loadDeviceInfo(); // Tự động lấy Hostname, IP, CPU, RAM
        lblStatus.setText("● CHƯA KẾT NỐI");
        lblAckStatus.setText("Chưa có phản hồi");
        btnDisconnect.setEnabled(false); // Ban đầu chưa kết nối thì khóa nút ngắt
        configureClientService();
        log("Runtime: " + BuildInfo.runtimeDescription(frmClient.class));
        log("Wi-Fi measurement starts disabled unless enabled from Wi-Fi Research or "
                + "-Dlanmonitoring.wifi.enabled=true.");
        javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
        javax.swing.JMenu filesMenu = new javax.swing.JMenu("Files");
        javax.swing.JMenuItem receivedFiles = new javax.swing.JMenuItem("Received Files");
        receivedFiles.addActionListener(event -> showReceivedFilesWindow());
        filesMenu.add(receivedFiles);
        menuBar.add(filesMenu);
        javax.swing.JMenu wirelessMenu = new javax.swing.JMenu("Wi-Fi Research");
        javax.swing.JCheckBoxMenuItem wirelessEnabled = new javax.swing.JCheckBoxMenuItem(
                "Enable Wi-Fi measurement", clientService.isWirelessMonitoringEnabled());
        wirelessEnabled.setToolTipText("Measures Client-to-Access-Point signal; does not infer distance from ping");
        wirelessEnabled.addActionListener(event ->
                clientService.setWirelessMonitoringEnabled(wirelessEnabled.isSelected()));
        wirelessMenu.add(wirelessEnabled);
        javax.swing.JMenuItem probeWireless = new javax.swing.JMenuItem("Test Wi-Fi collector now");
        probeWireless.setToolTipText("Reads one local sample without sending SSID/BSSID or enabling periodic measurement");
        probeWireless.addActionListener(event -> clientService.probeWirelessCollector());
        wirelessMenu.add(probeWireless);
        javax.swing.JCheckBoxMenuItem fingerprintEnabled = new javax.swing.JCheckBoxMenuItem(
                "Enable multi-AP fingerprint scans", clientService.isFingerprintingEnabled());
        fingerprintEnabled.setToolTipText("Opt-in, rate-limited scans; sends hashed AP IDs and real RSSI only");
        fingerprintEnabled.addActionListener(event ->
                clientService.setFingerprintingEnabled(fingerprintEnabled.isSelected()));
        wirelessMenu.add(fingerprintEnabled);
        javax.swing.JMenuItem probeFingerprint = new javax.swing.JMenuItem("Test multi-AP scanner now");
        probeFingerprint.addActionListener(event -> clientService.probeFingerprintScanner());
        wirelessMenu.add(probeFingerprint);
        menuBar.add(wirelessMenu);
        setJMenuBar(menuBar);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                clientService.close();
            }
        });
    }//GEN-LAST:event_btnDisconnectActionPerformed

    private void configureClientService() {
        clientService.setListener(new ClientEventListener() {
            @Override
            public void onLog(String message) { log(message); }

            @Override
            public void onStateChanged(ClientConnectionState state, String detail) {
                SwingUtilities.invokeLater(() -> updateConnectionState(state, detail));
            }

            @Override
            public void onStats(SystemStats stats) {
                SwingUtilities.invokeLater(() -> {
                    lblCPU.setText(String.format("%.1f%%", stats.cpuPercent()));
                    lblRAM.setText(String.format("%.1f%%", stats.ramPercent()));
                    lblHeartbeat.setText("● Đang gửi mỗi 3 giây");
                });
            }

            @Override
            public void onMessage(String content, String requestId) {
                SwingUtilities.invokeLater(() -> {
                    txtServerMessage.setText(content);
                    showMessagePopup(content);
                });
            }

            @Override
            public void onAlert(AlertNotification alert) {
                SwingUtilities.invokeLater(() -> showAlertPopup(alert));
            }

            @Override
            public void onAcknowledgementSent(String requestId) {
                SwingUtilities.invokeLater(() -> lblAckStatus.setText(
                        "ACK - MESSAGE_RECEIVED (" + requestId + ")"));
            }

            @Override
            public void onDisconnectedByServer() {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frmClient.this,
                        "Server đã chủ động ngắt kết nối của bạn.",
                        "Kết nối đã bị ngắt", JOptionPane.WARNING_MESSAGE));
            }

            @Override
            public void onReconnectedAfterServerDisconnect() {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frmClient.this,
                        "Đã kết nối lại Server thành công.",
                        "Kết nối lại thành công", JOptionPane.INFORMATION_MESSAGE));
            }

            @Override
            public void onFileTransferProgress(FileTransferProgress progress) {
                SwingUtilities.invokeLater(() -> showFileTransferProgress(progress));
            }

            @Override
            public void onFileReceived(ReceivedFileInfo file) {
                log("File received and SHA-256 verified: " + file.savedPath());
            }
        });
    }

    private void showFileTransferProgress(FileTransferProgress progress) {
        showReceivedFilesWindow();
        fileTransferPanel.updateTransfer(progress);
    }

    private void showReceivedFilesWindow() {
        if (fileTransferDialog == null) {
            fileTransferPanel = new ClientFileTransferPanel();
            fileTransferDialog = new javax.swing.JDialog(this, "Received Files", false);
            fileTransferDialog.setDefaultCloseOperation(javax.swing.WindowConstants.HIDE_ON_CLOSE);
            fileTransferDialog.setContentPane(fileTransferPanel);
            fileTransferDialog.setSize(900, 420);
            fileTransferDialog.setLocationRelativeTo(this);
        }
        if (!fileTransferDialog.isVisible()) fileTransferDialog.setVisible(true);
    }

    private void updateConnectionState(ClientConnectionState state, String detail) {
        lblStatus.setText("● " + state + " - " + detail);
        boolean disconnected = state == ClientConnectionState.DISCONNECTED;
        btnConnect.setEnabled(disconnected);
        btnDisconnect.setEnabled(!disconnected);
        txtServerIP.setEnabled(disconnected);
        txtPort.setEnabled(disconnected);
        if (disconnected) lblHeartbeat.setText("Đã dừng");
    }
    
    private void showMessagePopup(String message) {
    JOptionPane.showMessageDialog(
        this,
        message,
        "Tin nhắn từ Server",
        JOptionPane.INFORMATION_MESSAGE
    );
}

    private void showAlertPopup(AlertNotification alert) {
        String time = java.time.Instant.ofEpochMilli(alert.occurredAt())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString();
        String message = alert.templated()
                ? String.format("Máy: %s%nLoại: %s%n%s%nThời gian: %s",
                        alert.hostname(), alert.title(), alert.content(), time)
                : String.format("%s%nMáy: %s%nTài nguyên: %s%nGiá trị: %.1f%%%nNgưỡng: > %.1f%%%nThời gian: %s",
                        alert.severity(), alert.hostname(), alert.metric().displayName(),
                        alert.value(), alert.threshold(), time);
        int messageType = alert.severity() == lanmonitoringserver.model.AlertSeverity.CRITICAL
                ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE;
        JOptionPane pane = new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION);
        if (activeAlertDialog != null && activeAlertDialog.isDisplayable()) activeAlertDialog.dispose();
        javax.swing.JDialog dialog = pane.createDialog(this,
                alert.templated() ? alert.title() : "Resource Alert - " + alert.severity());
        activeAlertDialog = dialog;
        dialog.setModal(false);
        dialog.setAlwaysOnTop(true);
        javax.swing.Timer autoClose = new javax.swing.Timer(15_000, event -> dialog.dispose());
        autoClose.setRepeats(false);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                autoClose.stop();
                if (activeAlertDialog == dialog) activeAlertDialog = null;
            }
        });
        autoClose.start();
        dialog.setVisible(true);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jScrollPane3 = new javax.swing.JScrollPane();
        pnlContent = new javax.swing.JPanel();
        jPanel_Log = new javax.swing.JPanel();
        jScrollPane2 = new javax.swing.JScrollPane();
        txtLog = new javax.swing.JTextArea();
        lblAckStatus = new javax.swing.JLabel();
        jLabel19 = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        jPanel1 = new javax.swing.JPanel();
        txtServerIP = new javax.swing.JTextField();
        txtPort = new javax.swing.JTextField();
        btnConnect = new javax.swing.JButton();
        btnDisconnect = new javax.swing.JButton();
        jLabel2 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        jLabel1 = new javax.swing.JLabel();
        jPanel_MessageFromTheServer = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        txtServerMessage = new javax.swing.JTextArea();
        jPanel_DeviceInformation = new javax.swing.JPanel();
        jLabel7 = new javax.swing.JLabel();
        jLabel8 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        jLabel10 = new javax.swing.JLabel();
        jLabel11 = new javax.swing.JLabel();
        jLabel12 = new javax.swing.JLabel();
        lblClientID = new javax.swing.JLabel();
        lblHostname = new javax.swing.JLabel();
        lblClientIP = new javax.swing.JLabel();
        lblCPU = new javax.swing.JLabel();
        lblRAM = new javax.swing.JLabel();
        lblHeartbeat = new javax.swing.JLabel();
        lblStatus = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        jPanel_Log.setBorder(javax.swing.BorderFactory.createTitledBorder("NHẬT KÝ\n"));

        txtLog.setColumns(20);
        txtLog.setRows(5);
        jScrollPane2.setViewportView(txtLog);

        javax.swing.GroupLayout jPanel_LogLayout = new javax.swing.GroupLayout(jPanel_Log);
        jPanel_Log.setLayout(jPanel_LogLayout);
        jPanel_LogLayout.setHorizontalGroup(
            jPanel_LogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel_LogLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane2)
                .addContainerGap())
        );
        jPanel_LogLayout.setVerticalGroup(
            jPanel_LogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel_LogLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 201, Short.MAX_VALUE)
                .addContainerGap())
        );

        lblAckStatus.setText("ACK - MESSAGE_RECEIVED ");

        jLabel19.setText("Đã gửi phản hồi:");

        jLabel5.setText("Trạng thái:");

        txtServerIP.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtServerIPActionPerformed(evt);
            }
        });

        btnConnect.setText("KẾT NỐI");
        btnConnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnConnectActionPerformed(evt);
            }
        });

        btnDisconnect.setText("NGẮT KẾT NỐI");
        btnDisconnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDisconnectActionPerformed(evt);
            }
        });

        jLabel2.setText("Server IP:");

        jLabel4.setText("Port:");

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, jPanel1Layout.createSequentialGroup()
                        .addGap(28, 28, 28)
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel2)
                            .addComponent(jLabel4))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(txtPort)
                            .addComponent(txtServerIP)))
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addContainerGap(1185, Short.MAX_VALUE)
                        .addComponent(btnConnect)
                        .addGap(83, 83, 83)
                        .addComponent(btnDisconnect)))
                .addGap(29, 29, 29))
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addGap(26, 26, 26)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(txtServerIP, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel2))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel4)
                    .addComponent(txtPort, javax.swing.GroupLayout.PREFERRED_SIZE, 22, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 32, Short.MAX_VALUE)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnConnect)
                    .addComponent(btnDisconnect))
                .addContainerGap())
        );

        jLabel1.setFont(new java.awt.Font("Segoe UI", 0, 18)); // NOI18N
        jLabel1.setText("LAN MONITORING CLIENT");

        jPanel_MessageFromTheServer.setBorder(javax.swing.BorderFactory.createTitledBorder("THÔNG ĐIỆP TỪ SERVER\n"));

        txtServerMessage.setColumns(20);
        txtServerMessage.setRows(5);
        txtServerMessage.setText("Kiểm tra kết nối mạng\n");
        jScrollPane1.setViewportView(txtServerMessage);

        javax.swing.GroupLayout jPanel_MessageFromTheServerLayout = new javax.swing.GroupLayout(jPanel_MessageFromTheServer);
        jPanel_MessageFromTheServer.setLayout(jPanel_MessageFromTheServerLayout);
        jPanel_MessageFromTheServerLayout.setHorizontalGroup(
            jPanel_MessageFromTheServerLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel_MessageFromTheServerLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 1441, Short.MAX_VALUE)
                .addContainerGap())
        );
        jPanel_MessageFromTheServerLayout.setVerticalGroup(
            jPanel_MessageFromTheServerLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel_MessageFromTheServerLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(8, Short.MAX_VALUE))
        );

        jPanel_DeviceInformation.setBorder(javax.swing.BorderFactory.createTitledBorder("THÔNG TIN MÁY"));

        jLabel7.setText("Client ID            :");

        jLabel8.setText("Hostname         :");

        jLabel9.setText("IP Address         :");

        jLabel10.setText("CPU Usage        :");

        jLabel11.setText("RAM Usage       :");

        jLabel12.setText("Heartbeat          :");

        lblClientID.setText("...");

        lblHostname.setText("...");

        lblClientIP.setText("...");

        lblCPU.setText("...");

        lblRAM.setText("...");

        lblHeartbeat.setText("...");

        javax.swing.GroupLayout jPanel_DeviceInformationLayout = new javax.swing.GroupLayout(jPanel_DeviceInformation);
        jPanel_DeviceInformation.setLayout(jPanel_DeviceInformationLayout);
        jPanel_DeviceInformationLayout.setHorizontalGroup(
            jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel_DeviceInformationLayout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(jLabel11, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jLabel12, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jLabel9, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jLabel8, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jLabel7, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jLabel10, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(18, 18, 18)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(lblClientID, javax.swing.GroupLayout.DEFAULT_SIZE, 630, Short.MAX_VALUE)
                    .addComponent(lblClientIP, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblCPU, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblHostname, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblRAM, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblHeartbeat, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel_DeviceInformationLayout.setVerticalGroup(
            jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel_DeviceInformationLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel7)
                    .addComponent(lblClientID))
                .addGap(18, 18, 18)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel8)
                    .addComponent(lblHostname))
                .addGap(18, 18, 18)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel9)
                    .addComponent(lblClientIP))
                .addGap(18, 18, 18)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel10)
                    .addComponent(lblCPU))
                .addGap(18, 18, 18)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel11)
                    .addComponent(lblRAM))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 50, Short.MAX_VALUE)
                .addGroup(jPanel_DeviceInformationLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel12)
                    .addComponent(lblHeartbeat))
                .addGap(29, 29, 29))
        );

        javax.swing.GroupLayout pnlContentLayout = new javax.swing.GroupLayout(pnlContent);
        pnlContent.setLayout(pnlContentLayout);
        pnlContentLayout.setHorizontalGroup(
            pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(pnlContentLayout.createSequentialGroup()
                .addGroup(pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(pnlContentLayout.createSequentialGroup()
                        .addGap(661, 661, 661)
                        .addComponent(jLabel1))
                    .addGroup(pnlContentLayout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(pnlContentLayout.createSequentialGroup()
                        .addGap(23, 23, 23)
                        .addComponent(jLabel5)
                        .addGap(18, 18, 18)
                        .addComponent(lblStatus, javax.swing.GroupLayout.PREFERRED_SIZE, 644, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, pnlContentLayout.createSequentialGroup()
                            .addContainerGap()
                            .addComponent(jPanel_Log, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, pnlContentLayout.createSequentialGroup()
                            .addGap(14, 14, 14)
                            .addComponent(jLabel19)
                            .addGap(18, 18, 18)
                            .addComponent(lblAckStatus, javax.swing.GroupLayout.DEFAULT_SIZE, 1349, Short.MAX_VALUE))
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, pnlContentLayout.createSequentialGroup()
                            .addContainerGap()
                            .addGroup(pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                .addComponent(jPanel_DeviceInformation, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(jPanel_MessageFromTheServer, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))))
                .addGap(171, 361, Short.MAX_VALUE))
        );
        pnlContentLayout.setVerticalGroup(
            pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, pnlContentLayout.createSequentialGroup()
                .addGap(27, 27, 27)
                .addComponent(jLabel1)
                .addGap(30, 30, 30)
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel_MessageFromTheServer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(lblStatus))
                .addGap(18, 18, 18)
                .addComponent(jPanel_DeviceInformation, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(pnlContentLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel19)
                    .addComponent(lblAckStatus))
                .addGap(18, 18, 18)
                .addComponent(jPanel_Log, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(268, Short.MAX_VALUE))
        );

        jScrollPane3.setViewportView(pnlContent);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane3, javax.swing.GroupLayout.PREFERRED_SIZE, 1526, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(372, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane3, javax.swing.GroupLayout.PREFERRED_SIZE, 1068, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void txtServerIPActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtServerIPActionPerformed
    }//GEN-LAST:event_txtServerIPActionPerformed

    private void btnConnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnConnectActionPerformed
        String serverIP = txtServerIP.getText().trim();
        if (serverIP.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Server IP!", "Cảnh báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            int port = InputValidator.port(txtPort.getText());
            clientService.connect(serverIP, port, hostname, clientIP);
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
        }
    }//GEN-LAST:event_btnConnectActionPerformed

    private void btnDisconnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDisconnectActionPerformed
        clientService.disconnect();
        log("Đã ngắt kết nối thủ công.");
    }                                           
    
    // Hàm ghi nhật ký vào JTextArea txtLog có gắn nhãn thời gian
    private void log(String text) {
        SwingUtilities.invokeLater(() -> {
            SwingTextLimiter.append(txtLog,
                    "[" + java.time.LocalTime.now().withNano(0) + "] " + text + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    // Tự động lấy Hostname, IP và hiển thị cấu hình ban đầu
    private void loadDeviceInfo() {
        lblClientID.setText(clientID);
        try {
            InetAddress address = InetAddress.getLocalHost();
            hostname = address.getHostName();
            clientIP = address.getHostAddress();
        } catch (Exception exception) {
            hostname = "UNKNOWN-HOST";
            clientIP = "127.0.0.1";
            log("Lỗi lấy thông tin thiết bị: " + exception.getMessage());
        }
        lblHostname.setText(hostname);
        lblClientIP.setText(clientIP);
        SystemStats stats = systemMonitor.read();
        lblCPU.setText(String.format("%.1f%%", stats.cpuPercent()));
        lblRAM.setText(String.format("%.1f%%", stats.ramPercent()));
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
            java.util.logging.Logger.getLogger(frmClient.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(frmClient.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(frmClient.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(frmClient.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new frmClient().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnConnect;
    private javax.swing.JButton btnDisconnect;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel_DeviceInformation;
    private javax.swing.JPanel jPanel_Log;
    private javax.swing.JPanel jPanel_MessageFromTheServer;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JLabel lblAckStatus;
    private javax.swing.JLabel lblCPU;
    private javax.swing.JLabel lblClientID;
    private javax.swing.JLabel lblClientIP;
    private javax.swing.JLabel lblHeartbeat;
    private javax.swing.JLabel lblHostname;
    private javax.swing.JLabel lblRAM;
    private javax.swing.JLabel lblStatus;
    private javax.swing.JPanel pnlContent;
    private javax.swing.JTextArea txtLog;
    private javax.swing.JTextField txtPort;
    private javax.swing.JTextField txtServerIP;
    private javax.swing.JTextArea txtServerMessage;
    // End of variables declaration//GEN-END:variables
}
