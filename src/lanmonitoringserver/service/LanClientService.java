package lanmonitoringserver.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;
import lanmonitoringserver.model.ClientConnectionState;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertNotification;
import lanmonitoringserver.model.AlertSeverity;
import lanmonitoringserver.model.SystemStats;
import lanmonitoringserver.monitor.SystemMonitor;
import lanmonitoringserver.monitor.system.SystemInfoCollector;
import lanmonitoringserver.monitor.system.SystemInfoCollectorFactory;
import lanmonitoringserver.monitor.metrics.MetricsCollector;
import lanmonitoringserver.monitor.metrics.OshiMetricsCollector;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.FileTransferStatus;
import lanmonitoringserver.model.ReceivedFileInfo;
import lanmonitoringserver.network.FramedConnection;
import lanmonitoringserver.protocol.MessageType;
import lanmonitoringserver.protocol.ProtocolException;
import lanmonitoringserver.protocol.ProtocolMessage;
import lanmonitoringserver.protocol.SystemInfoMapper;
import lanmonitoringserver.protocol.TelemetryMapper;
import lanmonitoringserver.protocol.WirelessMapper;
import lanmonitoringserver.protocol.WifiScanMapper;
import lanmonitoringserver.monitor.wireless.WirelessSignalCollector;
import lanmonitoringserver.monitor.wireless.WirelessSignalCollectorFactory;
import lanmonitoringserver.monitor.wireless.MultiAccessPointScanner;
import lanmonitoringserver.monitor.wireless.MultiAccessPointScannerFactory;
import lanmonitoringserver.security.Authentication;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.TlsSupport;
import lanmonitoringserver.util.NamedThreadFactory;
import lanmonitoringserver.util.BuildInfo;

/** Client network/service layer with authentication and reconnect backoff. */
public final class LanClientService implements AutoCloseable {
    private static final long[] RECONNECT_DELAYS_SECONDS = {1, 2, 4, 8, 16, 30};
    private static final long SERVER_BLOCK_RETRY_SECONDS = 5;

