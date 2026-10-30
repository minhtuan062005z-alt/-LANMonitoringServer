package lanmonitoringserver.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.FileTransferStatus;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.monitor.LocalSystemMonitor;
import lanmonitoringserver.monitor.system.GenericSystemInfoCollector;
import lanmonitoringserver.network.FramedConnection;
import lanmonitoringserver.protocol.MessageType;
import lanmonitoringserver.protocol.ProtocolMessage;
import lanmonitoringserver.security.Authentication;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.security.ClientCredentials;
import lanmonitoringserver.security.InMemoryCredentialRegistry;
import lanmonitoringserver.security.TlsSupport;

/** Negative-path release checks for TLS, plaintext denial, timeout and RBAC persistence. */
public final class Phase6SecurityValidationTest {
    private static final String[] TLS_PROPERTIES = {
        "lanmonitoring.tls.keyStore", "lanmonitoring.tls.keyStorePassword",
        "lanmonitoring.tls.trustStore", "lanmonitoring.tls.trustStorePassword"
    };

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lan-phase6-security-");
        try {
            clearTlsProperties();
            check(!TlsSupport.configured(), "TLS is opt-in when no property/environment setting exists");
            TlsMaterial trusted = createTlsMaterial(root.resolve("trusted"), "localhost");
            TlsMaterial foreign = createTlsMaterial(root.resolve("foreign"), "foreign.invalid");
            configure(trusted);
            tlsValidationTests(trusted, foreign);
            plaintextDenialTest(root.resolve("plaintext"));
            configure(trusted);
            transferTimeoutTest(root.resolve("timeout"));
            rbacAndAuditTest(root.resolve("rbac"));
            symbolicLinkTest(root.resolve("symlink"));
            System.out.println("Phase6SecurityValidationTest: PASS");
        } finally {
            clearTlsProperties();
            deleteTree(root);
        }
    }

    private static void tlsValidationTests(TlsMaterial trusted, TlsMaterial foreign) throws Exception {
        ServerSocketFactory serverFactory = TlsSupport.serverSocketFactory();
        SocketFactory trustedClient = TlsSupport.clientSocketFactory();
        System.setProperty("lanmonitoring.tls.trustStore", foreign.trustStore().toString());
        System.setProperty("lanmonitoring.tls.trustStorePassword", foreign.password());
        SocketFactory untrustedClient = TlsSupport.clientSocketFactory();
        configure(trusted);

        expectHandshakeFailure(serverFactory, untrustedClient, "localhost",
                "certificate signed by an untrusted test authority");
        expectHandshakeFailure(serverFactory, trustedClient, "127.0.0.1",
                "certificate hostname/IP does not match SAN");
    }

    private static void expectHandshakeFailure(ServerSocketFactory serverFactory,
            SocketFactory clientFactory, String host, String description) throws Exception {
        try (ServerSocket listener = serverFactory.createServerSocket(0)) {
            CompletableFuture<Void> serverSide = CompletableFuture.runAsync(() -> {
                try (Socket accepted = listener.accept()) {
                    ((SSLSocket) accepted).startHandshake();
                } catch (IOException expected) {
                    // The peer also observes a handshake failure.
                }
            });
            boolean failed = false;
            try (SSLSocket socket = (SSLSocket) clientFactory.createSocket()) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress(host, listener.getLocalPort()), 3_000);
                socket.startHandshake();
            } catch (IOException expected) {
                failed = true;
            }
            check(failed, description + " is rejected");
            serverSide.get(5, TimeUnit.SECONDS);
        }
    }

    private static void plaintextDenialTest(Path root) throws Exception {
        Files.createDirectories(root);
        clearTlsProperties();
        ClientCredentials identity = ClientCredentials.generate();
        ClientPermissionStore permissions = new ClientPermissionStore(root.resolve("permissions.json"));
        ClientPermissions allowed = new ClientPermissions(true, true, true);
        permissions.put(identity.clientId(), allowed);
        AuthorizationContext admin = new AuthorizationContext("plaintext-admin", UserRole.ADMIN, Set.of());
        try (LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                    ServerSocketFactory.getDefault(), new InMemoryAlertStore(), permissions,
                    new AuditLogger(root.resolve("audit.jsonl")), admin);
                LanClientService client = new LanClientService(identity, new LocalSystemMonitor(),
                    SocketFactory.getDefault(), new GenericSystemInfoCollector())) {
            int port = server.start(0);
            client.connect("127.0.0.1", port, "PLAINTEXT-PC", "127.0.0.1");
            await(Duration.ofSeconds(8), () -> server.clients().stream().anyMatch(item ->
                    item.clientId().equals(identity.clientId()) && item.status() == ClientStatus.ONLINE),
                    "plaintext monitoring Client authenticates");
            Path source = root.resolve("blocked.txt");
            Files.writeString(source, "must not cross plaintext", StandardCharsets.UTF_8);
            expectSecurityOrIo(() -> server.sendFile(source, Set.of(identity.clientId())),
                    "file transfer is denied on plaintext");
            expectSecurityOrIo(() -> server.setClientPermissions(identity.clientId(),
                    new ClientPermissions(false, false, false)),
                    "permission mutation is denied for an online plaintext Client");
            check(new ClientPermissionStore(root.resolve("permissions.json")).get(identity.clientId())
                    .equals(allowed), "plaintext permission attempt is not persisted");
        }
    }

    private static void transferTimeoutTest(Path root) throws Exception {
        Files.createDirectories(root);
        ClientCredentials identity = ClientCredentials.generate();
        ClientPermissionStore permissions = new ClientPermissionStore(root.resolve("permissions.json"));
        permissions.put(identity.clientId(), new ClientPermissions(true, true, true));
        AuthorizationContext admin = new AuthorizationContext("timeout-admin", UserRole.ADMIN, Set.of());
        System.setProperty("lanmonitoring.file.offer.timeout.ms", "250");
        try (LanServerService server = new LanServerService(new InMemoryCredentialRegistry(),
                    TlsSupport.serverSocketFactory(), new InMemoryAlertStore(), permissions,
                    new AuditLogger(root.resolve("audit.jsonl")), admin)) {
            int port = server.start(0);
            try (FramedConnection silent = authenticateSilentTlsClient(identity, port)) {
                check(silent.socket().isConnected(), "silent TLS fixture remains connected");
                await(Duration.ofSeconds(5), () -> server.clients().stream().anyMatch(item ->
                        item.clientId().equals(identity.clientId()) && item.status() == ClientStatus.ONLINE),
                        "silent TLS Client authenticates");
                Path source = root.resolve("timeout.txt");
                Files.writeString(source, "no FILE_ACCEPT response", StandardCharsets.UTF_8);
                String transferId = server.sendFile(source, Set.of(identity.clientId())).get(0);
                await(Duration.ofSeconds(5), () -> server.fileTransfers().stream().anyMatch(item ->
                        item.transferId().equals(transferId) && item.status() == FileTransferStatus.FAILED
                        && item.detail().contains("timeout")), "offer timeout becomes FAILED");
            }
        } finally {
            System.clearProperty("lanmonitoring.file.offer.timeout.ms");
        }
    }

    private static FramedConnection authenticateSilentTlsClient(ClientCredentials identity, int port)
            throws Exception {
        SSLSocket socket = (SSLSocket) TlsSupport.clientSocketFactory().createSocket();
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.connect(new InetSocketAddress("localhost", port), 3_000);
        socket.startHandshake();
        socket.setSoTimeout(3_000);
        FramedConnection connection = new FramedConnection(socket);
        String nonce = Authentication.newNonce();
        connection.send(ProtocolMessage.create(MessageType.CONNECT, identity.clientId(), Map.of(
                "hostname", "SILENT-TLS", "ipAddress", "127.0.0.1",
                "operatingSystem", "test", "clientNonce", nonce)));
        ProtocolMessage notRegistered = connection.read();
        check(notRegistered.type() == MessageType.ERROR, "unknown identity is challenged to register");
        connection.send(ProtocolMessage.create(MessageType.REGISTER, identity.clientId(),
                Map.of("secret", identity.secret())));
        check(connection.read().type() == MessageType.REGISTERED, "silent Client registers");
        connection.send(ProtocolMessage.create(MessageType.CONNECT, identity.clientId(), Map.of(
                "hostname", "SILENT-TLS", "ipAddress", "127.0.0.1",
                "operatingSystem", "test", "clientNonce", nonce)));
        ProtocolMessage challenge = connection.read();
        String serverNonce = challenge.requireString("serverNonce", 128);
        String proof = Authentication.proof(identity.secret(), identity.clientId(), nonce, serverNonce);
        connection.send(ProtocolMessage.reply(MessageType.AUTH, challenge, identity.clientId(),
                Map.of("proof", proof)));
        check(connection.read().type() == MessageType.AUTH_OK, "silent Client authenticates by credential proof");
        check(connection.read().type() == MessageType.PERMISSIONS_UPDATE,
                "permissions are delivered only after authenticated TLS");
        return connection;
    }

    private static void rbacAndAuditTest(Path root) throws Exception {
        Files.createDirectories(root);
        Path source = root.resolve("roles.txt");
        Files.writeString(source, "roles", StandardCharsets.UTF_8);
        Path auditPath = root.resolve("audit.jsonl");
        ClientPermissions assigned = new ClientPermissions(true, false, true);

        ClientPermissionStore adminStore = new ClientPermissionStore(root.resolve("admin.json"));
        try (LanServerService admin = service(root, adminStore, auditPath,
                new AuthorizationContext("admin-user", UserRole.ADMIN, Set.of()))) {
            admin.setClientPermissions("target", assigned);
            expectIo(() -> admin.sendFile(source, Set.of("target")),
                    "ADMIN passes role check and reaches offline target validation");
        }
        check(new ClientPermissionStore(root.resolve("admin.json")).get("target").equals(assigned),
                "ADMIN permission assignment persists across restart");

        for (UserRole deniedRole : List.of(UserRole.OPERATOR, UserRole.VIEWER, UserRole.CLIENT)) {
            try (LanServerService service = service(root,
                    new ClientPermissionStore(root.resolve(deniedRole.name() + ".json")), auditPath,
                    new AuthorizationContext(deniedRole.name().toLowerCase(), deniedRole,
                            Set.of("target")))) {
                expectSecurity(() -> service.setClientPermissions("target", assigned),
                        deniedRole + " cannot manage permissions at service layer");
                if (deniedRole == UserRole.OPERATOR) {
                    expectIo(() -> service.sendFile(source, Set.of("target")),
                            "OPERATOR in-scope reaches target eligibility validation");
                    expectIo(() -> service.sendFile(source, Set.of("outside")),
                            "OPERATOR out-of-scope target is denied");
                } else {
                    expectSecurity(() -> service.sendFile(source, Set.of("target")),
                            deniedRole + " cannot send at service layer");
                }
            }
        }
        String audit = Files.readString(auditPath, StandardCharsets.UTF_8);
        check(audit.contains("\"role\":\"ADMIN\"") && audit.contains("\"role\":\"OPERATOR\"")
                && audit.contains("\"role\":\"VIEWER\"") && audit.contains("\"role\":\"CLIENT\"")
                && audit.contains("\"outcome\":\"DENIED\""),
                "audit log persists principals/roles and denied administrative outcomes");
    }

    private static LanServerService service(Path root, ClientPermissionStore store, Path audit,
            AuthorizationContext context) {
        return new LanServerService(new InMemoryCredentialRegistry(), ServerSocketFactory.getDefault(),
                new InMemoryAlertStore(), store, new AuditLogger(audit), context);
    }

    private static void symbolicLinkTest(Path root) throws Exception {
        Files.createDirectories(root);
        Path real = root.resolve("real");
        Files.createDirectories(real);
        Path link = root.resolve("receive-link");
        try {
            Files.createSymbolicLink(link, real);
            try (FileReceiver receiver = new FileReceiver(link, 1024)) {
                check(!receiver.begin("symlink-root", "escape.bin", 1, "0".repeat(64), true).accepted(),
                        "symbolic-link receive directory is rejected");
            }
        } catch (UnsupportedOperationException | IOException exception) {
            System.out.println("Phase6SecurityValidationTest: symlink creation unavailable; branch NOT EXECUTED: "
                    + exception.getClass().getSimpleName());
        }
    }

    private static TlsMaterial createTlsMaterial(Path directory, String dnsName) throws Exception {
        Files.createDirectories(directory);
        String password = "T" + UUID.randomUUID().toString().replace("-", "");
        Path keyStore = directory.resolve("server.p12");
        Path certificate = directory.resolve("server.cer");
        Path trustStore = directory.resolve("trust.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", password,
                "-keypass", password, "-dname", "CN=" + dnsName, "-validity", "2",
                "-ext", "SAN=dns:" + dnsName, "-noprompt");
        runKeytool("-exportcert", "-alias", "server", "-keystore", keyStore.toString(),
                "-storepass", password, "-file", certificate.toString(), "-noprompt");
        runKeytool("-importcert", "-alias", "server", "-storetype", "PKCS12",
                "-keystore", trustStore.toString(), "-storepass", password,
                "-file", certificate.toString(), "-noprompt");
        return new TlsMaterial(keyStore, trustStore, password);
    }

    private static void configure(TlsMaterial material) {
        System.setProperty("lanmonitoring.tls.keyStore", material.keyStore().toString());
        System.setProperty("lanmonitoring.tls.keyStorePassword", material.password());
        System.setProperty("lanmonitoring.tls.trustStore", material.trustStore().toString());
        System.setProperty("lanmonitoring.tls.trustStorePassword", material.password());
    }

    private static void clearTlsProperties() {
        for (String property : TLS_PROPERTIES) System.clearProperty(property);
    }

    private static void runKeytool(String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("keytool failed: " + output);
        }
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

    private static void expectSecurity(Action action, String description) throws Exception {
        try { action.run(); throw new AssertionError("Expected SecurityException: " + description); }
        catch (SecurityException expected) {}
    }

    private static void expectIo(Action action, String description) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException: " + description); }
        catch (IOException expected) {}
    }

    private static void expectSecurityOrIo(Action action, String description) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + description); }
        catch (SecurityException | IOException expected) {}
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }

    private record TlsMaterial(Path keyStore, Path trustStore, String password) {}
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
