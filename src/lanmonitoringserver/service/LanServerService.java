package lanmonitoringserver.service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collection;
import java.util.Set;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLSocket;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.ClientMetrics;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.MetricReading;
import lanmonitoringserver.model.NotificationTemplate;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.FileTransferStatus;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.model.TelemetryRingBuffer;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.model.NetworkLatencySample;
import lanmonitoringserver.model.WirelessCalibration;
import lanmonitoringserver.model.WirelessCalibrationPoint;
import lanmonitoringserver.model.WirelessObservation;
import lanmonitoringserver.model.ServerClientDistance;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.model.FingerprintSample;
import lanmonitoringserver.model.EstimatedClientPosition;
import lanmonitoringserver.network.FramedConnection;
import lanmonitoringserver.protocol.MessageType;
import lanmonitoringserver.protocol.ProtocolException;
import lanmonitoringserver.protocol.ProtocolMessage;
import lanmonitoringserver.protocol.SystemInfoMapper;
import lanmonitoringserver.protocol.TelemetryMapper;
import lanmonitoringserver.protocol.WirelessMapper;
import lanmonitoringserver.protocol.WifiScanMapper;
import lanmonitoringserver.security.Authentication;
import lanmonitoringserver.security.CredentialRegistry;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.util.InputValidator;
import lanmonitoringserver.util.NamedThreadFactory;

/** Network/service layer for the monitoring server. It has no Swing dependency. */
public final class LanServerService implements AutoCloseable {
    private static final long CLIENT_TIMEOUT_MILLIS = 10_000;
    private static final int LEGACY_MAX_LINE_LENGTH = 64 * 1024;
    private static final int MAX_PENDING_PINGS_PER_SESSION = 8;
    private static final long PENDING_PING_RETENTION_MILLIS = 30_000L;
    private static final int DEFAULT_TRANSFER_HISTORY_LIMIT = 1_000;

