package lanmonitoringserver.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lanmonitoringserver.model.AnsibleAction;
import lanmonitoringserver.model.AnsibleConnectionType;
import lanmonitoringserver.model.AnsibleHostStatus;
import lanmonitoringserver.model.AnsibleJobResult;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.model.AnsiblePlatform;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.security.AuthorizationContext;

/** Phase 7 controlled process integration; it does not claim real managed-host deployment. */
public final class AnsibleIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lan-phase7-ansible-");
        try {
            Path config = writeConfiguration(root);
            List<AnsibleManagedHost> hosts = new AnsibleInventoryStore(config).load();
            check(hosts.size() == 6, "explicit Windows/Linux allowlist loads");
            validationTests();
            playbookStructureTests();
            integrationAndIdempotency(root, config, hosts);
            timeoutAndCancel(root, hosts.get(0));
            rbacTest(root, config);
            System.out.println("AnsibleIntegrationTest: PASS (controlled process; no real hosts)");
        } finally {
            deleteTree(root);
        }
    }

    private static void integrationAndIdempotency(Path root, Path config,
            List<AnsibleManagedHost> hosts) throws Exception {
        Path vault = fakeVault(root); Path password = fakeVaultPassword(root);
        Path workspace = root.resolve("workspace");
        Path state = root.resolve("fake-state.properties");
        AnsibleRunner runner = runner(workspace, vault, password, state, "normal", Duration.ofSeconds(5));
        AuthorizationContext admin = new AuthorizationContext("phase7-admin", UserRole.ADMIN, Set.of());
        try (AnsibleService service = new AnsibleService(new AnsibleInventoryStore(config), runner,
                admin, new AuditLogger(root.resolve("audit.jsonl")), root.resolve("sources"))) {
            String content = "Xin chào Windows và Linux — Phase 7\n";
            AnsibleService.GeneratedSource source = service.generateFile(content);
            check(Files.readString(source.path(), StandardCharsets.UTF_8).equals(content),
                    "generated source preserves exact UTF-8 content");
            Set<String> selected = Set.of("linux_01", "linux_02", "windows_01");
            AnsibleJobResult first = service.deploy(selected).get(10, TimeUnit.SECONDS);
            check(first.successful() && first.hosts().size() == 3,
                    "controlled deploy returns per-host success for three hosts");
            check(first.hosts().values().stream().allMatch(result -> result.changed()
                    && result.sha256().equals(source.sha256())), "first run changes all hosts and verifies SHA-256");
            AnsibleJobResult second = service.deploy(selected).get(10, TimeUnit.SECONDS);
            check(second.successful() && second.hosts().values().stream().noneMatch(result -> result.changed()),
                    "second run is idempotent");
            AnsibleJobResult verify = service.verify(selected).get(10, TimeUnit.SECONDS);
            check(verify.successful() && verify.action() == AnsibleAction.VERIFY,
                    "verify-only job confirms synchronization");

            AnsibleJobResult mixed = service.deploy(Set.of("linux_01", "linux_offline", "windows_denied"))
                    .get(10, TimeUnit.SECONDS);
            check(mixed.hosts().get("linux_01").status() == AnsibleHostStatus.SUCCESS,
                    "successful host remains successful when another host fails");
            check(mixed.hosts().get("linux_offline").status() == AnsibleHostStatus.UNREACHABLE,
                    "offline managed host is distinct from Java Socket state");
            check(mixed.hosts().get("windows_denied").status() == AnsibleHostStatus.FAILED,
                    "remote permission failure is reported per host");
            expectFailure(() -> service.deploy(Set.of("disabled_windows")),
                    "disabled allowlist host cannot be managed");

            Path inventory = Files.walk(workspace).filter(path -> path.getFileName().toString().equals("inventory.yml"))
                    .findFirst().orElseThrow();
            String generatedInventory = Files.readString(inventory, StandardCharsets.UTF_8);
            check(generatedInventory.contains("lan_linux:") && generatedInventory.contains("lan_windows:"),
                    "generated inventory separates Windows and Linux groups");
            check(generatedInventory.contains("vault_hosts") && !generatedInventory.contains("fake-password"),
                    "inventory references encrypted vault variables and contains no credential value");
            String audit = Files.readString(root.resolve("audit.jsonl"), StandardCharsets.UTF_8);
            check(audit.contains("ANSIBLE_DEPLOY") && audit.contains("phase7-admin"),
                    "Ansible administrative operations are audited");
            Files.writeString(source.path(), "tampered outside application", StandardCharsets.UTF_8);
            expectFutureFailure(service.deploy(Set.of("linux_01")),
                    "externally modified managed source is rejected");
        }
    }

    private static void timeoutAndCancel(Path root, AnsibleManagedHost host) throws Exception {
        Path vault = fakeVault(root); Path password = fakeVaultPassword(root);
        Path source = root.resolve("timeout-source.txt"); Files.writeString(source, "timeout");
        String checksum = FileReceiver.sha256(source);
        AnsibleRunner timeoutRunner = runner(root.resolve("timeout-work"), vault, password,
                root.resolve("timeout-state"), "sleep", Duration.ofMillis(250));
        AnsibleJobResult timeout = timeoutRunner.run(AnsibleAction.DEPLOY, source, checksum,
                List.of(host), new AnsibleEventListener() {});
        check(timeout.timedOut() && timeout.hosts().get(host.inventoryName()).status()
                == AnsibleHostStatus.TIMED_OUT, "Ansible process timeout is enforced");

        AnsibleRunner cancelRunner = runner(root.resolve("cancel-work"), vault, password,
                root.resolve("cancel-state"), "sleep", Duration.ofSeconds(10));
        CompletableFuture<AnsibleJobResult> future = CompletableFuture.supplyAsync(() -> {
            try { return cancelRunner.run(AnsibleAction.DEPLOY, source, checksum,
                    List.of(host), new AnsibleEventListener() {}); }
            catch (IOException exception) { throw new RuntimeException(exception); }
        });
        Thread.sleep(300);
        check(cancelRunner.cancel(), "active Ansible process accepts cancellation");
        AnsibleJobResult cancelled = future.get(5, TimeUnit.SECONDS);
        check(cancelled.cancelled() && cancelled.hosts().get(host.inventoryName()).status()
                == AnsibleHostStatus.CANCELLED, "cancelled job is reported per host");
    }

    private static void rbacTest(Path root, Path config) throws Exception {
        AuthorizationContext viewer = new AuthorizationContext("viewer", UserRole.VIEWER, Set.of());
        AnsibleRunner unused = runner(root.resolve("viewer-work"), fakeVault(root), fakeVaultPassword(root),
                root.resolve("viewer-state"), "normal", Duration.ofSeconds(2));
        try (AnsibleService service = new AnsibleService(new AnsibleInventoryStore(config), unused,
                viewer, new AuditLogger(root.resolve("viewer-audit.jsonl")), root.resolve("viewer-source"))) {
            expectFailure(() -> service.generateFile("denied"), "VIEWER cannot generate Ansible source");
            expectFailure(() -> service.deploy(Set.of("linux_01")), "VIEWER cannot deploy");
        }
    }

    private static void validationTests() throws Exception {
        expectFailure(() -> new AnsibleManagedHost("bad;host", "bad", "", AnsiblePlatform.LINUX,
                "127.0.0.1", 22, AnsibleConnectionType.SSH,
                "/tmp/lan_monitoring_sync.txt", true), "inventory command injection is rejected");
        expectFailure(() -> new AnsibleManagedHost("linux", "bad", "", AnsiblePlatform.LINUX,
                "127.0.0.1", 22, AnsibleConnectionType.SSH,
                "/tmp/../lan_monitoring_sync.txt", true), "destination traversal is rejected");
        expectFailure(() -> new AnsibleManagedHost("linux", "bad", "", AnsiblePlatform.LINUX,
                "127.0.0.1", 22, AnsibleConnectionType.PSRP,
                "/tmp/lan_monitoring_sync.txt", true), "Linux PSRP is rejected");
    }

    private static void playbookStructureTests() throws IOException {
        String linux = Files.readString(Path.of("ansible/playbooks/sync_linux.yml"));
        String windows = Files.readString(Path.of("ansible/playbooks/sync_windows.yml"));
        check(linux.contains("ansible.builtin.copy:") && linux.contains("checksum_algorithm: sha256")
                && linux.contains("tags: [verify]"), "Linux playbook uses copy and SHA-256 verification");
        check(windows.contains("ansible.windows.win_copy:") && windows.contains("ansible.windows.win_stat:")
                && windows.contains("checksum_algorithm: sha256"),
                "Windows playbook uses win_copy/win_stat and SHA-256 verification");
    }

    private static Path writeConfiguration(Path root) throws IOException {
        Path path = root.resolve("managed-hosts.json");
        String json = """
                {"version":1,"hosts":[
                  {"inventoryName":"linux_01","displayName":"Linux 01","applicationClientId":"java-1","platform":"LINUX","address":"192.0.2.11","port":22,"connection":"SSH","destinationPath":"/opt/lan/lan_monitoring_sync.txt","enabled":true},
                  {"inventoryName":"linux_02","displayName":"Linux 02","applicationClientId":"java-2","platform":"LINUX","address":"192.0.2.12","port":22,"connection":"SSH","destinationPath":"/opt/lan/lan_monitoring_sync.txt","enabled":true},
                  {"inventoryName":"windows_01","displayName":"Windows 01","applicationClientId":"java-3","platform":"WINDOWS","address":"win01.example.invalid","port":5986,"connection":"PSRP","destinationPath":"C:\\\\ProgramData\\\\LANMonitoringServer\\\\lan_monitoring_sync.txt","enabled":true},
                  {"inventoryName":"linux_offline","displayName":"Offline Linux","applicationClientId":"","platform":"LINUX","address":"192.0.2.99","port":22,"connection":"SSH","destinationPath":"/opt/lan/lan_monitoring_sync.txt","enabled":true},
                  {"inventoryName":"windows_denied","displayName":"Denied Windows","applicationClientId":"","platform":"WINDOWS","address":"win02.example.invalid","port":5986,"connection":"WINRM","destinationPath":"C:\\\\ProgramData\\\\LANMonitoringServer\\\\lan_monitoring_sync.txt","enabled":true},
                  {"inventoryName":"disabled_windows","displayName":"Disabled Windows","applicationClientId":"","platform":"WINDOWS","address":"win03.example.invalid","port":22,"connection":"SSH","destinationPath":"C:\\\\ProgramData\\\\LANMonitoringServer\\\\lan_monitoring_sync.txt","enabled":false}
                ]}
                """;
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return path;
    }

    private static AnsibleRunner runner(Path workspace, Path vault, Path password,
            Path state, String mode, Duration timeout) {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = absoluteClasspath();
        List<String> prefix = List.of(java.toString(), "-cp", classpath,
                "-Dfake.ansible.mode=" + mode, "-Dfake.ansible.state=" + state.toAbsolutePath(),
                FakeAnsiblePlaybook.class.getName());
        return new AnsibleRunner(Path.of("ansible"), workspace, prefix, timeout, vault, password, false);
    }

    private static String absoluteClasspath() {
        String[] entries = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<String> absolute = new ArrayList<>();
        for (String entry : entries) absolute.add(Path.of(entry).toAbsolutePath().normalize().toString());
        return String.join(File.pathSeparator, absolute);
    }
    private static Path fakeVault(Path root) throws IOException {
        Path path = root.resolve("test-vault.yml");
        if (!Files.exists(path)) Files.writeString(path, "$ANSIBLE_VAULT;1.1;AES256\n00\n");
        return path;
    }
    private static Path fakeVaultPassword(Path root) throws IOException {
        Path path = root.resolve("test-vault-password");
        if (!Files.exists(path)) Files.writeString(path, "test-process-only");
        return path;
    }
    private static void expectFailure(Action action, String description) throws Exception {
        try { action.run(); throw new AssertionError("Expected failure: " + description); }
        catch (IOException | IllegalArgumentException | SecurityException expected) {}
    }
    private static void expectFutureFailure(CompletableFuture<?> future, String description)
            throws Exception {
        try { future.get(5, TimeUnit.SECONDS); throw new AssertionError("Expected failure: " + description); }
        catch (java.util.concurrent.ExecutionException expected) {}
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
