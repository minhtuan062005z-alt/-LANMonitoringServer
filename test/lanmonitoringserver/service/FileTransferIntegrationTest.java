package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.FileTransferStatus;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.monitor.LocalSystemMonitor;
import lanmonitoringserver.monitor.metrics.MetricsCollector;
import lanmonitoringserver.monitor.system.GenericSystemInfoCollector;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.InMemoryCredentialRegistry;
import lanmonitoringserver.security.TlsSupport;

/** Real TLS/TCP file-transfer integration with one Server and three Clients. */
public final class FileTransferIntegrationTest {
    private static final String PASSWORD = "T" + java.util.UUID.randomUUID().toString().replace("-", "");

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lan-phase6-file-test-");
        try {
            configureTls(root);
            receiverSecurityTests(root.resolve("receiver-security"));
            roleAuthorizationTest(root);
            transferToOneAndThreeClients(root);
            System.out.println("FileTransferIntegrationTest: PASS");
        } finally {
            deleteTree(root);
        }
    }

    private static void transferToOneAndThreeClients(Path root) throws Exception {
        ClientPermissionStore permissionStore = new ClientPermissionStore(root.resolve("permissions.json"));
        AuditLogger audit = new AuditLogger(root.resolve("audit.jsonl"));
        AuthorizationContext admin = new AuthorizationContext("phase6-admin", UserRole.ADMIN, Set.of());
        LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                TlsSupport.serverSocketFactory(), new InMemoryAlertStore(), permissionStore, audit, admin);
        List<String> tlsServerLogs = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.setListener(new ServerEventListener() {
            @Override public void onLog(String message) { tlsServerLogs.add(message); }
        });
        List<LanClientService> clients = new ArrayList<>();
        List<String> tlsClientLogs = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<ClientCredentials> identities = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ClientCredentials identity = ClientCredentials.generate();
            identities.add(identity);
            permissionStore.put(identity.clientId(), new ClientPermissions(true, true, true));
        }
        int port = server.start(0);
        try {
            verifyStableIdentityMismatchRejected(server, port);
            for (int index = 0; index < 3; index++) {
                FileReceiver receiver = new FileReceiver(root.resolve("client-" + index), 20L * 1024 * 1024);
                LanClientService client = new LanClientService(identities.get(index),
                        new LocalSystemMonitor(), TlsSupport.clientSocketFactory(),
                        new GenericSystemInfoCollector(), fixedMetrics(), receiver);
                if (index == 0) {
                    client.setListener(new ClientEventListener() {
                        @Override public void onLog(String message) { tlsClientLogs.add(message); }
                    });
                }
                clients.add(client);
                client.connect("127.0.0.1", port, "TLS-PC-" + (index + 1), "127.0.0.1");
            }
            await(Duration.ofSeconds(15), () -> online(server) == 3, "three TLS clients online");
            await(Duration.ofSeconds(5), () -> tlsClientLogs.stream().anyMatch(value ->
                            value.contains("transport=TLS")
                            && value.contains("WIFI_FINGERPRINT_V1=true")),
                    "TLS Client negotiates WIFI_FINGERPRINT_V1");
            check(tlsServerLogs.stream().anyMatch(value ->
                            value.contains("WIFI_FINGERPRINT_V1 advertised on authenticated TLS")),
                    "TLS Server advertises fingerprint capability");
            check(tlsClientLogs.stream().anyMatch(value -> value.contains(
                            "TCP endpoint=127.0.0.1:")
                            && value.contains("certificate identity=lanmonitoring-server.test")),
                    "changing TCP IPv4 is separated from the stable TLS identity");

            clients.get(0).disconnect();
            await(Duration.ofSeconds(5), () -> status(server, identities.get(0).clientId())
                    == ClientStatus.OFFLINE, "stable-identity TLS Client disconnects");
            clients.get(0).connect("127.0.0.1", port, "TLS-PC-1", "127.0.0.1");
            await(Duration.ofSeconds(10), () -> status(server, identities.get(0).clientId())
                    == ClientStatus.ONLINE, "stable-identity TLS Client reconnects by IPv4");

            Path text = root.resolve("sample.txt");
            Files.writeString(text, "Giai đoạn 6 – UTF-8\n".repeat(2_000), StandardCharsets.UTF_8);
            String firstClient = identities.get(0).clientId();
            List<String> one = server.sendFile(text, Set.of(firstClient));
            awaitSuccess(server, one, "text file to one Client");
            check(Arrays.equals(Files.readAllBytes(text),
                    Files.readAllBytes(root.resolve("client-0").resolve("sample.txt"))),
                    "single-client text content matches");
            List<String> retry = server.sendFile(text, Set.of(firstClient));
            awaitSuccess(server, retry, "safe retry as a new transfer");
            check(Arrays.equals(Files.readAllBytes(text),
                    Files.readAllBytes(root.resolve("client-0").resolve("sample (1).txt"))),
                    "duplicate policy preserves original and creates a unique name");

            Path image = root.resolve("image.png");
            java.awt.image.BufferedImage fixture = new java.awt.image.BufferedImage(
                    256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < fixture.getHeight(); y++) {
                for (int x = 0; x < fixture.getWidth(); x++) {
                    fixture.setRGB(x, y, 0xff000000 | x << 16 | y << 8 | (x ^ y));
                }
            }
            check(javax.imageio.ImageIO.write(fixture, "png", image.toFile()),
                    "valid PNG fixture created");
            byte[] imageBytes = Files.readAllBytes(image);
            Set<String> allIds = Set.of(identities.get(0).clientId(), identities.get(1).clientId(),
                    identities.get(2).clientId());
            List<String> three = server.sendFile(image, allIds);
            check(three.size() == 3, "three per-client transfer IDs created");
            awaitSuccess(server, three, "image file to three Clients");
            for (int index = 0; index < 3; index++) {
                check(Arrays.equals(imageBytes, Files.readAllBytes(
                        root.resolve("client-" + index).resolve("image.png"))),
                        "image content matches on Client " + index);
            }
            await(Duration.ofSeconds(5), () -> server.clients().stream()
                    .allMatch(client -> client.status() == ClientStatus.ONLINE
                            && !server.telemetryHistory(client.clientId()).isEmpty()),
                    "telemetry remains active after multi-Client file transfer");

            Path binary = root.resolve("payload.bin");
            byte[] binaryBytes = new byte[350_000];
            new java.security.SecureRandom().nextBytes(binaryBytes);
            Files.write(binary, binaryBytes);
            List<String> binaryTransfer = server.sendFile(binary, Set.of(identities.get(1).clientId()));
            awaitSuccess(server, binaryTransfer, "binary file");

            server.setClientPermissions(identities.get(2).clientId(),
                    new ClientPermissions(false, true, true));
            expectFailure(() -> server.sendFile(text, Set.of(identities.get(2).clientId())),
                    "permission-disabled Client is rejected");

            clients.get(2).disconnect();
            await(Duration.ofSeconds(5), () -> status(server, identities.get(2).clientId()) == ClientStatus.OFFLINE,
                    "Client offline");
            server.setClientPermissions(identities.get(2).clientId(),
                    new ClientPermissions(true, true, true));
            expectFailure(() -> server.sendFile(text, Set.of(identities.get(2).clientId())),
                    "offline Client is rejected");

            Path cancelledFile = root.resolve("cancel.bin");
            byte[] cancellable = new byte[4 * 1024 * 1024];
            new java.security.SecureRandom().nextBytes(cancellable);
            Files.write(cancelledFile, cancellable);
            AtomicBoolean cancelRequested = new AtomicBoolean();
            server.setListener(new ServerEventListener() {
                @Override public void onFileTransfersChanged(List<FileTransferProgress> transfers) {
                    transfers.stream().filter(item -> item.fileName().equals("cancel.bin")
                            && item.status() == FileTransferStatus.IN_PROGRESS).findFirst()
                            .ifPresent(item -> {
                                if (cancelRequested.compareAndSet(false, true)) {
                                    server.cancelFileTransfer(item.transferId());
                                }
                            });
                }
            });
            List<String> cancelledIds = server.sendFile(cancelledFile, Set.of(identities.get(0).clientId()));
            await(Duration.ofSeconds(10), () -> statusOf(server, cancelledIds.get(0))
                    == FileTransferStatus.CANCELLED, "explicit cancellation");
            check(!Files.exists(root.resolve("client-0").resolve("cancel.bin")),
                    "cancelled file is not published");

            Path interrupted = root.resolve("disconnect.bin");
            byte[] large = new byte[4 * 1024 * 1024];
            new java.security.SecureRandom().nextBytes(large);
            Files.write(interrupted, large);
            AtomicBoolean disconnected = new AtomicBoolean();
            LanClientService victim = clients.get(1);
            victim.setListener(new ClientEventListener() {
                @Override public void onFileTransferProgress(FileTransferProgress progress) {
                    if (progress.status() == FileTransferStatus.IN_PROGRESS
                            && disconnected.compareAndSet(false, true)) victim.disconnect();
                }
            });
            List<String> interruptedIds = server.sendFile(interrupted, Set.of(identities.get(1).clientId()));
            await(Duration.ofSeconds(10), () -> terminal(server, interruptedIds.get(0)),
                    "disconnect transfer reaches terminal state");
            check(statusOf(server, interruptedIds.get(0)) != FileTransferStatus.SUCCESS,
                    "mid-transfer disconnect is not reported as success");
            check(Files.size(root.resolve("audit.jsonl")) > 0, "administrative audit log written");
        } finally {
            for (LanClientService client : clients) client.close();
            server.close();
        }
    }

    private static void verifyStableIdentityMismatchRejected(LanServerService server, int port)
            throws Exception {
        String expectedIdentity = System.getProperty("lanmonitoring.tls.serverIdentity");
        List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        System.setProperty("lanmonitoring.tls.serverIdentity", "wrong-server-identity.test");
        try (LanClientService rejected = new LanClientService(ClientCredentials.generate(),
                new LocalSystemMonitor(), TlsSupport.clientSocketFactory(),
                new GenericSystemInfoCollector())) {
            rejected.setListener(new ClientEventListener() {
                @Override public void onLog(String message) { logs.add(message); }
            });
            rejected.connect("127.0.0.1", port, "WRONG-TLS-IDENTITY", "127.0.0.1");
            await(Duration.ofSeconds(5), () -> logs.stream().anyMatch(value ->
                    value.startsWith("Connection failed:")),
                    "wrong stable TLS identity is rejected by hostname verification");
            check(server.clients().stream().noneMatch(value ->
                            value.hostname().equals("WRONG-TLS-IDENTITY")),
                    "identity-mismatched TLS Client never authenticates");
        } finally {
            System.setProperty("lanmonitoring.tls.serverIdentity", expectedIdentity);
        }
    }

    private static void receiverSecurityTests(Path directory) throws Exception {
        FileReceiver receiver = new FileReceiver(directory, 4);
        check(!receiver.begin("size-limit", "large.bin", 5, "0".repeat(64), true).accepted(),
                "size limit enforced");
        check(!receiver.begin("traversal", "../escape.txt", 1, "0".repeat(64), true).accepted(),
                "path traversal rejected");
        check(!receiver.begin("windows-path", "C:\\escape.txt", 1, "0".repeat(64), true).accepted(),
                "absolute/platform path syntax rejected");
        check(!receiver.begin("denied", "denied.txt", 1, "0".repeat(64), false).accepted(),
                "Client receive permission enforced");
        FileReceiver noSpace = new FileReceiver(directory.resolve("no-space"), 100, () -> 0);
        check(!noSpace.begin("no-space", "full.bin", 1, "0".repeat(64), true).accepted(),
                "insufficient disk space is rejected before transfer");
        noSpace.close();
        byte[] bytes = new byte[]{1, 2, 3, 4};
        check(receiver.begin("bad-checksum", "corrupt.bin", bytes.length, "0".repeat(64), true).accepted(),
                "checksum fixture accepted initially");
        receiver.acceptChunk("bad-checksum", 0, java.util.Base64.getEncoder().encodeToString(bytes));
        expectFailure(() -> receiver.finish("bad-checksum"), "checksum mismatch rejected");
        check(!Files.exists(directory.resolve("corrupt.bin")), "corrupt file is never published");

        Path executableMarker = directory.resolve("must-not-exist.marker");
        byte[] command = ("echo unsafe > \"" + executableMarker + "\"").getBytes(StandardCharsets.UTF_8);
        String commandHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(command));
        try (FileReceiver inertReceiver = new FileReceiver(directory.resolve("inert"), 4096)) {
            check(inertReceiver.begin("no-auto-exec", "received.cmd", command.length,
                    commandHash, true).accepted(),
                    "executable-looking file is accepted as inert data");
            inertReceiver.acceptChunk("no-auto-exec", 0,
                    java.util.Base64.getEncoder().encodeToString(command));
            inertReceiver.finish("no-auto-exec");
            Thread.sleep(100);
            check(!Files.exists(executableMarker), "received file is never automatically executed");
        }
        receiver.close();
    }

    private static void roleAuthorizationTest(Path root) throws Exception {
        Path source = root.resolve("viewer.txt");
        Files.writeString(source, "viewer cannot send", StandardCharsets.UTF_8);
        AuthorizationContext viewer = new AuthorizationContext("viewer", UserRole.VIEWER, Set.of());
        try (LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                ServerSocketFactory.getDefault(), new InMemoryAlertStore(),
                new ClientPermissionStore(root.resolve("viewer-permissions.json")),
                new AuditLogger(root.resolve("viewer-audit.jsonl")), viewer)) {
            expectFailure(() -> server.sendFile(source, Set.of("client")),
                    "VIEWER cannot send files");
            expectFailure(() -> server.setClientPermissions("client", ClientPermissions.defaults()),
                    "VIEWER cannot manage permissions");
        }
        AuthorizationContext operator = new AuthorizationContext("operator", UserRole.OPERATOR,
                Set.of("allowed"));
        check(operator.canAccessClient("allowed") && !operator.canAccessClient("other"),
                "OPERATOR scope limits target Clients");

        Path persisted = root.resolve("persisted-permissions.json");
        ClientPermissionStore written = new ClientPermissionStore(persisted);
        ClientPermissions expected = new ClientPermissions(true, false, false);
        written.put("persistent-client", expected);
        check(new ClientPermissionStore(persisted).get("persistent-client").equals(expected),
                "Client permissions survive store restart");

        Path blocker = root.resolve("permission-parent-is-file");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        ClientPermissionStore failing = new ClientPermissionStore(blocker.resolve("permissions.json"));
        expectFailure(() -> failing.put("rollback-client", expected),
                "permission persistence error is surfaced");
        check(failing.get("rollback-client").equals(ClientPermissions.defaults()),
                "failed permission save rolls back in-memory state");
    }

    private static MetricsCollector fixedMetrics() {
        return new MetricsCollector() {
            @Override public TelemetrySample collect() {
                long now = System.currentTimeMillis();
                return new TelemetrySample(now, 1_000, MeasurementStatus.OK, 10, 1, 2, null,
                        MeasurementStatus.OK, 20, 2, 8, 10, MeasurementStatus.UNSUPPORTED,
                        null, null, null, null, "test", MeasurementStatus.UNSUPPORTED,
                        null, 0, 0, 10d, MeasurementStatus.OK, 0, 0, "test", "test");
            }
            @Override public String collectorName() { return "Phase6TestMetrics"; }
        };
    }

    private static void configureTls(Path root) throws Exception {
        Path keyStore = root.resolve("server.p12");
        Path certificate = root.resolve("server.cer");
        Path trustStore = root.resolve("trust.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", PASSWORD,
                "-keypass", PASSWORD, "-dname", "CN=lanmonitoring-server.test", "-validity", "2",
                "-ext", "SAN=dns:lanmonitoring-server.test", "-noprompt");
        runKeytool("-exportcert", "-alias", "server", "-keystore", keyStore.toString(),
                "-storepass", PASSWORD, "-file", certificate.toString(), "-noprompt");
        runKeytool("-importcert", "-alias", "server", "-storetype", "PKCS12",
                "-keystore", trustStore.toString(), "-storepass", PASSWORD,
                "-file", certificate.toString(), "-noprompt");
        System.setProperty("lanmonitoring.tls.keyStore", keyStore.toString());
        System.setProperty("lanmonitoring.tls.keyStorePassword", PASSWORD);
        System.setProperty("lanmonitoring.tls.trustStore", trustStore.toString());
        System.setProperty("lanmonitoring.tls.trustStorePassword", PASSWORD);
        System.setProperty("lanmonitoring.tls.serverIdentity", "lanmonitoring-server.test");
    }

    private static void runKeytool(String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>();
        command.add(executable.toString()); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("keytool failed: " + output);
        }
    }

    private static int online(LanServerService server) {
        return (int) server.clients().stream().filter(client -> client.status() == ClientStatus.ONLINE).count();
    }
    private static ClientStatus status(LanServerService server, String id) {
        return server.clients().stream().filter(client -> client.clientId().equals(id))
                .map(client -> client.status()).findFirst().orElse(null);
    }
    private static void awaitSuccess(LanServerService server, List<String> ids, String description)
            throws Exception {
        await(Duration.ofSeconds(20), () -> ids.stream().allMatch(id ->
                statusOf(server, id) == FileTransferStatus.SUCCESS), description);
    }
    private static boolean terminal(LanServerService server, String id) {
        return server.fileTransfers().stream().filter(item -> item.transferId().equals(id))
                .anyMatch(FileTransferProgress::terminal);
    }
    private static FileTransferStatus statusOf(LanServerService server, String id) {
        return server.fileTransfers().stream().filter(item -> item.transferId().equals(id))
                .map(FileTransferProgress::status).findFirst().orElse(null);
    }
    private static void await(Duration timeout, BooleanSupplier condition, String description)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out: " + description);
    }
    private static void expectFailure(Action action, String description) throws Exception {
        try { action.run(); throw new AssertionError("Expected failure: " + description); }
        catch (IOException | SecurityException | IllegalArgumentException expected) {}
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
