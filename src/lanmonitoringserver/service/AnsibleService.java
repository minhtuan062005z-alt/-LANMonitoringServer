package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lanmonitoringserver.model.AnsibleAction;
import lanmonitoringserver.model.AnsibleJobResult;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.util.NamedThreadFactory;

/** Controlled Java facade for source generation, allowlist selection and Ansible execution. */
public final class AnsibleService implements AutoCloseable {
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private final AnsibleInventoryStore inventoryStore;
    private final AnsibleRunner runner;
    private final AuthorizationContext authorization;
    private final AuditLogger audit;
    private final Path sourceDirectory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("ansible-service", true));
    private volatile AnsibleEventListener listener = new AnsibleEventListener() {};
    private volatile List<AnsibleManagedHost> hosts = List.of();
    private volatile String configurationError;
    private volatile Path sourceFile;
    private volatile String sourceSha256;

    public AnsibleService(AnsibleInventoryStore inventoryStore, AnsibleRunner runner,
            AuthorizationContext authorization, AuditLogger audit, Path sourceDirectory) {
        this.inventoryStore = inventoryStore;
        this.runner = runner;
        this.authorization = authorization;
        this.audit = audit;
        this.sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
        reloadHosts();
    }

    public static AnsibleService createDefault(AuthorizationContext authorization) {
        Path workspace = Path.of(System.getProperty("lanmonitoring.ansible.workspace",
                Path.of(System.getProperty("user.home"), ".lanmonitoringserver", "ansible").toString()));
        return new AnsibleService(AnsibleInventoryStore.defaultStore(),
                AnsibleRunner.fromSystemProperties(), authorization, AuditLogger.defaultLogger(),
                workspace.resolve("sources"));
    }

    public void setListener(AnsibleEventListener listener) {
        this.listener = listener == null ? new AnsibleEventListener() {} : listener;
    }

    public synchronized List<AnsibleManagedHost> reloadHosts() {
        try {
            hosts = inventoryStore.load();
            configurationError = null;
        } catch (IOException exception) {
            hosts = List.of();
            configurationError = exception.getMessage();
        }
        return hosts;
    }

    public List<AnsibleManagedHost> hosts() { return hosts; }
    public String configurationError() { return configurationError; }
    public Path inventoryConfigurationPath() { return inventoryStore.path(); }
    public Path sourceFile() { return sourceFile; }
    public String sourceSha256() { return sourceSha256; }
    public AuthorizationContext authorization() { return authorization; }

    public synchronized GeneratedSource generateFile(String content) throws IOException {
        requireAdmin("ANSIBLE_GENERATE");
        if (content == null) throw new IllegalArgumentException("Content is required");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_CONTENT_BYTES || content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("UTF-8 content must be between 1 byte and 1 MiB");
        }
        ensureSafeSourceDirectory();
        Path target = sourceDirectory.resolve("lan_monitoring_sync.txt");
        Path temporary = sourceDirectory.resolve(".lan_monitoring_sync-" + UUID.randomUUID() + ".tmp");
        Files.write(temporary, bytes);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        sourceFile = target;
        sourceSha256 = sha256(bytes);
        audit("ANSIBLE_GENERATE", "source", "SUCCESS", "sha256=" + sourceSha256);
        listener.onLog("Generated UTF-8 source at " + target + " (SHA-256 " + sourceSha256 + ")");
        return new GeneratedSource(target, sourceSha256, bytes.length, Instant.now().toEpochMilli());
    }

    public CompletableFuture<AnsibleJobResult> deploy(Set<String> selectedInventoryNames) {
        return submit(AnsibleAction.DEPLOY, selectedInventoryNames);
    }

    public CompletableFuture<AnsibleJobResult> verify(Set<String> selectedInventoryNames) {
        return submit(AnsibleAction.VERIFY, selectedInventoryNames);
    }

    private CompletableFuture<AnsibleJobResult> submit(AnsibleAction action,
            Set<String> selectedInventoryNames) {
        requireAdmin("ANSIBLE_" + action.name());
        List<AnsibleManagedHost> selected = selectHosts(selectedInventoryNames);
        Path snapshot;
        String checksum;
        try {
            synchronized (this) {
                if (sourceFile == null || sourceSha256 == null || !Files.isRegularFile(sourceFile)) {
                    throw new IOException("Generate the managed source file first");
                }
                ensureSafeSourceDirectory();
                if (Files.isSymbolicLink(sourceFile)
                        || !FileReceiver.sha256(sourceFile).equals(sourceSha256)) {
                    throw new IOException("Managed source changed outside the application; generate it again");
                }
                snapshot = sourceDirectory.resolve("source-" + UUID.randomUUID() + ".txt");
                Files.copy(sourceFile, snapshot);
                checksum = sourceSha256;
            }
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        audit("ANSIBLE_" + action.name(), String.join(",", selectedInventoryNames),
                "STARTED", "hosts=" + selected.size() + ",sha256=" + checksum);
        return CompletableFuture.supplyAsync(() -> {
            try {
                AnsibleJobResult result = runner.run(action, snapshot, checksum, selected, listener);
                audit("ANSIBLE_" + action.name(), String.join(",", selectedInventoryNames),
                        result.successful() ? "SUCCESS" : "FAILED",
                        "job=" + result.jobId() + ",exit=" + result.exitCode());
                return result;
            } catch (IOException | RuntimeException exception) {
                audit("ANSIBLE_" + action.name(), String.join(",", selectedInventoryNames),
                        "FAILED", exception.getMessage());
                throw new CompletionException(exception);
            } finally {
                try { Files.deleteIfExists(snapshot); } catch (IOException ignored) {}
            }
        }, executor);
    }

    public boolean cancelCurrentJob() {
        requireAdmin("ANSIBLE_CANCEL");
        boolean cancelled = runner.cancel();
        audit("ANSIBLE_CANCEL", "current-job", cancelled ? "SUCCESS" : "NO_ACTIVE_JOB", "");
        return cancelled;
    }

    private List<AnsibleManagedHost> selectHosts(Set<String> names) {
        if (names == null || names.isEmpty()) throw new IllegalArgumentException("Select managed hosts");
        List<AnsibleManagedHost> selected = new ArrayList<>();
        for (String name : Set.copyOf(names)) {
            AnsibleManagedHost host = hosts.stream().filter(item -> item.inventoryName().equals(name))
                    .findFirst().orElseThrow(() -> new SecurityException(
                            "Host is not in the explicit Ansible allowlist: " + name));
            if (!host.enabled()) throw new SecurityException("Ansible management disabled for host: " + name);
            selected.add(host);
        }
        return List.copyOf(selected);
    }

    private void requireAdmin(String action) {
        if (!authorization.role().canManageAnsible()) {
            audit(action, "", "DENIED", "Only ADMIN can manage Ansible synchronization");
            throw new SecurityException("Only ADMIN can manage Ansible synchronization");
        }
    }

    private void ensureSafeSourceDirectory() throws IOException {
        Files.createDirectories(sourceDirectory);
        if (Files.isSymbolicLink(sourceDirectory)
                || !sourceDirectory.toRealPath().equals(sourceDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Ansible source directory cannot use symbolic links");
        }
    }

    private void audit(String action, String target, String outcome, String detail) {
        try { audit.record(authorization.principal(), authorization.role().name(),
                action, target, outcome, detail); }
        catch (IOException exception) { listener.onLog("Audit log failed: " + exception.getMessage()); }
    }

    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void close() {
        runner.cancel();
        executor.shutdownNow();
    }

    public record GeneratedSource(Path path, String sha256, long bytes, long generatedAt) {}
}