    private final CredentialRegistry credentials;
    private final ServerSocketFactory socketFactory;
    private final ConcurrentHashMap<String, ClientRecord> clients = new ConcurrentHashMap<>();
    private final Set<String> reconnectBlockedClients = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Session, Boolean> sessions = new ConcurrentHashMap<>();
    private final AlertStore alertStore;
    private final AlertEngine alertEngine;
    private final ClientPermissionStore permissionStore;
    private final AuditLogger auditLogger;
    private final AuthorizationContext authorization;
    private final WirelessCalibrationStore wirelessCalibrationStore;
    private final ServerClientDistanceService distanceService;
    private final RadioMapStore radioMapStore;
    private final WifiFingerprintEstimator fingerprintEstimator = new WifiFingerprintEstimator();
    private final ConcurrentHashMap<String, FileTransferProgress> fileTransfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TransferControl> transferControls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> transferNotificationTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> manualNotificationDedupTimes = new ConcurrentHashMap<>();
    private final ExecutorService fileTransferExecutor = Executors.newFixedThreadPool(3,
            new NamedThreadFactory("file-transfer", true));
    private final ExecutorService alertPersistenceExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("alert-store", true));
    private final AtomicBoolean alertPersistenceScheduled = new AtomicBoolean();
    private final AtomicBoolean alertPersistenceDirty = new AtomicBoolean();
    private final String alertStoreLoadError;
    private final AtomicBoolean alertPersistenceAvailable = new AtomicBoolean();
    private final AtomicBoolean serviceClosed = new AtomicBoolean();
    private volatile String alertPersistenceError;
    private volatile ServerEventListener listener = new ServerEventListener() {};
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private ExecutorService acceptExecutor;
    private ExecutorService networkExecutor;
    private ScheduledExecutorService scheduler;

    public LanServerService(CredentialRegistry credentials) {
        this(credentials, ServerSocketFactory.getDefault(), FileAlertStore.defaultStore(),
                defaultPermissionStore(), AuditLogger.defaultLogger(),
                AuthorizationContext.fromSystemProperties(), defaultWirelessCalibrationStore(),
                defaultDistanceStore(), defaultRadioMapStore());
    }

    public LanServerService(CredentialRegistry credentials, ServerSocketFactory socketFactory) {
        this(credentials, socketFactory, FileAlertStore.defaultStore(), defaultPermissionStore(),
                AuditLogger.defaultLogger(), AuthorizationContext.fromSystemProperties(),
                defaultWirelessCalibrationStore(), defaultDistanceStore(), defaultRadioMapStore());
    }

    public LanServerService(CredentialRegistry credentials, ServerSocketFactory socketFactory,
            AlertStore alertStore) {
        this(credentials, socketFactory, alertStore, defaultPermissionStore(),
                AuditLogger.defaultLogger(), AuthorizationContext.fromSystemProperties(),
                WirelessCalibrationStore.inMemory(), ServerClientDistanceStore.inMemory());
    }

    public LanServerService(CredentialRegistry credentials, ServerSocketFactory socketFactory,
            AlertStore alertStore, ClientPermissionStore permissionStore, AuditLogger auditLogger,
            AuthorizationContext authorization) {
        this(credentials, socketFactory, alertStore, permissionStore, auditLogger, authorization,
                WirelessCalibrationStore.inMemory(), ServerClientDistanceStore.inMemory());
    }

    public LanServerService(CredentialRegistry credentials, ServerSocketFactory socketFactory,
            AlertStore alertStore, ClientPermissionStore permissionStore, AuditLogger auditLogger,
            AuthorizationContext authorization, WirelessCalibrationStore wirelessCalibrationStore) {
        this(credentials, socketFactory, alertStore, permissionStore, auditLogger, authorization,
                wirelessCalibrationStore, ServerClientDistanceStore.inMemory());
    }

    public LanServerService(CredentialRegistry credentials, ServerSocketFactory socketFactory,
            AlertStore alertStore, ClientPermissionStore permissionStore, AuditLogger auditLogger,
            AuthorizationContext authorization, WirelessCalibrationStore wirelessCalibrationStore,
            ServerClientDistanceStore distanceStore) {
        this(credentials, socketFactory, alertStore, permissionStore, auditLogger, authorization,
                wirelessCalibrationStore, distanceStore, RadioMapStore.inMemory());
    }

    public LanServerService(CredentialRegistry credentials, ServerSocketFactory socketFactory,
            AlertStore alertStore, ClientPermissionStore permissionStore, AuditLogger auditLogger,
            AuthorizationContext authorization, WirelessCalibrationStore wirelessCalibrationStore,
            ServerClientDistanceStore distanceStore, RadioMapStore radioMapStore) {
        this.credentials = credentials;
        this.socketFactory = socketFactory;
        this.alertStore = alertStore;
        this.permissionStore = permissionStore;
        this.auditLogger = auditLogger;
        this.authorization = authorization;
        this.wirelessCalibrationStore = wirelessCalibrationStore == null
                ? WirelessCalibrationStore.inMemory() : wirelessCalibrationStore;
        this.distanceService = new ServerClientDistanceService(distanceStore == null
                ? ServerClientDistanceStore.inMemory() : distanceStore);
        this.radioMapStore = radioMapStore == null ? RadioMapStore.inMemory() : radioMapStore;
        AlertStoreSnapshot loaded;
        String loadError = null;
        try { loaded = alertStore.load(); }
        catch (IOException exception) {
            loaded = AlertStoreSnapshot.empty();
            loadError = exception.getMessage();
        }
        alertEngine = new AlertEngine(loaded.rules(), loaded.alerts());
        alertStoreLoadError = loadError;
        alertPersistenceError = loadError;
        alertPersistenceAvailable.set(loadError == null);
    }

    private static ClientPermissionStore defaultPermissionStore() {
        try { return ClientPermissionStore.defaultStore(); }
        catch (IOException exception) {
            throw new IllegalStateException("Cannot load Client permission store", exception);
        }
    }

    private static WirelessCalibrationStore defaultWirelessCalibrationStore() {
        try { return WirelessCalibrationStore.defaultStore(); }
        catch (IOException exception) {
            System.err.println("Wireless calibration persistence unavailable; using memory only: "
                    + exception.getMessage());
            return WirelessCalibrationStore.inMemory();
        }
    }

    private static ServerClientDistanceStore defaultDistanceStore() {
        try { return ServerClientDistanceStore.defaultStore(); }
        catch (IOException exception) {
            System.err.println("Server-Client distance persistence unavailable; using memory only: "
                    + exception.getMessage());
            return ServerClientDistanceStore.inMemory();
        }
    }

    private static RadioMapStore defaultRadioMapStore() {
        try { return RadioMapStore.defaultStore(); }
        catch (IOException exception) {
            System.err.println("Wi-Fi Radio Map persistence unavailable; using memory only: "
                    + exception.getMessage());
            return RadioMapStore.inMemory();
        }
    }

    public void setListener(ServerEventListener listener) {
        this.listener = listener == null ? new ServerEventListener() {} : listener;
        this.listener.onAlertsChanged(alerts());
    }

    public synchronized int start(int port) throws IOException {
        if (serviceClosed.get()) throw new IllegalStateException("Server service is closed");
        if (running) throw new IllegalStateException("Server is already running");
        serverSocket = socketFactory.createServerSocket(port);
        running = true;
        acceptExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("lan-server-accept", true));
        networkExecutor = new ThreadPoolExecutor(4, 32, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory("lan-server-io", true));
        scheduler = Executors.newScheduledThreadPool(1, new NamedThreadFactory("lan-server-monitor", true));
        acceptExecutor.execute(this::acceptLoop);
        scheduler.scheduleAtFixedRate(this::monitorClients, 3, 3, TimeUnit.SECONDS);
        listener.onLog("Server listening on port " + serverSocket.getLocalPort()
                + " using protocol v" + ProtocolMessage.CURRENT_VERSION);
        listener.onLog(serverSocket instanceof javax.net.ssl.SSLServerSocket
                ? "TLS 1.3 transport enabled; file transfer available subject to RBAC."
                : "Plain TCP monitoring mode; file transfer is disabled until TLS is configured.");
        if (alertStoreLoadError != null) {
            listener.onLog("Alert store load failed; persistence disabled to preserve the existing file: "
                    + alertStoreLoadError);
        }
        return serverSocket.getLocalPort();
    }

    public synchronized void stop() {
        if (!running && serverSocket == null) return;
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        for (Session session : sessions.keySet()) session.close();
        sessions.clear();
        if (scheduler != null) scheduler.shutdownNow();
        if (acceptExecutor != null) acceptExecutor.shutdownNow();
        if (networkExecutor != null) networkExecutor.shutdownNow();
        for (ClientRecord client : clients.values()) {
            client.status = reconnectBlockedClients.contains(client.clientId)
                    ? ClientStatus.DISCONNECTED_BY_SERVER : ClientStatus.OFFLINE;
            client.session = null;
            markFingerprintStale(client, "Historical estimate; Server stopped");
        }
        publishClients();
        listener.onLog("Server stopped safely.");
    }

    public boolean isRunning() { return running; }

    public int localPort() {
        ServerSocket current = serverSocket;
        return current == null ? -1 : current.getLocalPort();
    }

    public List<ClientSnapshot> clients() {
        List<ClientSnapshot> result = new ArrayList<>();
        for (ClientRecord client : clients.values()) result.add(client.snapshot());
        result.sort(Comparator.comparing(ClientSnapshot::hostname, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ClientSnapshot::clientId));
        return List.copyOf(result);
    }

    public List<TelemetrySample> telemetryHistory(String clientId) {
        ClientRecord client = clients.get(clientId);
        return client == null ? List.of() : client.telemetry.snapshot();
    }

    public List<WirelessObservation> wirelessHistory(String clientId) {
        ClientRecord client = clients.get(clientId);
        return client == null ? List.of() : client.wireless.history();
    }

    public WirelessCalibration calibrateWireless(String clientId,
            List<WirelessCalibrationPoint> points) {
        ClientRecord client = clients.get(clientId);
        if (client == null) throw new IllegalArgumentException("Unknown Client ID");
        WirelessCalibration result = client.wireless.calibrate(points);
        listener.onLog("Saved in-memory Wi-Fi calibration for " + client.hostname
                + " (" + result.pointCount() + " points, RMSE "
                + String.format(java.util.Locale.ROOT, "%.2f", result.rmseDb()) + " dB)");
        return result;
    }

    public NetworkLatencySample networkLatency(String clientId) {
        ClientRecord client = clients.get(clientId);
        if (client == null || client.responseTimeMillis == null) return null;
        return new NetworkLatencySample(client.lastSeen, lanmonitoringserver.model.MeasurementStatus.OK,
                client.responseTimeMillis,
                "Authenticated application PING/PONG; not a physical distance measurement");
    }

    public ServerClientDistance serverClientDistance(String clientId) {
        return distanceService.distanceFor(clientId);
    }

    public List<FingerprintSample> radioMapSamples() { return radioMapStore.samples(); }
    public Path radioMapPath() { return radioMapStore.path(); }
    public WifiScanSnapshot latestWifiScan(String clientId) {
        ClientRecord client = clients.get(clientId);
        return client == null ? null : client.latestWifiScan;
    }
    public EstimatedClientPosition estimatedWifiPosition(String clientId) {
        ClientRecord client = clients.get(clientId);
        return client == null ? null : client.fingerprintPosition;
    }

    public FingerprintSample addRadioMapSample(String clientId, SpatialPosition position)
            throws IOException {
        requireDistanceManagement("WIFI_RADIO_MAP_SAMPLE_ADD", clientId);
        ClientRecord client = clients.get(clientId);
        if (client == null || client.latestWifiScan == null) {
            throw new IllegalStateException("No multi-AP scan is available for this Client");
        }
        WifiScanSnapshot scan = client.latestWifiScan;
        if (scan.status() != lanmonitoringserver.model.MeasurementStatus.OK
                || scan.isStale(System.currentTimeMillis()) || scan.accessPoints().size() < 3) {
            throw new IllegalStateException("A fresh scan with at least three real RSSI APs is required");
        }
        LinkedHashMap<String, Double> signals = new LinkedHashMap<>();
        for (var signal : scan.accessPoints()) signals.put(signal.targetId(), signal.rssiDbm());
        SpatialPosition surveyed = new SpatialPosition(position.referencePoint(), position.xMeters(),
                position.yMeters(), position.zMeters(), scan.timestamp());
        FingerprintSample sample = new FingerprintSample(UUID.randomUUID().toString(), clientId, surveyed,
                signals, scan.timestamp());
        radioMapStore.add(sample);
        audit("WIFI_RADIO_MAP_SAMPLE_ADD", clientId, "SUCCESS",
                position.referencePoint() + "; APs=" + signals.size());
        recalculateFingerprintPositions();
        publishClients();
        return sample;
    }

    public void removeRadioMapSample(String sampleId) throws IOException {
        requireDistanceManagement("WIFI_RADIO_MAP_SAMPLE_REMOVE", sampleId);
        radioMapStore.remove(sampleId);
        audit("WIFI_RADIO_MAP_SAMPLE_REMOVE", sampleId, "SUCCESS", "removed");
        recalculateFingerprintPositions();
        publishClients();
    }

    private void recalculateFingerprintPositions() {
        List<FingerprintSample> samples = radioMapStore.samples();
        for (ClientRecord record : clients.values()) {
            EstimatedClientPosition estimate = fingerprintEstimator.estimate(record.clientId,
                    record.latestWifiScan, samples);
            record.fingerprintPosition = estimate;
            distanceService.updateEstimatedPosition(record.clientId, estimate);
        }
    }

    public void setServerPosition(SpatialPosition position) throws IOException {
        requireDistanceManagement("DISTANCE_SERVER_POSITION_UPDATE", "server");
        distanceService.setServerPosition(position);
        audit("DISTANCE_SERVER_POSITION_UPDATE", "server", "SUCCESS", position.referencePoint());
        publishClients();
    }

    public void clearServerPosition() throws IOException {
        requireDistanceManagement("DISTANCE_SERVER_POSITION_CLEAR", "server");
        distanceService.clearServerPosition();
        audit("DISTANCE_SERVER_POSITION_CLEAR", "server", "SUCCESS", "cleared");
        publishClients();
    }

    public void setClientPosition(String clientId, SpatialPosition position) throws IOException {
        requireDistanceManagement("DISTANCE_CLIENT_POSITION_UPDATE", clientId);
        distanceService.setClientPosition(clientId, position);
        audit("DISTANCE_CLIENT_POSITION_UPDATE", clientId, "SUCCESS", position.referencePoint());
        publishClients();
    }

    public void setManualClientDistance(String clientId, double meters, Double errorMeters,
            long measuredAt) throws IOException {
        requireDistanceManagement("DISTANCE_MANUAL_UPDATE", clientId);
        distanceService.setManualMeasurement(clientId, meters, errorMeters, measuredAt);
        audit("DISTANCE_MANUAL_UPDATE", clientId, "SUCCESS", "manual measurement updated");
        publishClients();
    }

    public void clearClientDistance(String clientId) throws IOException {
        requireDistanceManagement("DISTANCE_CLIENT_CLEAR", clientId);
        distanceService.clearClient(clientId);
        audit("DISTANCE_CLIENT_CLEAR", clientId, "SUCCESS", "position and manual measurement cleared");
        publishClients();
    }

    private void requireDistanceManagement(String action, String target) {
        if (authorization.role() == lanmonitoringserver.model.UserRole.ADMIN) return;
        audit(action, target, "DENIED", "Only ADMIN can manage physical location data");
        throw new SecurityException("Only ADMIN can manage Server-Client distance data");
    }

    public List<AlertRecord> alerts() {
        List<AlertRecord> result = new ArrayList<>(alertEngine.alerts());
        result.sort(Comparator.comparingLong(AlertRecord::createdAt).reversed());
        return List.copyOf(result);
    }

    public List<AlertRule> alertRules() { return alertEngine.rules(); }

    public AuthorizationContext authorization() { return authorization; }

    public ClientPermissions clientPermissions(String clientId) { return permissionStore.get(clientId); }

    public void setClientPermissions(String clientId, ClientPermissions permissions) throws IOException {
        if (!authorization.role().canManagePermissions()) {
            audit("PERMISSIONS_UPDATE", clientId, "DENIED", "Role cannot manage permissions");
            throw new SecurityException("Only ADMIN can manage Client permissions");
        }
        ClientRecord client = clients.get(clientId);
        if (client != null && client.session != null && client.status == ClientStatus.ONLINE
                && !client.session.secure()) {
            audit("PERMISSIONS_UPDATE", clientId, "DENIED", "TLS required");
            throw new SecurityException("Permission changes for an online Client require TLS");
        }
        permissionStore.put(clientId, permissions);
        if (client != null && client.session != null && client.status == ClientStatus.ONLINE
                && client.session.secure()) {
            client.session.send(permissionMessage(clientId, permissions));
        }
        audit("PERMISSIONS_UPDATE", clientId, "SUCCESS", permissions.toString());
        publishClients();
    }

    public List<FileTransferProgress> fileTransfers() {
        return fileTransfers.values().stream()
                .sorted(Comparator.comparingLong(FileTransferProgress::startedAt).reversed()).toList();
    }

    /** Validates once, then streams independently to each authorized Client on a bounded pool. */
    public List<String> sendFile(Path source, Collection<String> requestedClientIds) throws IOException {
        if (!authorization.role().canSendFiles()) {
            audit("FILE_SEND", "", "DENIED", "Role cannot send files");
            throw new SecurityException("Current role cannot send files");
        }
        Path file = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new IOException("File is not readable");
        String fileName = file.getFileName().toString();
        if (!FileReceiver.safeFileName(fileName)) throw new IOException("Invalid file name");
        long size = Files.size(file);
        long maximum = Long.getLong("lanmonitoring.file.max.bytes", FileReceiver.DEFAULT_MAX_FILE_BYTES);
        if (size > maximum) throw new IOException("File exceeds configured maximum size");
        if (requestedClientIds == null || requestedClientIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one Client");
        }
        String checksum = FileReceiver.sha256(file);
        String fileId = UUID.randomUUID().toString();
        List<String> transferIds = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String clientId : Set.copyOf(requestedClientIds)) {
            ClientRecord client = clients.get(clientId);
            String reason = transferEligibility(clientId, client);
            if (reason != null) {
                rejected.add(clientId + ": " + reason);
                audit("FILE_SEND", clientId, "DENIED", reason);
                continue;
            }
            String transferId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            FileTransferProgress progress = new FileTransferProgress(transferId, fileId, clientId,
                    client.hostname, fileName, size, checksum, 0, 0, FileTransferStatus.QUEUED,
                    "Queued", null, now, now);
            fileTransfers.put(transferId, progress);
            TransferControl control = new TransferControl(clientId);
            transferControls.put(transferId, control);
            transferIds.add(transferId);
            fileTransferExecutor.execute(() -> transferFile(file, progress, control));
        }
        notifyTransfers();
        if (transferIds.isEmpty()) throw new IOException("No eligible Client: " + String.join("; ", rejected));
        if (!rejected.isEmpty()) listener.onLog("File transfer skipped: " + String.join("; ", rejected));
        return List.copyOf(transferIds);
    }

    public boolean cancelFileTransfer(String transferId) {
        TransferControl control = transferControls.get(transferId);
        FileTransferProgress current = fileTransfers.get(transferId);
        if (control == null || current == null || current.terminal()) return false;
        if (!authorization.role().canSendFiles()
                || authorization.role() != lanmonitoringserver.model.UserRole.ADMIN
                && !authorization.canAccessClient(current.clientId())) {
            throw new SecurityException("Current role cannot cancel this transfer");
        }
        control.cancelled.set(true);
        ClientRecord client = clients.get(current.clientId());
        if (client != null && client.session != null) {
            try { client.session.send(ProtocolMessage.create(MessageType.FILE_CANCEL, current.clientId(),
                    Map.of("transferId", transferId))); } catch (IOException ignored) {}
        }
        updateTransfer(current, current.bytesTransferred(), FileTransferStatus.CANCELLED,
                "Cancelled by " + authorization.principal(), null);
        audit("FILE_CANCEL", current.clientId(), "SUCCESS", transferId);
        return true;
    }

    private String transferEligibility(String clientId, ClientRecord client) {
        if (!authorization.canAccessClient(clientId)) return "Client outside role scope";
        if (!permissionStore.get(clientId).receiveFiles()) return "Receive-file permission disabled";
        if (client == null || client.status != ClientStatus.ONLINE || client.session == null) return "Client offline";
        if (client.session.legacy) return "Legacy Client unsupported";
        if (!client.session.secure()) return "TLS required";
        return null;
    }

    private void transferFile(Path file, FileTransferProgress initial, TransferControl control) {
        FileTransferProgress current = initial;
        try {
            ClientRecord client = requireTransferClient(initial.clientId(), control);
            ProtocolMessage offer = ProtocolMessage.create(MessageType.FILE_OFFER, initial.clientId(),
                    Map.of("transferId", initial.transferId(), "fileId", initial.fileId(),
                            "fileName", initial.fileName(), "fileSize", initial.fileSize(),
                            "sha256", initial.sha256()));
            client.session.send(offer);
            current = updateTransfer(current, 0, FileTransferStatus.OFFERED,
                    "Waiting for Client acceptance", null);
            String acceptance = control.acceptance.get(fileOfferTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!"ACCEPTED".equals(acceptance)) throw new IOException(acceptance);

            byte[] buffer = new byte[FileReceiver.MAX_CHUNK_BYTES];
            long sent = 0;
            try (var input = Files.newInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    client = requireTransferClient(initial.clientId(), control);
                    String encoded = Base64.getEncoder().encodeToString(
                            count == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, count));
                    client.session.send(ProtocolMessage.create(MessageType.FILE_CHUNK, initial.clientId(),
                            Map.of("transferId", initial.transferId(), "offset", sent, "data", encoded)));
                    sent += count;
                    current = updateTransfer(current, sent, FileTransferStatus.IN_PROGRESS,
                            "Sending", null);
                }
            }
            client = requireTransferClient(initial.clientId(), control);
            client.session.send(ProtocolMessage.create(MessageType.FILE_COMPLETE, initial.clientId(),
                    Map.of("transferId", initial.transferId())));
            current = updateTransfer(current, sent, FileTransferStatus.VERIFYING,
                    "Waiting for Client checksum", null);
            TransferResult result = control.result.get(fileResultTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (control.cancelled.get()) throw new IOException("Transfer cancelled");
            if (result.status == FileTransferStatus.SUCCESS) {
                current = updateTransfer(current, sent, FileTransferStatus.SUCCESS,
                        "Checksum verified", result.savedPath);
                audit("FILE_SEND", initial.clientId(), "SUCCESS", initial.fileName());
            } else if (result.status == FileTransferStatus.CANCELLED) {
                updateTransfer(current, sent, FileTransferStatus.CANCELLED, result.detail, null);
            } else {
                throw new IOException(result.detail);
            }
        } catch (TimeoutException exception) {
            updateTransfer(current, current.bytesTransferred(), FileTransferStatus.FAILED,
                    "Transfer timeout", null);
            audit("FILE_SEND", initial.clientId(), "FAILED", "Timeout");
        } catch (IOException | InterruptedException | ExecutionException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            FileTransferStatus status = control.cancelled.get()
                    ? FileTransferStatus.CANCELLED : FileTransferStatus.FAILED;
            updateTransfer(current, current.bytesTransferred(), status, safeMessage(exception), null);
            audit("FILE_SEND", initial.clientId(), status.name(), safeMessage(exception));
        } finally {
            transferControls.remove(initial.transferId());
        }
    }

    private ClientRecord requireTransferClient(String clientId, TransferControl control) throws IOException {
        if (control.cancelled.get()) throw new IOException("Transfer cancelled");
        ClientRecord client = clients.get(clientId);
        if (client == null || client.status != ClientStatus.ONLINE || client.session == null
                || !client.session.secure()) throw new IOException("Client disconnected or TLS unavailable");
        return client;
    }

    private static long fileOfferTimeoutMillis() {
        return Math.max(100L, Long.getLong("lanmonitoring.file.offer.timeout.ms", 15_000L));
    }

    private static long fileResultTimeoutMillis() {
        return Math.max(100L, Long.getLong("lanmonitoring.file.result.timeout.ms", 30_000L));
    }

    private FileTransferProgress updateTransfer(FileTransferProgress old, long bytes,
            FileTransferStatus status, String detail, String savedPath) {
        long now = System.currentTimeMillis();
        double speed = bytes <= 0 ? 0 : bytes * 1000d / Math.max(1, now - old.startedAt());
        FileTransferProgress updated = new FileTransferProgress(old.transferId(), old.fileId(),
                old.clientId(), old.hostname(), old.fileName(), old.fileSize(), old.sha256(),
                Math.min(bytes, old.fileSize()), speed, status, detail, savedPath,
                old.startedAt(), now);
        fileTransfers.put(updated.transferId(), updated);
        boolean immediate = status != FileTransferStatus.IN_PROGRESS
                || now - transferNotificationTimes.getOrDefault(updated.transferId(), 0L) >= 200;
        if (immediate) {
            if (status == FileTransferStatus.IN_PROGRESS) {
                transferNotificationTimes.put(updated.transferId(), now);
            } else if (updated.terminal()) {
                transferNotificationTimes.remove(updated.transferId());
                pruneTransferHistory();
            }
            notifyTransfers();
        }
        return updated;
    }

    private void pruneTransferHistory() {
        int limit = Math.max(10, Math.min(100_000,
                Integer.getInteger("lanmonitoring.transfer.history.max", DEFAULT_TRANSFER_HISTORY_LIMIT)));
        int excess = fileTransfers.size() - limit;
        if (excess <= 0) return;
        fileTransfers.values().stream().filter(FileTransferProgress::terminal)
                .sorted(Comparator.comparingLong(FileTransferProgress::updatedAt))
                .limit(excess).map(FileTransferProgress::transferId).forEach(fileTransfers::remove);
    }

    private void notifyTransfers() { listener.onFileTransfersChanged(fileTransfers()); }

    private ProtocolMessage permissionMessage(String clientId, ClientPermissions permissions) {
        return ProtocolMessage.create(MessageType.PERMISSIONS_UPDATE, clientId, Map.of(
                "receiveFiles", permissions.receiveFiles(),
                "receiveAlerts", permissions.receiveAlerts(),
                "exportSystemInformation", permissions.exportSystemInformation()));
    }

    private void audit(String action, String target, String outcome, String detail) {
        try { auditLogger.record(authorization.principal(), authorization.role().name(),
                action, target, outcome, detail); }
        catch (IOException exception) { listener.onLog("Audit log failed: " + exception.getMessage()); }
    }

    private static String safeMessage(Throwable exception) {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause() : exception;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    /** False after a load/save error; alert evaluation continues in memory without overwriting data. */
    public boolean isAlertPersistenceAvailable() { return alertPersistenceAvailable.get(); }

    public String alertPersistenceError() { return alertPersistenceError; }

    public void updateAlertRule(AlertRule rule) {
        requireAlertManagement("ALERT_RULE_UPDATE", rule == null ? "" : rule.metric().name());
        alertEngine.updateRule(rule);
        scheduleAlertPersistence();
        listener.onAlertsChanged(alerts());
        audit("ALERT_RULE_UPDATE", rule.metric().name(), "SUCCESS", rule.toString());
    }

    public boolean acknowledgeAlert(String alertId) {
        requireAlertManagement("ALERT_ACKNOWLEDGE", alertId);
        AlertRecord updated = alertEngine.acknowledge(alertId, System.currentTimeMillis());
        if (updated == null) {
            audit("ALERT_ACKNOWLEDGE", alertId, "NOT_FOUND", "Alert not found");
            return false;
        }
        alertStateChanged();
        audit("ALERT_ACKNOWLEDGE", alertId, "SUCCESS", updated.status().name());
        return true;
    }

    public boolean resolveAlert(String alertId) {
        requireAlertManagement("ALERT_RESOLVE", alertId);
        AlertRecord updated = alertEngine.resolve(alertId, System.currentTimeMillis());
        if (updated == null) {
            audit("ALERT_RESOLVE", alertId, "NOT_FOUND", "Alert not found");
            return false;
        }
        alertStateChanged();
        audit("ALERT_RESOLVE", alertId, "SUCCESS", updated.status().name());
        return true;
    }

    /** Creates fixed-template notifications and delivers each one through ALERT/ALERT_ACK. */
    public NotificationSendResult sendAdminNotification(NotificationTemplate template,
            Collection<String> requestedClientIds) {
        requireAlertManagement("ADMIN_NOTIFICATION_SEND", template == null ? "" : template.name());
        if (template == null) throw new IllegalArgumentException("Select a notification template");
        if (requestedClientIds == null || requestedClientIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one online Client");
        }
        long now = System.currentTimeMillis();
        List<AlertRecord> created = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String clientId : Set.copyOf(requestedClientIds)) {
            ClientRecord client = clients.get(clientId);
            String reason = notificationEligibility(clientId, client);
            String dedupKey = template.name() + ":" + clientId;
            Long previous = manualNotificationDedupTimes.get(dedupKey);
            if (reason == null && previous != null && now - previous < 5_000L) {
                reason = "Duplicate click suppressed (5 seconds)";
            }
            if (reason != null) {
                rejected.put(clientId, reason);
                audit("ADMIN_NOTIFICATION_SEND", clientId, "DENIED", reason);
                continue;
            }
            manualNotificationDedupTimes.put(dedupKey, now);
            MetricReading reading = metricForTemplate(client, template.metric(), now);
            AlertRecord alert = alertEngine.createManualNotification(clientId, client.hostname,
                    template, reading, now);
            created.add(alert);
            audit("ADMIN_NOTIFICATION_SEND", clientId, "SUCCESS", template.name());
        }
        if (!created.isEmpty()) alertStateChanged();
        for (AlertRecord alert : created) {
            ClientRecord client = clients.get(alert.clientId());
            if (client != null && client.session != null) client.session.deliverAlert(client, alert);
        }
        return new NotificationSendResult(List.copyOf(created), Map.copyOf(rejected));
    }

    private String notificationEligibility(String clientId, ClientRecord client) {
        if (!authorization.canAccessClient(clientId)) return "Client outside role scope";
        if (!permissionStore.get(clientId).receiveAlerts()) return "Admin alerts=false";
        if (client == null || client.status != ClientStatus.ONLINE || client.session == null) return "Client offline";
        if (client.session.legacy) return "Legacy Client unsupported";
        return null;
    }

    private static MetricReading metricForTemplate(ClientRecord client, AlertMetric metric, long now) {
        ClientMetrics metrics = client.metrics.snapshot(now);
        return switch (metric) {
            case CPU -> metrics.cpuUsage();
            case RAM -> metrics.ramUsage();
            case GPU -> metrics.gpuUsage();
            case DISK_ACTIVE -> metrics.diskActive();
            case DISK_CAPACITY -> metrics.diskCapacity();
            case SYSTEM -> null;
        };
    }

    public record NotificationSendResult(List<AlertRecord> created, Map<String, String> rejected) {}

    private void requireAlertManagement(String action, String target) {
        if (authorization.role().canManageAlerts()) return;
        audit(action, target, "DENIED", "Only ADMIN can manage alerts");
        throw new SecurityException("Only ADMIN can acknowledge, resolve, or configure alerts");
    }

    private void alertStateChanged() {
        scheduleAlertPersistence();
        listener.onAlertsChanged(alerts());
        publishClients();
    }

    private void scheduleAlertPersistence() {
        if (!alertPersistenceAvailable.get()) return;
        alertPersistenceDirty.set(true);
        if (!alertPersistenceScheduled.compareAndSet(false, true)) return;
        alertPersistenceExecutor.execute(() -> {
            try {
                do {
                    alertPersistenceDirty.set(false);
                    try {
                        alertStore.save(new AlertStoreSnapshot(alertEngine.rules(), alertEngine.alerts()));
                    } catch (IOException exception) {
                        alertPersistenceError = exception.getMessage();
                        alertPersistenceAvailable.set(false);
                        alertPersistenceDirty.set(false);
                        listener.onLog("Alert persistence failed and was disabled for this run: "
                                + exception.getMessage());
                    }
                } while (alertPersistenceAvailable.get() && alertPersistenceDirty.get());
            } finally {
                alertPersistenceScheduled.set(false);
                if (alertPersistenceDirty.get()) scheduleAlertPersistence();
            }
        });
    }

    public String sendMessage(String clientId, String content) throws IOException {
        if (!authorization.canAccessClient(clientId)) throw new SecurityException("Client outside role scope");
        if (!permissionStore.get(clientId).receiveAlerts()) {
            throw new SecurityException("Administrative notifications are disabled for this Client");
        }
        String validated = InputValidator.message(content);
        ClientRecord client = clients.get(clientId);
        if (client == null || client.status != ClientStatus.ONLINE || client.session == null) {
            throw new IOException("Client is not online: " + clientId);
        }
        ProtocolMessage message = ProtocolMessage.create(MessageType.MESSAGE, clientId,
                Map.of("content", validated));
        client.session.send(message);
        listener.onLog("Sent MESSAGE to " + client.hostname + " (requestId=" + message.requestId() + ")");
        return message.requestId();
    }

    /**
     * Blocks the selected stable identities before closing their current sessions. A failure for
     * one identity is isolated from the rest of the batch.
     */
    public ClientControlResult disconnectClients(Collection<String> requestedClientIds) {
        LinkedHashSet<String> ids = normalizedClientIds(requestedClientIds);
        List<String> failures = new ArrayList<>();
        int completed = 0;
        for (String clientId : ids) {
            ClientRecord record = clients.get(clientId);
            if (record == null) {
                failures.add(clientId + ": unknown Client ID");
                continue;
            }
            if (!authorization.canAccessClient(clientId)) {
                failures.add(clientId + ": outside role scope");
                audit("CLIENT_DISCONNECT", clientId, "DENIED", "Client outside role scope");
                continue;
            }
            Session session;
            synchronized (clients) {
                reconnectBlockedClients.add(clientId);
                record.status = ClientStatus.DISCONNECTED_BY_SERVER;
                record.responseTimeMillis = null;
                session = record.session;
                markFingerprintStale(record, "Historical estimate; disconnected by Server");
            }
            try {
                if (session != null && !session.closed) {
                    if (!session.legacy) {
                        session.send(ProtocolMessage.create(MessageType.SERVER_DISCONNECT,
                                clientId, Map.of("reason", "DISCONNECTED_BY_ADMIN")));
                    }
                    session.close();
                }
                completed++;
                listener.onLog("Server disconnected and blocked Client: " + record.hostname
                        + " [" + clientId + "]");
                audit("CLIENT_DISCONNECT", clientId, "SUCCESS",
                        session == null ? "Blocked while offline" : "Session closed and reconnect blocked");
            } catch (RuntimeException | IOException error) {
                if (session != null) session.close();
                // The block is already effective even if the best-effort notification failed.
                completed++;
                listener.onLog("Client blocked; disconnect notification failed for " + record.hostname
                        + " [" + clientId + "]: " + error.getMessage());
                audit("CLIENT_DISCONNECT", clientId, "SUCCESS",
                        "Blocked; notification failed: " + error.getMessage());
            }
        }
        publishClients();
        return new ClientControlResult(ids.size(), completed, List.copyOf(failures));
    }

    /** Removes only the selected identities from the reconnect block list. */
    public ClientControlResult allowReconnect(Collection<String> requestedClientIds) {
        LinkedHashSet<String> ids = normalizedClientIds(requestedClientIds);
        List<String> failures = new ArrayList<>();
        int completed = 0;
        for (String clientId : ids) {
            ClientRecord record = clients.get(clientId);
            if (record == null) {
                failures.add(clientId + ": unknown Client ID");
                continue;
            }
            if (!authorization.canAccessClient(clientId)) {
                failures.add(clientId + ": outside role scope");
                audit("CLIENT_RECONNECT_ALLOW", clientId, "DENIED", "Client outside role scope");
                continue;
            }
            if (!reconnectBlockedClients.remove(clientId)) {
                failures.add(record.hostname + " [" + clientId + "]: Client is not blocked");
                continue;
            }
            if (record.status != ClientStatus.ONLINE) {
                record.status = ClientStatus.WAITING_FOR_RECONNECT;
            }
            completed++;
            listener.onLog("Reconnect allowed; waiting for TCP authentication: " + record.hostname
                    + " [" + clientId + "]");
            audit("CLIENT_RECONNECT_ALLOW", clientId, "SUCCESS",
                    "Block removed; waiting for authenticated TCP session");
        }
        publishClients();
        return new ClientControlResult(ids.size(), completed, List.copyOf(failures));
    }

    public boolean isReconnectBlocked(String clientId) {
        return clientId != null && reconnectBlockedClients.contains(clientId);
    }

    private static LinkedHashSet<String> normalizedClientIds(Collection<String> requested) {
        if (requested == null) throw new IllegalArgumentException("Client IDs are required");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String clientId : requested) {
            if (clientId != null && !clientId.isBlank()) result.add(clientId.trim());
        }
        return result;
    }

    public record ClientControlResult(int requested, int completed, List<String> failures) {}

    public String requestSystemInfo(String clientId) throws IOException {
        if (!authorization.canAccessClient(clientId)) throw new SecurityException("Client outside role scope");
        if (!permissionStore.get(clientId).exportSystemInformation()) {
            throw new SecurityException("System information export is disabled for this Client");
        }
        ClientRecord client = clients.get(clientId);
        if (client == null || client.status != ClientStatus.ONLINE || client.session == null
                || client.session.legacy) {
            throw new IOException("Client does not support SYSTEM_INFO refresh: " + clientId);
        }
        ProtocolMessage request = ProtocolMessage.create(MessageType.SYSTEM_INFO_REQUEST,
                clientId, Map.of("reason", "USER_REFRESH"));
        client.session.send(request);
        listener.onLog("Requested SYSTEM_INFO refresh from " + client.hostname);
        return request.requestId();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout((int) (CLIENT_TIMEOUT_MILLIS + 5_000));
                Session session = new Session(socket);
                sessions.put(session, Boolean.TRUE);
                try {
                    networkExecutor.execute(session);
                } catch (RejectedExecutionException overloaded) {
                    sessions.remove(session);
                    session.close();
                    listener.onLog("Connection rejected: server worker pool is full");
                }
            } catch (IOException exception) {
                if (running) listener.onLog("Accept error: " + exception.getMessage());
            }
        }
    }

    private void monitorClients() {
        if (!running) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (ClientRecord client : clients.values()) {
            Session session = client.session;
            if (client.status == ClientStatus.ONLINE && now - client.lastSeen > CLIENT_TIMEOUT_MILLIS) {
                client.status = ClientStatus.OFFLINE;
                client.session = null;
                markFingerprintStale(client, "Historical estimate; Client heartbeat timed out");
                if (session != null) session.close();
                listener.onLog("Heartbeat timeout: " + client.hostname);
                changed = true;
            } else if (client.status == ClientStatus.ONLINE && session != null && !session.legacy) {
                try {
                    if (prunePendingPings(session.pendingPings, now)) {
                        client.responseTimeMillis = null;
                        changed = true;
                    }
                    ProtocolMessage ping = ProtocolMessage.create(MessageType.PING, client.clientId,
                            Map.of("sentAt", now));
                    session.pendingPings.put(ping.requestId(), now);
                    session.send(ping);
                } catch (IOException exception) {
                    session.close();
                }
            }
        }
        if (changed) publishClients();
    }

    static boolean prunePendingPings(Map<String, Long> pending, long now) {
        boolean removed = pending.entrySet().removeIf(entry ->
                now - entry.getValue() > PENDING_PING_RETENTION_MILLIS);
        while (pending.size() >= MAX_PENDING_PINGS_PER_SESSION) {
            String oldest = pending.entrySet().stream()
                    .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (oldest == null) break;
            removed |= pending.remove(oldest) != null;
        }
        return removed;
    }

    private void publishClients() {
        listener.onClientsChanged(clients());
    }

    private void markFingerprintStale(ClientRecord client, String reason) {
        EstimatedClientPosition current = client.fingerprintPosition;
        if (current == null) return;
        EstimatedClientPosition stale = current.asStale(reason);
        if (stale == current) return;
        client.fingerprintPosition = stale;
        distanceService.updateEstimatedPosition(client.clientId, stale);
    }

    private boolean markOnline(Session session, String clientId, String hostname,
            String ipAddress, String operatingSystem) {
        ClientRecord record;
        Session previous = null;
        synchronized (clients) {
            if (reconnectBlockedClients.contains(clientId)) return false;
            record = clients.computeIfAbsent(clientId, ignored -> new ClientRecord(clientId));
            if (record.session != null && record.session != session) previous = record.session;
            record.metrics.beginSession();
            record.hostname = hostname;
            record.ipAddress = ipAddress;
            record.operatingSystem = operatingSystem;
            record.status = ClientStatus.ONLINE;
            record.lastSeen = System.currentTimeMillis();
            record.session = session;
            session.client = record;
            session.authenticated = true;
        }
        if (previous != null) previous.close();
        listener.onLog("Client online: " + hostname + " [" + clientId + "]");
        publishClients();
        return true;
    }

    private final class Session implements Runnable {
        private final Socket socket;
        private final ConcurrentHashMap<String, Long> pendingPings = new ConcurrentHashMap<>();
        private FramedConnection framed;
        private PrintWriter legacyOutput;
        private volatile boolean legacy;
        private volatile boolean authenticated;
        private volatile boolean closed;
        private String pendingClientId;
        private String clientNonce;
        private String serverNonce;
        private String pendingHostname;
        private String pendingIp;
        private String pendingOperatingSystem;
        private String pendingClientRelease = "not-reported";
        private ClientRecord client;
        private long wirelessSamplesReceived;

        private Session(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try {
                PushbackInputStream input = new PushbackInputStream(
                        new BufferedInputStream(socket.getInputStream()), 1);
                int first = input.read();
                if (first < 0) return;
                input.unread(first);
                legacy = first != 0;
                if (legacy) handleLegacy(input);
                else {
                    listener.onLog("Protocol detected: modern framed JSON v1 from "
                            + socket.getRemoteSocketAddress());
                    handleFramed(input);
                }
            } catch (IOException | RuntimeException exception) {
                if (running && !closed) listener.onLog("Client connection error from "
                        + socket.getRemoteSocketAddress() + ": " + exception.getMessage());
            } finally {
                disconnect();
            }
        }

        private void handleFramed(PushbackInputStream input) throws IOException {
            framed = new FramedConnection(socket, input, socket.getOutputStream());
            ProtocolMessage message;
            while (!closed && (message = framed.read()) != null) handleMessage(message);
        }

        private void handleMessage(ProtocolMessage message) throws IOException {
            InputValidator.protocolClientId(message.clientId());
            switch (message.type()) {
                case REGISTER -> handleRegister(message);
                case CONNECT -> handleConnect(message);
                case AUTH -> handleAuthentication(message);
                case HEARTBEAT -> handleHeartbeat(message);
                case TELEMETRY -> handleTelemetry(message);
                case WIRELESS_SIGNAL -> handleWirelessSignal(message);
                case WIFI_SCAN -> handleWifiScan(message);
                case SYSTEM_INFO -> handleSystemInfo(message);
                case PONG -> handlePong(message);
                case ACK -> handleAck(message);
                case ALERT_ACK -> handleAlertAck(message);
                case FILE_ACCEPT -> handleFileAcceptance(message, true);
                case FILE_REJECT -> handleFileAcceptance(message, false);
                case FILE_RESULT -> handleFileResult(message);
                default -> sendError(message, "UNEXPECTED_TYPE", "Message type is not valid in this direction");
            }
        }

        private void handleRegister(ProtocolMessage message) throws IOException {
            if (authenticated) throw new ProtocolException("Cannot register an authenticated session");
            String secret = message.requireString("secret", 128);
            try {
                java.util.Base64.getDecoder().decode(secret);
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException("Invalid registration secret", exception);
            }
            CredentialRegistry.RegistrationResult result = credentials.register(message.clientId(), secret);
            if (result == CredentialRegistry.RegistrationResult.REJECTED) {
                sendError(message, "IDENTITY_CONFLICT", "Client ID is registered with another credential");
                close();
                return;
            }
            send(ProtocolMessage.reply(MessageType.REGISTERED, message, message.clientId(),
                    Map.of("created", result == CredentialRegistry.RegistrationResult.CREATED)));
            listener.onLog("Client registration accepted: " + message.clientId());
        }

        private void handleConnect(ProtocolMessage message) throws IOException {
            if (authenticated) throw new ProtocolException("Session is already authenticated");
            if (!credentials.isRegistered(message.clientId())) {
                sendError(message, "NOT_REGISTERED", "Client must register before authentication");
                return;
            }
            if (reconnectBlockedClients.contains(message.clientId())) {
                sendError(message, "CLIENT_BLOCKED",
                        "Server administrator has not allowed this Client to reconnect");
                listener.onLog("Rejected blocked reconnect attempt: " + message.clientId());
                close();
                return;
            }
            pendingClientId = message.clientId();
            pendingHostname = message.requireString("hostname", InputValidator.MAX_HOSTNAME_LENGTH);
            pendingIp = message.requireString("ipAddress", InputValidator.MAX_IP_LENGTH);
            pendingOperatingSystem = message.optionalString("operatingSystem", 255, "Unknown");
            String reportedRelease = message.optionalString("clientRelease", 64, "not-reported");
            pendingClientRelease = reportedRelease.matches("[A-Za-z0-9._-]{1,64}")
                    ? reportedRelease : "invalid-release-marker";
            clientNonce = message.requireString("clientNonce", 128);
            serverNonce = Authentication.newNonce();
            send(ProtocolMessage.reply(MessageType.CHALLENGE, message, pendingClientId,
                    Map.of("serverNonce", serverNonce)));
        }

        private void handleAuthentication(ProtocolMessage message) throws IOException {
            if (pendingClientId == null || !pendingClientId.equals(message.clientId())) {
                throw new ProtocolException("AUTH does not match pending identity");
            }
            String proof = message.requireString("proof", 256);
            if (!credentials.verify(pendingClientId, clientNonce, serverNonce, proof)) {
                sendError(message, "AUTH_FAILED", "Authentication proof is invalid");
                close();
                return;
            }
            if (!markOnline(this, pendingClientId, pendingHostname, pendingIp, pendingOperatingSystem)) {
                sendError(message, "CLIENT_BLOCKED",
                        "Server administrator has not allowed this Client to reconnect");
                close();
                return;
            }
            List<String> negotiatedCapabilities = new ArrayList<>(List.of("SYSTEM_INFO_V1",
                    "TELEMETRY_V1", "ALERT_V1", "FILE_TRANSFER_V1",
                    "TLS_REQUIRED_FOR_FILES", "WIRELESS_SIGNAL_V1"));
            if (fingerprintCapabilityAllowed(secure())) {
                negotiatedCapabilities.add("WIFI_FINGERPRINT_V1");
            }
            boolean fingerprintAllowed = negotiatedCapabilities.contains("WIFI_FINGERPRINT_V1");
            String fingerprintReason = fingerprintAllowed
                    ? "WIFI_FINGERPRINT_V1 advertised on authenticated "
                            + (secure() ? "TLS" : "plain TCP lab override")
                    : "WIFI_FINGERPRINT_V1 withheld: plain TCP session and "
                            + "lanmonitoring.fingerprint.requireTls=true";
            send(ProtocolMessage.reply(MessageType.AUTH_OK, message, pendingClientId,
                    Map.of("sessionId", UUID.randomUUID().toString(),
                            "capabilities", negotiatedCapabilities,
                            "wifiFingerprintReason", fingerprintReason)));
            listener.onLog("Authenticated modern client " + pendingHostname + " [" + pendingClientId
                    + "]; client release=" + pendingClientRelease
                    + "; transport=" + (secure() ? "TLS" : "plain TCP")
                    + "; WIRELESS_SIGNAL_V1 advertised; " + fingerprintReason + ".");
            if (secure()) send(permissionMessage(pendingClientId, permissionStore.get(pendingClientId)));
            deliverUndeliveredAlerts(client);
                            
        }

        private void requireAuthenticated(ProtocolMessage message) throws ProtocolException {
            if (!authenticated || client == null || !client.clientId.equals(message.clientId())) {
                throw new ProtocolException("Message is not authenticated for this client identity");
            }
            if (reconnectBlockedClients.contains(message.clientId())) {
                throw new ProtocolException("Client was disconnected by Server");
            }
        }

        private void handleHeartbeat(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            double cpu = message.requireDouble("cpuPercent", 0, 100);
            double ram = message.requireDouble("ramPercent", 0, 100);
            Double gpu = message.optionalDouble("gpuPercent", 0, 100);
            Double diskCapacity = message.optionalDouble("diskPercent", 0, 100);
            long receivedAt = System.currentTimeMillis();
            client.metrics.acceptHeartbeat(receivedAt, cpu, ram, gpu, diskCapacity);
            client.lastSeen = receivedAt;
            if (client.status != ClientStatus.ONLINE) client.status = ClientStatus.ONLINE;
            publishClients();
        }

        private void handleSystemInfo(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            if (!permissionStore.get(client.clientId).exportSystemInformation()) {
                listener.onLog("Ignored SYSTEM_INFO from permission-disabled Client " + client.hostname);
                return;
            }
            Object value = message.payload().get("systemInfo");
            if (!(value instanceof Map<?, ?> map)) {
                throw new ProtocolException("SYSTEM_INFO payload must contain an object");
            }
            client.systemInformation = SystemInfoMapper.fromMap(map);
            client.lastSeen = System.currentTimeMillis();
            listener.onLog("Received structured SYSTEM_INFO from " + client.hostname);
            publishClients();
        }

        private void handleTelemetry(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            Object value = message.payload().get("sample");
            if (!(value instanceof Map<?, ?> map)) {
                throw new ProtocolException("TELEMETRY payload must contain an object");
            }
            TelemetrySample sample = TelemetryMapper.fromMap(map);
            long futureTolerance = System.currentTimeMillis() + 60_000L;
            if (sample.timestamp() > futureTolerance) {
                throw new ProtocolException("Telemetry timestamp is too far in the future");
            }
            if (!client.metrics.acceptTelemetry(sample, System.currentTimeMillis())) {
                listener.onLog("Ignored out-of-order TELEMETRY from " + client.hostname
                        + " (timestamp=" + sample.timestamp() + ")");
                return;
            }
            client.telemetry.add(sample);
            listener.onTelemetry(client.clientId, sample);
            AlertEngine.Result alertResult = alertEngine.evaluate(client.clientId,
                    client.hostname, sample, System.currentTimeMillis());
            if (!alertResult.changed().isEmpty()) alertStateChanged();
            for (AlertRecord alert : alertResult.notifications()) deliverAlert(client, alert);
            publishClients();
        }

        private void handleWirelessSignal(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            Object value = message.payload().get("sample");
            if (!(value instanceof Map<?, ?> map)) {
                throw new ProtocolException("WIRELESS_SIGNAL payload must contain an object");
            }
            var sample = WirelessMapper.fromMap(map);
            if (sample.timestamp() > System.currentTimeMillis() + 60_000L) {
                throw new ProtocolException("Wireless timestamp is too far in the future");
            }
            if (!client.wireless.accept(sample)) {
                listener.onLog("Ignored out-of-order WIRELESS_SIGNAL from " + client.hostname);
                return;
            }
            client.lastSeen = System.currentTimeMillis();
            var observation = client.wireless.latest();
            long count = ++wirelessSamplesReceived;
            if (count == 1 || count % 30 == 0) {
                listener.onLog("WIRELESS_SIGNAL authenticated and stored #" + count + " for "
                        + client.hostname + " [" + client.clientId + "]: status=" + sample.status()
                        + ", network=" + sample.networkType() + ", RSSI="
                        + (sample.rssiDbm() == null ? "NOT_AVAILABLE" : sample.rssiDbm() + " dBm")
                        + ", history=" + client.wireless.history().size() + ".");
            }
            listener.onWirelessSignal(client.clientId, observation);
        }

        private void handleWifiScan(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            if (!secure() && fingerprintTlsRequired()) {
                listener.onLog("WIFI_SCAN rejected for " + client.hostname + " [" + client.clientId
                        + "]: authenticated session uses plain TCP while "
                        + "lanmonitoring.fingerprint.requireTls=true.");
                throw new ProtocolException("WIFI_SCAN requires an authenticated TLS session");
            }
            Object value = message.payload().get("scan");
            if (!(value instanceof Map<?, ?> map)) {
                throw new ProtocolException("WIFI_SCAN payload must contain an object");
            }
            WifiScanSnapshot scan = WifiScanMapper.fromMap(map);
            if (scan.timestamp() > System.currentTimeMillis() + 60_000L) {
                throw new ProtocolException("Wi-Fi scan timestamp is too far in the future");
            }
            if (client.latestWifiScan != null && scan.timestamp() <= client.latestWifiScan.timestamp()) {
                listener.onLog("WIFI_SCAN rejected for " + client.hostname + " [" + client.clientId
                        + "]: timestamp is not newer than the stored scan.");
                return;
            }
            client.latestWifiScan = scan;
            client.lastSeen = System.currentTimeMillis();
            EstimatedClientPosition estimate = fingerprintEstimator.estimate(client.clientId,
                    scan, radioMapStore.samples());
            client.fingerprintPosition = estimate;
            distanceService.updateEstimatedPosition(client.clientId, estimate);
            listener.onLog("WIFI_SCAN authenticated for " + client.hostname + " ["
                    + client.clientId + "]: status=" + scan.status() + ", hashed APs="
                    + scan.accessPoints().size() + ", position="
                    + (estimate.position() == null ? "NOT_AVAILABLE" : String.format(
                            java.util.Locale.ROOT, "(%.2f, %.2f) +/- %.2f m",
                            estimate.position().xMeters(), estimate.position().yMeters(),
                            estimate.estimatedErrorMeters())) + ".");
            publishClients();
        }

        private void handlePong(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            Long sentAt = pendingPings.remove(message.requestId());
            if (sentAt != null) {
                client.responseTimeMillis = Math.max(0, System.currentTimeMillis() - sentAt);
                client.lastSeen = System.currentTimeMillis();
                publishClients();
            }
        }

        private void handleAck(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            client.lastSeen = System.currentTimeMillis();
            listener.onAcknowledgement(client.clientId, client.hostname, message.requestId());
            listener.onLog("Received ACK from " + client.hostname
                    + " (requestId=" + message.requestId() + ")");
        }

        private void handleAlertAck(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            String alertId = message.requireString("alertId", 128);
            String deliveryStatus = message.requireString("status", 64);
            if (!"ALERT_RECEIVED".equals(deliveryStatus)) {
                throw new ProtocolException("Invalid ALERT_ACK status");
            }
            AlertRecord updated = alertEngine.updateDelivery(alertId, client.clientId,
                    AlertDeliveryStatus.ACKNOWLEDGED, message.requestId(), System.currentTimeMillis());
            if (updated == null) throw new ProtocolException("ALERT_ACK does not match this client");
            if (updated.manualNotification()) {
                updated = alertEngine.resolve(updated.id(), System.currentTimeMillis());
            }
            listener.onLog("Received ALERT_ACK from " + client.hostname + " for " + alertId);
            alertStateChanged();
        }

        private void handleFileAcceptance(ProtocolMessage message, boolean accepted)
                throws ProtocolException {
            requireAuthenticated(message);
            if (!secure()) throw new ProtocolException("File transfer responses require TLS");
            String transferId = message.requireString("transferId", 128);
            TransferControl control = transferControls.get(transferId);
            if (control == null) {
                FileTransferProgress completed = fileTransfers.get(transferId);
                if (completed != null && completed.clientId().equals(client.clientId)
                        && completed.terminal()) return;
                throw new ProtocolException("FILE response does not match active transfer");
            }
            if (!control.clientId.equals(client.clientId)) {
                throw new ProtocolException("FILE response does not match active transfer");
            }
            String result = accepted ? "ACCEPTED"
                    : "Rejected: " + message.requireString("reason", 512);
            control.acceptance.complete(result);
        }

        private void handleFileResult(ProtocolMessage message) throws ProtocolException {
            requireAuthenticated(message);
            if (!secure()) throw new ProtocolException("File transfer responses require TLS");
            String transferId = message.requireString("transferId", 128);
            TransferControl control = transferControls.get(transferId);
            if (control == null) {
                FileTransferProgress completed = fileTransfers.get(transferId);
                if (completed != null && completed.clientId().equals(client.clientId)
                        && completed.terminal()) return;
                throw new ProtocolException("FILE_RESULT does not match active transfer");
            }
            if (!control.clientId.equals(client.clientId)) {
                throw new ProtocolException("FILE_RESULT does not match active transfer");
            }
            String value = message.requireString("status", 32);
            FileTransferStatus status;
            try { status = FileTransferStatus.valueOf(value); }
            catch (IllegalArgumentException exception) {
                throw new ProtocolException("Invalid FILE_RESULT status", exception);
            }
            if (status != FileTransferStatus.SUCCESS && status != FileTransferStatus.FAILED
                    && status != FileTransferStatus.CANCELLED) {
                throw new ProtocolException("Non-terminal FILE_RESULT status");
            }
            boolean verified = message.requireBoolean("checksumVerified");
            if (status == FileTransferStatus.SUCCESS && !verified) {
                throw new ProtocolException("Successful file result requires checksum verification");
            }
            String detail = message.optionalString("reason", 512,
                    status == FileTransferStatus.SUCCESS ? "Checksum verified" : status.name());
            String savedPath = message.optionalString("savedPath", 2048, null);
            control.result.complete(new TransferResult(status, detail, savedPath));
        }

        private void deliverUndeliveredAlerts(ClientRecord target) {
            for (AlertRecord alert : alertEngine.alerts()) {
                if (alert.clientId().equals(target.clientId) && alert.status().isOpen()
                        && alert.deliveryStatus() != AlertDeliveryStatus.ACKNOWLEDGED
                        && (alert.automaticRamAlert()
                            || permissionStore.get(target.clientId).receiveAlerts())) {
                    deliverAlert(target, alert);
                }
            }
        }

        private void deliverAlert(ClientRecord target, AlertRecord alert) {
            if (target.session != this || target.status != ClientStatus.ONLINE || legacy
                    || !alert.automaticRamAlert()
                    && !permissionStore.get(target.clientId).receiveAlerts()) return;
            // Stable for retries/reconnect of this occurrence; changes on a real reminder/escalation.
            String notificationId = alert.id() + ":" + alert.updatedAt();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("alertId", alert.id()); payload.put("hostname", target.hostname);
            payload.put("metric", alert.metric().name()); payload.put("severity", alert.severity().name());
            payload.put("value", alert.value()); payload.put("threshold", alert.threshold());
            payload.put("occurredAt", alert.updatedAt()); payload.put("notificationId", notificationId);
            payload.put("mandatory", alert.automaticRamAlert());
            if (alert.title() != null) { payload.put("title", alert.title()); payload.put("content", alert.content()); }
            ProtocolMessage notification = ProtocolMessage.create(MessageType.ALERT, target.clientId,
                    payload);
            try {
                send(notification);
                alertEngine.updateDelivery(alert.id(), target.clientId,
                        AlertDeliveryStatus.SENT, notification.requestId(), System.currentTimeMillis());
                listener.onLog("Sent ALERT " + alert.id() + " to " + target.hostname);
            } catch (IOException exception) {
                alertEngine.updateDelivery(alert.id(), target.clientId,
                        AlertDeliveryStatus.FAILED, notification.requestId(), System.currentTimeMillis());
                listener.onLog("ALERT delivery failed for " + target.hostname + ": " + exception.getMessage());
            }
            alertStateChanged();
        }

        private void sendError(ProtocolMessage request, String code, String description) throws IOException {
            send(ProtocolMessage.reply(MessageType.ERROR, request, request.clientId(),
                    Map.of("code", code, "message", description)));
        }

        private void handleLegacy(PushbackInputStream input) throws IOException {
            listener.onLog("Legacy protocol connection from " + socket.getRemoteSocketAddress());
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            legacyOutput = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            String line;
            while (!closed && (line = readLimitedLine(reader)) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 4 && "CONNECT".equals(parts[0])) {
                    String safeId = "legacy:" + parts[1] + "@" + socket.getRemoteSocketAddress();
                    if (!markOnline(this, safeId, parts[2], parts[3], "Unknown (legacy client)")) {
                        close();
                        return;
                    }
                    listener.onLog("Legacy client isolated under server-assigned identity: " + safeId);
                } else if (parts.length >= 4 && "HEARTBEAT".equals(parts[0]) && client != null) {
                    try {
                        double cpu = Math.max(0, Math.min(100, Double.parseDouble(parts[2])));
                        double ram = Math.max(0, Math.min(100, Double.parseDouble(parts[3])));
                        long receivedAt = System.currentTimeMillis();
                        client.metrics.acceptHeartbeat(receivedAt, cpu, ram, null, null);
                        client.lastSeen = receivedAt;
                        publishClients();
                    } catch (NumberFormatException exception) {
                        throw new ProtocolException("Invalid legacy heartbeat", exception);
                    }
                } else if (parts.length >= 2 && "ACK".equals(parts[0]) && client != null) {
                    listener.onAcknowledgement(client.clientId, client.hostname, "legacy");
                }
            }
        }

        private String readLimitedLine(BufferedReader reader) throws IOException {
            StringBuilder line = new StringBuilder();
            int character;
            while ((character = reader.read()) >= 0) {
                if (character == '\n') break;
                if (character != '\r') line.append((char) character);
                if (line.length() > LEGACY_MAX_LINE_LENGTH) throw new ProtocolException("Legacy line too large");
            }
            return character < 0 && line.isEmpty() ? null : line.toString();
        }

        private void send(ProtocolMessage message) throws IOException {
            if (closed) throw new IOException("Connection is closed");
            if (legacy) {
                if (message.type() == MessageType.MESSAGE) {
                    synchronized (this) {
                        legacyOutput.println("MESSAGE|" + message.payload().get("content"));
                        if (legacyOutput.checkError()) throw new IOException("Legacy socket write failed");
                    }
                }
            } else {
                framed.send(message);
            }
        }

        private boolean secure() { return socket instanceof SSLSocket; }

        private void disconnect() {
            close();
            sessions.remove(this);
            ClientRecord current = client;
            if (current != null && current.session == this) {
                for (TransferControl control : transferControls.values()) {
                    if (control.clientId.equals(current.clientId)) {
                        IOException failure = new IOException("Client disconnected during transfer");
                        control.acceptance.completeExceptionally(failure);
                        control.result.completeExceptionally(failure);
                    }
                }
                current.session = null;
                current.status = reconnectBlockedClients.contains(current.clientId)
                        ? ClientStatus.DISCONNECTED_BY_SERVER : ClientStatus.OFFLINE;
                markFingerprintStale(current, "Historical estimate; Client disconnected");
                listener.onLog("Client offline: " + current.hostname);
                publishClients();
            }
        }

        private void close() {
            if (closed) return;
            closed = true;
            closeQuietly(socket);
        }
    }

    private final class ClientRecord {
        private final String clientId;
        private volatile String hostname = "";
        private volatile String ipAddress = "";
        private volatile String operatingSystem = "Unknown";
        private volatile ClientStatus status = ClientStatus.OFFLINE;
        private volatile Long responseTimeMillis;
        private volatile SystemInformation systemInformation;
        private volatile long lastSeen = System.currentTimeMillis();
        private volatile Session session;
        private final TelemetryRingBuffer telemetry = new TelemetryRingBuffer(120);
        private final ClientMetricTracker metrics = new ClientMetricTracker();
        private final WirelessTracker wireless;
        private volatile WifiScanSnapshot latestWifiScan;
        private volatile EstimatedClientPosition fingerprintPosition;

        private ClientRecord(String clientId) {
            this.clientId = clientId;
            this.wireless = new WirelessTracker(clientId, wirelessCalibrationStore);
        }

        private ClientSnapshot snapshot() {
            ClientMetrics currentMetrics = metrics.snapshot(System.currentTimeMillis());
            AlertEngine.Summary alertSummary = alertEngine.summary(clientId);
            return new ClientSnapshot(clientId, hostname, ipAddress, operatingSystem, status,
                    responseTimeMillis,
                    valueOrZero(currentMetrics.cpuUsage()),
                    valueOrZero(currentMetrics.ramUsage()),
                    currentMetrics.gpuUsage().value(),
                    currentMetrics.diskCapacity().value(),
                    lastSeen, systemInformation, currentMetrics,
                    alertSummary.highestSeverity(), alertSummary.activeCount(),
                    distanceService.distanceFor(clientId));
        }

        private static double valueOrZero(lanmonitoringserver.model.MetricReading reading) {
            return reading.value() == null ? 0d : reading.value();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }

    private static boolean fingerprintTlsRequired() {
        return Boolean.parseBoolean(System.getProperty(
                "lanmonitoring.fingerprint.requireTls", "true"));
    }

    static boolean fingerprintCapabilityAllowed(boolean secureTransport) {
        return secureTransport || !fingerprintTlsRequired();
    }

    private static final class TransferControl {
        private final String clientId;
        private final CompletableFuture<String> acceptance = new CompletableFuture<>();
        private final CompletableFuture<TransferResult> result = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private TransferControl(String clientId) { this.clientId = clientId; }
    }

    private record TransferResult(FileTransferStatus status, String detail, String savedPath) {}

    @Override
    public void close() {
        if (!serviceClosed.compareAndSet(false, true)) return;
        stop();
        scheduleAlertPersistence();
        alertPersistenceExecutor.shutdown();
        for (TransferControl control : transferControls.values()) control.cancelled.set(true);
        fileTransferExecutor.shutdownNow();
        try { alertPersistenceExecutor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