    private final ClientCredentials credentials;
    private final SystemMonitor systemMonitor;
    private final SocketFactory socketFactory;
    private final SystemInfoCollector systemInfoCollector;
    private final MetricsCollector metricsCollector;
    private final FileReceiver fileReceiver;
    private final WirelessSignalCollector wirelessCollector;
    private final MultiAccessPointScanner fingerprintScanner;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2,
            new NamedThreadFactory("lan-client", true));
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean collectingSystemInfo = new AtomicBoolean();
    private final ExecutorService systemInfoExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("system-info", true));
    private final ScheduledExecutorService telemetryExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("telemetry", true));
    private final ScheduledExecutorService wirelessExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("wireless", true));
    private final ScheduledExecutorService fingerprintExecutor = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("wifi-fingerprint", true));
    private final Object lifecycleLock = new Object();
    private volatile ClientEventListener listener = new ClientEventListener() {};
    private volatile FramedConnection connection;
    private volatile boolean desiredConnected;
    private volatile boolean authenticated;
    private volatile boolean disconnectedByServer;
    private volatile boolean systemInfoSupported;
    private volatile boolean telemetrySupported;
    private volatile boolean wirelessSupported;
    private volatile boolean fingerprintSupported;
    private volatile boolean wirelessMonitoringEnabled = Boolean.getBoolean("lanmonitoring.wifi.enabled");
    private volatile boolean fingerprintingEnabled = Boolean.getBoolean("lanmonitoring.fingerprint.enabled");
    private volatile boolean closed;
    private volatile String host;
    private volatile int port;
    private volatile String hostname;
    private volatile String ipAddress;
    private volatile String clientNonce;
    private volatile int reconnectAttempt;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> telemetryTask;
    private ScheduledFuture<?> wirelessTask;
    private ScheduledFuture<?> fingerprintTask;
    private volatile SystemInformation cachedSystemInfo;
    private volatile ClientPermissions permissions = ClientPermissions.defaults();
    private volatile boolean secureTransport;
    private final AtomicLong wirelessSamplesSent = new AtomicLong();
    private final AtomicLong fingerprintScansSent = new AtomicLong();
    private volatile String lastWirelessDiagnostic = "";
    private final java.util.concurrent.ConcurrentHashMap<String, IncomingMeta> incomingFiles =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> fileProgressNotificationTimes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> seenAlertIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor) {
        this(credentials, systemMonitor, SocketFactory.getDefault(), SystemInfoCollectorFactory.create());
    }

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor,
            SocketFactory socketFactory) {
        this(credentials, systemMonitor, socketFactory, SystemInfoCollectorFactory.create());
    }

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor,
            SocketFactory socketFactory, SystemInfoCollector systemInfoCollector) {
        this(credentials, systemMonitor, socketFactory, systemInfoCollector, new OshiMetricsCollector());
    }

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor,
            SocketFactory socketFactory, SystemInfoCollector systemInfoCollector,
            MetricsCollector metricsCollector) {
        this(credentials, systemMonitor, socketFactory, systemInfoCollector, metricsCollector,
                FileReceiver.defaultReceiver(), WirelessSignalCollectorFactory.create());
    }

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor,
            SocketFactory socketFactory, SystemInfoCollector systemInfoCollector,
            MetricsCollector metricsCollector, FileReceiver fileReceiver) {
        this(credentials, systemMonitor, socketFactory, systemInfoCollector, metricsCollector,
                fileReceiver, WirelessSignalCollectorFactory.create());
    }

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor,
            SocketFactory socketFactory, SystemInfoCollector systemInfoCollector,
            MetricsCollector metricsCollector, FileReceiver fileReceiver,
            WirelessSignalCollector wirelessCollector) {
        this(credentials, systemMonitor, socketFactory, systemInfoCollector, metricsCollector,
                fileReceiver, wirelessCollector, MultiAccessPointScannerFactory.create());
    }

    public LanClientService(ClientCredentials credentials, SystemMonitor systemMonitor,
            SocketFactory socketFactory, SystemInfoCollector systemInfoCollector,
            MetricsCollector metricsCollector, FileReceiver fileReceiver,
            WirelessSignalCollector wirelessCollector, MultiAccessPointScanner fingerprintScanner) {
        this.credentials = credentials;
        this.systemMonitor = systemMonitor;
        this.socketFactory = socketFactory;
        this.systemInfoCollector = systemInfoCollector;
        this.metricsCollector = metricsCollector;
        this.fileReceiver = fileReceiver;
        this.wirelessCollector = wirelessCollector;
        this.fingerprintScanner = fingerprintScanner;
    }

    public void setListener(ClientEventListener listener) {
        this.listener = listener == null ? new ClientEventListener() {} : listener;
    }

    public String clientId() { return credentials.clientId(); }
    public boolean isAuthenticated() { return authenticated; }
    public boolean isWirelessMonitoringEnabled() { return wirelessMonitoringEnabled; }
    public boolean isFingerprintingEnabled() { return fingerprintingEnabled; }

    public void probeFingerprintScanner() {
        if (closed) throw new IllegalStateException("Client service is closed");
        fingerprintExecutor.execute(() -> {
            try {
                var scan = fingerprintScanner.scan();
                listener.onLog("Wi-Fi fingerprint scanner probe: status=" + scan.status()
                        + ", APs with real RSSI=" + scan.accessPoints().size()
                        + ", provider=" + scan.provider() + ". " + scan.detail());
            } catch (RuntimeException error) {
                listener.onLog("Wi-Fi fingerprint scanner probe failed: " + safeMessage(error));
            }
        });
    }

    public void setFingerprintingEnabled(boolean enabled) {
        fingerprintingEnabled = enabled;
        String schedulerState;
        synchronized (lifecycleLock) {
            cancel(fingerprintTask);
            fingerprintTask = null;
            if (enabled && authenticated && fingerprintSupported) scheduleFingerprinting();
            schedulerState = fingerprintSchedulerState();
        }
        listener.onLog("Wi-Fi fingerprint scanning " + (enabled ? "enabled" : "disabled")
                + " (scheduler " + schedulerState + "). Multi-AP identifiers remain hashed; "
                + "results are position estimates, not direct ranging.");
    }

    /** Runs one privacy-safe local probe without enabling periodic collection or sending data. */
    public void probeWirelessCollector() {
        if (closed) throw new IllegalStateException("Client service is closed");
        wirelessExecutor.execute(() -> {
            try {
                var sample = wirelessCollector.collect().forSharing(false);
                listener.onLog("Wi-Fi collector probe: status=" + sample.status()
                        + ", network=" + sample.networkType() + ", RSSI="
                        + metric(sample.rssiDbm(), " dBm") + ", quality="
                        + metric(sample.linkQualityPercent(), "%") + ", AP target="
                        + (sample.targetId().isBlank() ? "NOT_AVAILABLE" : "available (privacy hash)")
                        + ", provider=" + sample.provider() + ".");
                if (sample.status() != lanmonitoringserver.model.MeasurementStatus.OK) {
                    listener.onLog("Wi-Fi collector probe detail: " + sample.detail());
                }
            } catch (RuntimeException exception) {
                listener.onLog("Wi-Fi collector probe failed: " + safeMessage(exception));
            }
        });
    }

    public void setWirelessMonitoringEnabled(boolean enabled) {
        wirelessMonitoringEnabled = enabled;
        synchronized (lifecycleLock) {
            cancel(wirelessTask);
            wirelessTask = null;
            if (enabled && authenticated && wirelessSupported) scheduleWireless();
        }
        String schedulerState = !enabled ? "stopped"
                : authenticated && wirelessSupported ? "scheduled"
                : "waiting for modern authentication and WIRELESS_SIGNAL_V1";
        listener.onLog("Wi-Fi measurement " + (enabled ? "enabled" : "disabled")
                + " (scheduler " + schedulerState + "). RSSI describes Client-to-AP signal, "
                + "not Client-to-Server distance.");
    }

    public void connect(String host, int port, String hostname, String ipAddress) {
        if (closed) throw new IllegalStateException("Client service is closed");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Server IP must not be empty");
        synchronized (lifecycleLock) {
            this.host = host.trim();
            this.port = port;
            this.hostname = hostname;
            this.ipAddress = ipAddress;
            desiredConnected = true;
            authenticated = false;
            disconnectedByServer = false;
            systemInfoSupported = false;
            telemetrySupported = false;
            wirelessSupported = false;
            fingerprintSupported = false;
            telemetrySupported = false;
            wirelessSupported = false;
            fingerprintSupported = false;
            wirelessSamplesSent.set(0);
            fingerprintScansSent.set(0);
            lastWirelessDiagnostic = "";
            reconnectAttempt = 0;
            cancel(reconnectTask);
            closeConnection();
            long currentGeneration = generation.incrementAndGet();
            updateState(ClientConnectionState.CONNECTING, "Connecting to " + host + ":" + port);
            executor.execute(() -> openConnection(currentGeneration));
        }
    }

    public void disconnect() {
        synchronized (lifecycleLock) {
            desiredConnected = false;
            authenticated = false;
            disconnectedByServer = false;
            systemInfoSupported = false;
            generation.incrementAndGet();
            cancel(heartbeatTask);
            cancel(telemetryTask);
            cancel(wirelessTask);
            cancel(fingerprintTask);
            cancel(reconnectTask);
            closeConnection();
        }
        updateState(ClientConnectionState.DISCONNECTED, "Disconnected");
    }

    private void openConnection(long expectedGeneration) {
        if (!shouldRun(expectedGeneration)) return;
        Socket socket = null;
        try {
            String tlsIdentity = TlsSupport.serverIdentity();
            if (!tlsIdentity.isEmpty() && !(socketFactory instanceof SSLSocketFactory)) {
                throw new ProtocolException("A fixed TLS Server identity is configured but TLS is not enabled");
            }
            if (!tlsIdentity.isEmpty()) {
                Socket transport = new Socket();
                try {
                    transport.connect(new InetSocketAddress(host, port), 5_000);
                    socket = ((SSLSocketFactory) socketFactory).createSocket(
                            transport, tlsIdentity, port, true);
                } catch (IOException | RuntimeException error) {
                    closeQuietly(transport);
                    throw error;
                }
            } else {
                socket = socketFactory.createSocket();
                socket.connect(new InetSocketAddress(host, port), 5_000);
            }
            if (socket instanceof SSLSocket tlsSocket) {
                javax.net.ssl.SSLParameters parameters = tlsSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!tlsIdentity.isEmpty()) {
                    parameters.setServerNames(java.util.List.of(new SNIHostName(tlsIdentity)));
                }
                tlsSocket.setSSLParameters(parameters);
                tlsSocket.startHandshake();
                listener.onLog("TLS identity verified: TCP endpoint=" + host + ":" + port
                        + ", certificate identity="
                        + (tlsIdentity.isEmpty() ? host : tlsIdentity) + ".");
            }
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15_000);
            secureTransport = socket instanceof SSLSocket;
            listener.onLog("Transport connected: " + (secureTransport ? "TLS" : "plain TCP")
                    + "; WIFI_FINGERPRINT_V1 requires TLS by default.");
            FramedConnection opened = new FramedConnection(socket);
            synchronized (lifecycleLock) {
                if (!shouldRun(expectedGeneration)) {
                    opened.close();
                    return;
                }
                connection = opened;
            }
            updateState(ClientConnectionState.AUTHENTICATING, "Authenticating client identity");
            sendConnect();
            readLoop(opened, expectedGeneration);
        } catch (IOException | RuntimeException exception) {
            closeQuietly(socket);
            if (shouldRun(expectedGeneration)) {
                listener.onLog("Connection failed: " + exception.getMessage());
                scheduleReconnect(expectedGeneration);
            }
        }
    }

    private void readLoop(FramedConnection opened, long expectedGeneration) {
        try {
            ProtocolMessage message;
            while (shouldRun(expectedGeneration) && (message = opened.read()) != null) {
                handleMessage(message);
            }
            if (shouldRun(expectedGeneration)) throw new IOException("Server closed the connection");
        } catch (IOException | RuntimeException exception) {
            if (shouldRun(expectedGeneration)) listener.onLog("Connection lost: " + exception.getMessage());
        } finally {
            synchronized (lifecycleLock) {
                if (connection == opened) {
                    connection = null;
                    authenticated = false;
                    secureTransport = false;
                    systemInfoSupported = false;
                    telemetrySupported = false;
                    wirelessSupported = false;
                    fingerprintSupported = false;
                    cancel(heartbeatTask);
                    cancel(telemetryTask);
                    cancel(wirelessTask);
                    cancel(fingerprintTask);
                }
            }
            for (Map.Entry<String, IncomingMeta> entry : incomingFiles.entrySet()) {
                if (incomingFiles.remove(entry.getKey(), entry.getValue())) {
                    fileReceiver.cancel(entry.getKey());
                    emitIncoming(entry.getValue(), entry.getKey(), 0, FileTransferStatus.FAILED,
                            "Connection lost", null);
                }
            }
            closeQuietly(opened);
            if (shouldRun(expectedGeneration)) scheduleReconnect(expectedGeneration);
        }
    }

    private void handleMessage(ProtocolMessage message) throws IOException {
        if (!credentials.clientId().equals(message.clientId())) {
            throw new ProtocolException("Server response contains another client identity");
        }
        switch (message.type()) {
            case ERROR -> handleError(message);
            case REGISTERED -> sendConnect();
            case CHALLENGE -> {
                String serverNonce = message.requireString("serverNonce", 128);
                String proof = Authentication.proof(credentials.secret(), credentials.clientId(),
                        clientNonce, serverNonce);
                send(ProtocolMessage.reply(MessageType.AUTH, message, credentials.clientId(),
                        Map.of("proof", proof)));
            }
            case AUTH_OK -> handleAuthenticated(message);
            case PING -> send(ProtocolMessage.reply(MessageType.PONG, message,
                    credentials.clientId(), Map.of("receivedAt", System.currentTimeMillis())));
            case SYSTEM_INFO_REQUEST -> {
                if (permissions.exportSystemInformation()) collectAndSendSystemInfo(true);
                else listener.onLog("SYSTEM_INFO request denied by Server-assigned Client permission.");
            }
            case ALERT -> handleAlert(message);
            case PERMISSIONS_UPDATE -> handlePermissions(message);
            case FILE_OFFER -> handleFileOffer(message);
            case FILE_CHUNK -> handleFileChunk(message);
            case FILE_COMPLETE -> handleFileComplete(message);
            case FILE_CANCEL -> handleFileCancel(message);
            case MESSAGE -> handleServerMessage(message);
            case SERVER_DISCONNECT -> handleServerDisconnect();
            default -> throw new ProtocolException("Unexpected server message type: " + message.type());
        }
    }

    private void handleServerDisconnect() {
        boolean notify = !disconnectedByServer;
        disconnectedByServer = true;
        authenticated = false;
        secureTransport = false;
        systemInfoSupported = false;
        telemetrySupported = false;
        wirelessSupported = false;
        fingerprintSupported = false;
        synchronized (lifecycleLock) {
            cancel(heartbeatTask);
            cancel(telemetryTask);
            cancel(wirelessTask);
            cancel(fingerprintTask);
        }
        updateState(ClientConnectionState.DISCONNECTED_BY_SERVER,
                "Server disconnected this Client; retrying permission every 5 seconds");
        if (notify) listener.onDisconnectedByServer();
        closeConnection();
    }

    private void handleError(ProtocolMessage message) throws IOException {
        String code = message.requireString("code", 64);
        String description = message.requireString("message", 512);
        if ("NOT_REGISTERED".equals(code)) {
            send(ProtocolMessage.create(MessageType.REGISTER, credentials.clientId(),
                    Map.of("secret", credentials.secret())));
            listener.onLog("Registering this client identity with the server.");
            return;
        }
        if ("CLIENT_BLOCKED".equals(code)) {
            disconnectedByServer = true;
            authenticated = false;
            updateState(ClientConnectionState.DISCONNECTED_BY_SERVER,
                    "Waiting for Server permission; retrying every 5 seconds");
            throw new ProtocolException("Server has not allowed this Client to reconnect yet");
        }
        throw new ProtocolException("Server rejected connection: " + code + " - " + description);
    }

    private void sendConnect() throws IOException {
        clientNonce = Authentication.newNonce();
        send(ProtocolMessage.create(MessageType.CONNECT, credentials.clientId(), Map.of(
                "hostname", hostname,
                "ipAddress", ipAddress,
                "operatingSystem", System.getProperty("os.name", "Unknown") + " "
                        + System.getProperty("os.version", ""),
                "clientRelease", BuildInfo.RELEASE_ID,
                "clientNonce", clientNonce)));
    }

    private void handleAuthenticated(ProtocolMessage message) throws ProtocolException {
        boolean reconnectedAfterServerDisconnect = disconnectedByServer;
        disconnectedByServer = false;
        authenticated = true;
        Object capabilities = message.payload().get("capabilities");
        systemInfoSupported = capabilities instanceof Iterable<?> values
                && java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch("SYSTEM_INFO_V1"::equals);
        telemetrySupported = capabilities instanceof Iterable<?> values
                && java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch("TELEMETRY_V1"::equals);
        wirelessSupported = capabilities instanceof Iterable<?> values
                && java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch("WIRELESS_SIGNAL_V1"::equals);
        fingerprintSupported = capabilities instanceof Iterable<?> fingerprintValues
                && java.util.stream.StreamSupport.stream(fingerprintValues.spliterator(), false)
                        .anyMatch("WIFI_FINGERPRINT_V1"::equals);
        String fingerprintReason = message.optionalString("wifiFingerprintReason", 256,
                fingerprintSupported ? "advertised by Server" : "not reported by Server");
        listener.onLog("Protocol negotiation: modern JSON v" + ProtocolMessage.CURRENT_VERSION
                + "; transport=" + (secureTransport ? "TLS" : "plain TCP")
                + "; WIRELESS_SIGNAL_V1=" + wirelessSupported
                + "; WIFI_FINGERPRINT_V1=" + fingerprintSupported
                + "; Wi-Fi measurement enabled=" + wirelessMonitoringEnabled
                + "; multi-AP enabled=" + fingerprintingEnabled + ".");
        if (!wirelessSupported) {
            listener.onLog("Wi-Fi scheduler not started: Server did not advertise WIRELESS_SIGNAL_V1. "
                    + "Verify that Server and Client both use the latest dist JAR.");
        } else if (!wirelessMonitoringEnabled) {
            listener.onLog("Wi-Fi scheduler not started: enable Wi-Fi Research > Enable Wi-Fi measurement.");
        }
        if (!fingerprintSupported) {
            listener.onLog("Wi-Fi fingerprint scheduler not started: " + fingerprintReason + ".");
        } else if (!fingerprintingEnabled) {
            listener.onLog("Wi-Fi fingerprint scheduler not started: enable Wi-Fi Research > "
                    + "Enable multi-AP fingerprint scans.");
        }
        reconnectAttempt = 0;
        updateState(ClientConnectionState.CONNECTED, "Authenticated with protocol v"
                + ProtocolMessage.CURRENT_VERSION);
        synchronized (lifecycleLock) {
            cancel(heartbeatTask);
            heartbeatTask = executor.scheduleAtFixedRate(this::sendHeartbeat,
                    0, 3, TimeUnit.SECONDS);
            cancel(telemetryTask);
            if (telemetrySupported) {
                long interval = telemetryIntervalMillis();
                telemetryTask = telemetryExecutor.scheduleAtFixedRate(this::sendTelemetry,
                        0, interval, TimeUnit.MILLISECONDS);
                listener.onLog("Realtime telemetry started every " + interval + " ms using "
                        + metricsCollector.collectorName());
            }
            cancel(wirelessTask);
            wirelessTask = null;
            if (wirelessMonitoringEnabled && wirelessSupported) scheduleWireless();
            cancel(fingerprintTask);
            fingerprintTask = null;
            if (fingerprintingEnabled && fingerprintSupported) scheduleFingerprinting();
        }
        if (systemInfoSupported) collectAndSendSystemInfo(false);
        if (reconnectedAfterServerDisconnect) listener.onReconnectedAfterServerDisconnect();
    }

    private void collectAndSendSystemInfo(boolean forceRefresh) {
        if (!systemInfoSupported) {
            listener.onLog("Server does not advertise SYSTEM_INFO_V1 capability.");
            return;
        }
        if (!collectingSystemInfo.compareAndSet(false, true)) {
            listener.onLog("System information collection is already running.");
            return;
        }
        systemInfoExecutor.execute(() -> {
            try {
                SystemInformation information = cachedSystemInfo;
                if (forceRefresh || information == null) {
                    listener.onLog("Collecting static system information using "
                            + systemInfoCollector.collectorName() + "...");
                    information = systemInfoCollector.collect();
                    cachedSystemInfo = information;
                }
                if (authenticated && desiredConnected) {
                    send(ProtocolMessage.create(MessageType.SYSTEM_INFO, credentials.clientId(),
                            Map.of("systemInfo", SystemInfoMapper.toMap(information))));
                    listener.onLog("SYSTEM_INFO sent to server.");
                }
            } catch (Exception exception) {
                listener.onLog("System information collection/send failed: " + exception.getMessage());
            } finally {
                collectingSystemInfo.set(false);
            }
        });
    }

    private void sendHeartbeat() {
        if (!authenticated || !desiredConnected) return;
        try {
            SystemStats stats = systemMonitor.read();
            listener.onStats(stats);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cpuPercent", stats.cpuPercent());
            payload.put("ramPercent", stats.ramPercent());
            if (stats.gpuPercent() != null) payload.put("gpuPercent", stats.gpuPercent());
            if (stats.diskPercent() != null) payload.put("diskPercent", stats.diskPercent());
            send(ProtocolMessage.create(MessageType.HEARTBEAT, credentials.clientId(), payload));
        } catch (IOException | RuntimeException exception) {
            listener.onLog("Heartbeat failed: " + exception.getMessage());
            closeConnection();
        }
    }

    private void sendTelemetry() {
        if (!authenticated || !desiredConnected || !telemetrySupported) return;
        try {
            var sample = metricsCollector.collect();
            send(ProtocolMessage.create(MessageType.TELEMETRY, credentials.clientId(),
                    Map.of("sample", TelemetryMapper.toMap(sample))));
        } catch (IOException | RuntimeException exception) {
            listener.onLog("Telemetry failed: " + exception.getMessage());
            closeConnection();
        }
    }

    private static long telemetryIntervalMillis() {
        long configured = Long.getLong("lanmonitoring.telemetry.interval.ms", 1_000L);
        return Math.max(500L, Math.min(10_000L, configured));
    }

    private void scheduleWireless() {
        long interval = wirelessIntervalMillis();
        wirelessTask = wirelessExecutor.scheduleAtFixedRate(this::sendWirelessSignal,
                0, interval, TimeUnit.MILLISECONDS);
        listener.onLog("Wi-Fi signal measurement started every " + interval + " ms using "
                + wirelessCollector.collectorName());
    }

    private void sendWirelessSignal() {
        if (!authenticated || !desiredConnected || !wirelessSupported || !wirelessMonitoringEnabled) return;
        try {
            boolean shareIdentifiers = Boolean.getBoolean("lanmonitoring.wifi.share.identifiers");
            var sample = wirelessCollector.collect().forSharing(shareIdentifiers);
            send(ProtocolMessage.create(MessageType.WIRELESS_SIGNAL, credentials.clientId(),
                    Map.of("sample", WirelessMapper.toMap(sample))));
            long count = wirelessSamplesSent.incrementAndGet();
            String diagnostic = sample.status() + "/" + sample.networkType() + "/"
                    + (sample.rssiDbm() == null ? "no-rssi" : "rssi") + "/"
                    + (sample.linkQualityPercent() == null ? "no-quality" : "quality");
            if (count == 1 || count % 30 == 0 || !diagnostic.equals(lastWirelessDiagnostic)) {
                lastWirelessDiagnostic = diagnostic;
                listener.onLog("WIRELESS_SIGNAL sent #" + count + ": status=" + sample.status()
                        + ", network=" + sample.networkType() + ", RSSI="
                        + metric(sample.rssiDbm(), " dBm") + ", quality="
                        + metric(sample.linkQualityPercent(), "%") + ", identifiers="
                        + (shareIdentifiers ? "shared by opt-in" : "hidden") + ".");
            }
        } catch (IOException | RuntimeException exception) {
            listener.onLog("Wi-Fi measurement failed: " + exception.getMessage());
        }
    }

    private void scheduleFingerprinting() {
        long interval = fingerprintIntervalMillis();
        fingerprintTask = fingerprintExecutor.scheduleWithFixedDelay(this::sendFingerprintScan,
                0, interval, TimeUnit.MILLISECONDS);
        listener.onLog("Wi-Fi fingerprint multi-AP scan started every " + interval + " ms using "
                + fingerprintScanner.scannerName());
    }

    private String fingerprintSchedulerState() {
        if (!fingerprintingEnabled) return "stopped: disabled by user";
        if (!authenticated) return "waiting: Client is not authenticated";
        if (!fingerprintSupported) {
            return "blocked: Server did not advertise WIFI_FINGERPRINT_V1 (TLS is required by default)";
        }
        return fingerprintTask != null && !fingerprintTask.isCancelled()
                ? "scheduled" : "waiting to schedule";
    }

    private void sendFingerprintScan() {
        if (!authenticated || !desiredConnected || !fingerprintSupported || !fingerprintingEnabled) return;
        try {
            var scan = fingerprintScanner.scan();
            send(ProtocolMessage.create(MessageType.WIFI_SCAN, credentials.clientId(),
                    Map.of("scan", WifiScanMapper.toMap(scan))));
            long count = fingerprintScansSent.incrementAndGet();
            if (count == 1 || count % 12 == 0 || scan.status() != lanmonitoringserver.model.MeasurementStatus.OK) {
                listener.onLog("WIFI_SCAN sent #" + count + ": status=" + scan.status()
                        + ", hashed APs=" + scan.accessPoints().size() + ". " + scan.detail());
            }
        } catch (IOException error) {
            listener.onLog("Wi-Fi fingerprint transport failed: " + safeMessage(error));
            closeConnection();
        } catch (RuntimeException error) {
            listener.onLog("Wi-Fi fingerprint scan failed without fabricated data: " + safeMessage(error));
        }
    }

    private static long fingerprintIntervalMillis() {
        return Math.max(5_000L, Math.min(300_000L,
                Long.getLong("lanmonitoring.fingerprint.interval.ms", 10_000L)));
    }

    private static String metric(Double value, String unit) {
        return value == null ? "NOT_AVAILABLE" : value + unit;
    }

    private static long wirelessIntervalMillis() {
        long configured = Long.getLong("lanmonitoring.wifi.interval.ms", 2_000L);
        return Math.max(1_000L, Math.min(30_000L, configured));
    }

    private void handleServerMessage(ProtocolMessage message) throws IOException {
        if (!authenticated) throw new ProtocolException("MESSAGE received before authentication");
        String content = message.requireString("content", 64 * 1024);
        listener.onMessage(content, message.requestId());
        send(ProtocolMessage.reply(MessageType.ACK, message, credentials.clientId(),
                Map.of("status", "MESSAGE_RECEIVED")));
        listener.onAcknowledgementSent(message.requestId());
    }

    private void handleAlert(ProtocolMessage message) throws IOException {
        if (!authenticated) throw new ProtocolException("ALERT received before authentication");
        String alertId = message.requireString("alertId", 128);
        String alertHostname = message.requireString("hostname", 255);
        AlertMetric metric;
        AlertSeverity severity;
        try {
            metric = AlertMetric.valueOf(message.requireString("metric", 64));
            severity = AlertSeverity.valueOf(message.requireString("severity", 32));
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Invalid ALERT enum value", exception);
        }
        double value = message.requireDouble("value", 0, 100);
        double threshold = message.requireDouble("threshold", 0, 100);
        Object occurred = message.payload().get("occurredAt");
        if (!(occurred instanceof Number number) || number.longValue() <= 0) {
            throw new ProtocolException("Invalid ALERT occurredAt");
        }
        boolean mandatory = message.payload().get("mandatory") instanceof Boolean flag && flag;
        if (mandatory && metric != AlertMetric.RAM) {
            throw new ProtocolException("Only automatic RAM alerts may bypass Admin alerts permission");
        }
        if (!mandatory && !permissions.receiveAlerts()) {
            listener.onLog("Administrative ALERT ignored by Server-assigned notification permission.");
            return;
        }
        String notificationId = message.optionalString("notificationId", 128, alertId);
        String title = message.optionalString("title", 255, null);
        String content = message.optionalString("content", 4_096, null);
        if ((title == null) != (content == null)) throw new ProtocolException("Incomplete ALERT template");
        AlertNotification alert = new AlertNotification(alertId, alertHostname, metric,
                severity, value, threshold, number.longValue(), notificationId,
                title, content, mandatory);
        if (seenAlertIds.size() > 512) seenAlertIds.clear();
        if (seenAlertIds.add(notificationId)) listener.onAlert(alert);
        send(ProtocolMessage.reply(MessageType.ALERT_ACK, message, credentials.clientId(),
                Map.of("alertId", alertId, "status", "ALERT_RECEIVED")));
        listener.onLog("ALERT acknowledged: " + alertId);
    }

    private void handlePermissions(ProtocolMessage message) throws ProtocolException {
        if (!authenticated) throw new ProtocolException("PERMISSIONS_UPDATE before authentication");
        if (!secureTransport) throw new ProtocolException("Permission changes require TLS");
        permissions = new ClientPermissions(message.requireBoolean("receiveFiles"),
                message.requireBoolean("receiveAlerts"),
                message.requireBoolean("exportSystemInformation"));
        listener.onPermissionsChanged(permissions);
        listener.onLog("Client permissions updated by authenticated Server.");
    }

    private void handleFileOffer(ProtocolMessage message) throws IOException {
        requireSecureFileSession();
        String transferId = message.requireString("transferId", 128);
        String fileId = message.requireString("fileId", 128);
        String fileName = message.requireString("fileName", 240);
        long size = message.requireLong("fileSize", 0, Long.MAX_VALUE);
        String sha256 = message.requireString("sha256", 64);
        FileReceiver.OfferResult result = fileReceiver.begin(transferId, fileName, size, sha256,
                permissions.receiveFiles());
        if (!result.accepted()) {
            send(ProtocolMessage.reply(MessageType.FILE_REJECT, message, credentials.clientId(),
                    Map.of("transferId", transferId, "reason", result.reason())));
            emitTransfer(new FileTransferProgress(transferId, fileId, credentials.clientId(),
                    hostname, fileName, size, sha256, 0, 0, FileTransferStatus.FAILED,
                    result.reason(), null, System.currentTimeMillis(), System.currentTimeMillis()));
            return;
        }
        long now = System.currentTimeMillis();
        incomingFiles.put(transferId, new IncomingMeta(fileId, fileName, size, sha256,
                result.targetPath(), now));
        send(ProtocolMessage.reply(MessageType.FILE_ACCEPT, message, credentials.clientId(),
                Map.of("transferId", transferId)));
        emitIncoming(transferId, 0, FileTransferStatus.OFFERED, "Accepted", null);
    }

    private void handleFileChunk(ProtocolMessage message) throws IOException {
        requireSecureFileSession();
        String transferId = message.requireString("transferId", 128);
        long offset = message.requireLong("offset", 0, Long.MAX_VALUE);
        String data = message.requireString("data", 100_000);
        long received = fileReceiver.acceptChunk(transferId, offset, data);
        emitIncoming(transferId, received, FileTransferStatus.IN_PROGRESS, "Receiving", null);
    }

    private void handleFileComplete(ProtocolMessage message) throws IOException {
        requireSecureFileSession();
        String transferId = message.requireString("transferId", 128);
        IncomingMeta meta = incomingFiles.get(transferId);
        if (meta == null) throw new ProtocolException("Unknown transfer");
        emitIncoming(transferId, meta.size, FileTransferStatus.VERIFYING, "Verifying SHA-256", null);
        try {
            ReceivedFileInfo received = fileReceiver.finish(transferId);
            incomingFiles.remove(transferId);
            send(ProtocolMessage.reply(MessageType.FILE_RESULT, message, credentials.clientId(),
                    Map.of("transferId", transferId, "status", "SUCCESS",
                            "savedPath", received.savedPath(), "checksumVerified", true)));
            emitIncoming(meta, transferId, meta.size, FileTransferStatus.SUCCESS,
                    "Checksum verified", received.savedPath());
            listener.onFileReceived(received);
        } catch (IOException exception) {
            fileReceiver.cancel(transferId);
            incomingFiles.remove(transferId);
            send(ProtocolMessage.reply(MessageType.FILE_RESULT, message, credentials.clientId(),
                    Map.of("transferId", transferId, "status", "FAILED",
                            "reason", safeMessage(exception), "checksumVerified", false)));
            emitIncoming(meta, transferId, 0, FileTransferStatus.FAILED,
                    safeMessage(exception), null);
        }
    }

    private void handleFileCancel(ProtocolMessage message) throws IOException {
        requireSecureFileSession();
        String transferId = message.requireString("transferId", 128);
        IncomingMeta meta = incomingFiles.remove(transferId);
        fileReceiver.cancel(transferId);
        if (meta != null) emitIncoming(meta, transferId, 0, FileTransferStatus.CANCELLED,
                "Cancelled by Server", null);
        send(ProtocolMessage.reply(MessageType.FILE_RESULT, message, credentials.clientId(),
                Map.of("transferId", transferId, "status", "CANCELLED",
                        "checksumVerified", false)));
    }

    private void requireSecureFileSession() throws ProtocolException {
        if (!authenticated || !secureTransport) {
            throw new ProtocolException("File transfer requires an authenticated TLS session");
        }
    }

    private void emitIncoming(String transferId, long bytes, FileTransferStatus status,
            String detail, String savedPath) {
        IncomingMeta meta = incomingFiles.get(transferId);
        if (meta != null) emitIncoming(meta, transferId, bytes, status, detail, savedPath);
    }

    private void emitIncoming(IncomingMeta meta, String transferId, long bytes,
            FileTransferStatus status, String detail, String savedPath) {
        long now = System.currentTimeMillis();
        double speed = bytes <= 0 ? 0 : bytes * 1000d / Math.max(1, now - meta.startedAt);
        emitTransfer(new FileTransferProgress(transferId, meta.fileId, credentials.clientId(),
                hostname, meta.fileName, meta.size, meta.sha256, Math.min(bytes, meta.size), speed,
                status, detail, savedPath == null ? meta.targetPath : savedPath, meta.startedAt, now));
    }

    private void emitTransfer(FileTransferProgress progress) {
        long now = System.currentTimeMillis();
        boolean immediate = progress.status() != FileTransferStatus.IN_PROGRESS
                || now - fileProgressNotificationTimes.getOrDefault(progress.transferId(), 0L) >= 200;
        if (immediate) {
            if (progress.status() == FileTransferStatus.IN_PROGRESS) {
                fileProgressNotificationTimes.put(progress.transferId(), now);
            } else if (progress.terminal()) {
                fileProgressNotificationTimes.remove(progress.transferId());
            }
            listener.onFileTransferProgress(progress);
        }
    }

    public ClientPermissions permissions() { return permissions; }
    public java.util.List<ReceivedFileInfo> receivedFiles() { return fileReceiver.receivedFiles(); }

    private static String safeMessage(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void send(ProtocolMessage message) throws IOException {
        FramedConnection current = connection;
        if (current == null) throw new IOException("No active connection");
        current.send(message);
    }

    private void scheduleReconnect(long expectedGeneration) {
        synchronized (lifecycleLock) {
            if (!shouldRun(expectedGeneration) || reconnectTask != null && !reconnectTask.isDone()) return;
            long delay;
            if (disconnectedByServer) {
                delay = SERVER_BLOCK_RETRY_SECONDS;
                updateState(ClientConnectionState.DISCONNECTED_BY_SERVER,
                        "Waiting for Server permission; next retry in 5 seconds");
            } else {
                int index = Math.min(reconnectAttempt, RECONNECT_DELAYS_SECONDS.length - 1);
                delay = RECONNECT_DELAYS_SECONDS[index];
                reconnectAttempt++;
                updateState(ClientConnectionState.RECONNECTING,
                        "Reconnecting in " + delay + " second(s), attempt " + reconnectAttempt);
            }
            reconnectTask = executor.schedule(() -> {
                synchronized (lifecycleLock) { reconnectTask = null; }
                if (shouldRun(expectedGeneration)) openConnection(expectedGeneration);
            }, delay, TimeUnit.SECONDS);
        }
    }

    private boolean shouldRun(long expectedGeneration) {
        return !closed && desiredConnected && generation.get() == expectedGeneration;
    }

    private void updateState(ClientConnectionState state, String detail) {
        listener.onStateChanged(state, detail);
        listener.onLog(detail);
    }

    private void closeConnection() {
        FramedConnection current = connection;
        connection = null;
        closeQuietly(current);
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) task.cancel(true);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
        }
        disconnect();
        executor.shutdownNow();
        systemInfoExecutor.shutdownNow();
        telemetryExecutor.shutdownNow();
        wirelessExecutor.shutdownNow();
        fingerprintExecutor.shutdownNow();
        closeQuietly(metricsCollector);
        closeQuietly(wirelessCollector);
        closeQuietly(fingerprintScanner);
        closeQuietly(fileReceiver);
    }

    private record IncomingMeta(String fileId, String fileName, long size, String sha256,
            String targetPath, long startedAt) {}
}
