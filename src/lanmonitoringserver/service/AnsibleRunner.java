package lanmonitoringserver.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lanmonitoringserver.model.AnsibleAction;
import lanmonitoringserver.model.AnsibleHostResult;
import lanmonitoringserver.model.AnsibleHostStatus;
import lanmonitoringserver.model.AnsibleJobResult;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.model.AnsiblePlatform;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Executes only the bundled playbooks using argument-safe ProcessBuilder calls. */
public final class AnsibleRunner {
    private static final int MAX_CAPTURE_CHARS = 2 * 1024 * 1024;
    private static final Pattern MARKER = Pattern.compile(
            "LANMONITORING_RESULT\\|host=([A-Za-z_][A-Za-z0-9_-]{0,63})\\|status=SUCCESS"
            + "\\|changed=(true|false|True|False)\\|sha256=([0-9a-fA-F]{64})");
    private static final Pattern RECAP = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_-]{0,63})\\s*:\\s*ok=(\\d+)\\s+changed=(\\d+)"
            + "\\s+unreachable=(\\d+)\\s+failed=(\\d+).*$");

    private final Path projectDirectory;
    private final Path workspaceDirectory;
    private final List<String> commandPrefix;
    private final Duration timeout;
    private final Path vaultFile;
    private final Path vaultPasswordFile;
    private final boolean wslPaths;
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    public AnsibleRunner(Path projectDirectory, Path workspaceDirectory, List<String> commandPrefix,
            Duration timeout, Path vaultFile, Path vaultPasswordFile, boolean wslPaths) {
        this.projectDirectory = projectDirectory.toAbsolutePath().normalize();
        this.workspaceDirectory = workspaceDirectory.toAbsolutePath().normalize();
        if (commandPrefix == null || commandPrefix.isEmpty()
                || commandPrefix.stream().anyMatch(value -> value == null || value.isBlank()
                        || value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r"))) {
            throw new IllegalArgumentException("Invalid Ansible command prefix");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        if (this.timeout.toMillis() < 100) throw new IllegalArgumentException("Timeout too short");
        this.vaultFile = normalize(vaultFile);
        this.vaultPasswordFile = normalize(vaultPasswordFile);
        this.wslPaths = wslPaths;
    }

    public static AnsibleRunner fromSystemProperties() {
        String configuredProject = System.getProperty("lanmonitoring.ansible.project", "").trim();
        Path project = configuredProject.isEmpty() ? defaultProjectDirectory() : Path.of(configuredProject);
        Path workspace = Path.of(System.getProperty("lanmonitoring.ansible.workspace",
                Path.of(System.getProperty("user.home"), ".lanmonitoringserver", "ansible").toString()));
        String mode = System.getProperty("lanmonitoring.ansible.mode", "native").trim();
        List<String> prefix = new ArrayList<>();
        boolean wsl = "wsl".equalsIgnoreCase(mode);
        if (wsl) {
            prefix.add("wsl.exe");
            String distribution = System.getProperty("lanmonitoring.ansible.wsl.distribution", "").trim();
            if (!distribution.isEmpty()) { prefix.add("-d"); prefix.add(distribution); }
            prefix.add("--");
            prefix.add("ansible-playbook");
        } else {
            prefix.add(System.getProperty("lanmonitoring.ansible.executable", "ansible-playbook").trim());
        }
        long timeoutSeconds = Long.getLong("lanmonitoring.ansible.timeout.seconds", 300L);
        Path vault = optionalProperty("lanmonitoring.ansible.vault.file");
        Path password = optionalProperty("lanmonitoring.ansible.vault.password.file");
        return new AnsibleRunner(project, workspace, prefix,
                Duration.ofSeconds(Math.max(1, timeoutSeconds)), vault, password, wsl);
    }

    public AnsibleJobResult run(AnsibleAction action, Path source, String sha256,
            List<AnsibleManagedHost> hosts, AnsibleEventListener listener) throws IOException {
        if (hosts == null || hosts.isEmpty()) throw new IllegalArgumentException("Select managed hosts");
        if (activeProcess.get() != null) throw new IllegalStateException("Ansible job is already running");
        validateCredentials();
        ensureSafeWorkspace();
        String jobId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        Path jobDirectory = Files.createDirectory(workspaceDirectory.resolve("job-" + jobId));
        Path inventory = jobDirectory.resolve("inventory.yml");
        AnsibleInventoryWriter.write(inventory, hosts);
        Path variables = jobDirectory.resolve("job-vars.json");
        writeVariables(variables, source, sha256);
        Map<String, AnsibleHostResult> results = new LinkedHashMap<>();
        for (AnsibleManagedHost host : hosts) results.put(host.inventoryName(), pending(host));
        StringBuilder log = new StringBuilder();
        cancelRequested.set(false);
        boolean timedOut = false;
        boolean cancelled = false;
        int exitCode = 0;
        try {
            Map<AnsiblePlatform, List<AnsibleManagedHost>> groups = new EnumMap<>(AnsiblePlatform.class);
            for (AnsibleManagedHost host : hosts) groups.computeIfAbsent(host.platform(), key -> new ArrayList<>()).add(host);
            for (Map.Entry<AnsiblePlatform, List<AnsibleManagedHost>> group : groups.entrySet()) {
                for (AnsibleManagedHost host : group.getValue()) {
                    AnsibleHostResult running = new AnsibleHostResult(host.inventoryName(),
                            host.platform(), AnsibleHostStatus.RUNNING, false, "", "Ansible running");
                    results.put(host.inventoryName(), running);
                    listener.onHostResult(running);
                }
                Execution execution = execute(action, group.getKey(), group.getValue(), inventory,
                        variables, listener, log);
                if (execution.exitCode != 0) exitCode = execution.exitCode;
                timedOut |= execution.timedOut;
                cancelled |= execution.cancelled;
                parseResults(group.getValue(), execution.output, execution.timedOut,
                        execution.cancelled, execution.exitCode, results, listener);
                if (timedOut || cancelled) break;
            }
            if (timedOut || cancelled) {
                for (AnsibleManagedHost host : hosts) {
                    if (results.get(host.inventoryName()).status() == AnsibleHostStatus.PENDING) {
                        AnsibleHostStatus status = timedOut ? AnsibleHostStatus.TIMED_OUT
                                : AnsibleHostStatus.CANCELLED;
                        AnsibleHostResult result = new AnsibleHostResult(host.inventoryName(),
                                host.platform(), status, false, "", status.name());
                        results.put(host.inventoryName(), result);
                        listener.onHostResult(result);
                    }
                }
            }
        } finally {
            activeProcess.set(null);
            cancelRequested.set(false);
        }
        return new AnsibleJobResult(jobId, action, sha256, started, System.currentTimeMillis(),
                exitCode, timedOut, cancelled, results, log.toString());
    }

    public boolean cancel() {
        cancelRequested.set(true);
        Process process = activeProcess.get();
        if (process == null) return false;
        process.destroy();
        return true;
    }

    private Execution execute(AnsibleAction action, AnsiblePlatform platform,
            List<AnsibleManagedHost> hosts, Path inventory, Path variables,
            AnsibleEventListener listener, StringBuilder combinedLog) throws IOException {
        List<String> command = new ArrayList<>(commandPrefix);
        command.add("-i"); command.add(controlPath(inventory));
        Path playbook = projectDirectory.resolve(platform == AnsiblePlatform.LINUX
                ? "playbooks/sync_linux.yml" : "playbooks/sync_windows.yml");
        if (!Files.isRegularFile(playbook)) throw new IOException("Bundled playbook is missing: " + playbook);
        command.add(controlPath(playbook));
        command.add("--limit");
        command.add(hosts.stream().map(AnsibleManagedHost::inventoryName)
                .collect(java.util.stream.Collectors.joining(",")));
        command.add("--extra-vars"); command.add("@" + controlPath(variables));
        command.add("--extra-vars"); command.add("@" + controlPath(vaultFile));
        command.add("--vault-password-file"); command.add(controlPath(vaultPasswordFile));
        if (action == AnsibleAction.VERIFY) { command.add("--tags"); command.add("verify"); }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectDirectory.toFile());
        builder.environment().put("ANSIBLE_NOCOLOR", "1");
        builder.environment().put("PYTHONUNBUFFERED", "1");
        Process process;
        try { process = builder.start(); }
        catch (IOException exception) { throw new IOException("ANSIBLE_PROCESS_START_FAILED: "
                + commandPrefix.get(0) + ": " + exception.getMessage(), exception); }
        if (!activeProcess.compareAndSet(null, process)) {
            process.destroyForcibly();
            throw new IllegalStateException("Ansible job is already running");
        }
        StringBuilder output = new StringBuilder();
        Thread stdout = drain(process.getInputStream(), "stdout", output, combinedLog, listener);
        Thread stderr = drain(process.getErrorStream(), "stderr", output, combinedLog, listener);
        boolean timedOut = false;
        boolean cancelled = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelRequested.get()) { cancelled = true; break; }
                if (System.nanoTime() >= deadline) { timedOut = true; break; }
            }
            cancelled |= cancelRequested.get();
            if (timedOut || cancelled) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            stdout.join(2_000); stderr.join(2_000);
            return new Execution(process.isAlive() ? -1 : process.exitValue(), timedOut,
                    cancelled, output.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Ansible execution interrupted", exception);
        } finally {
            activeProcess.compareAndSet(process, null);
        }
    }

    private static Thread drain(InputStream stream, String channel, StringBuilder output,
            StringBuilder combined, AnsibleEventListener listener) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) { appendBounded(output, line + "\n"); }
                    synchronized (combined) { appendBounded(combined, "[" + channel + "] " + line + "\n"); }
                    listener.onLog("[" + channel + "] " + line);
                }
            } catch (IOException exception) {
                listener.onLog("[" + channel + "] read failed: " + exception.getMessage());
            }
        }, "ansible-" + channel);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void parseResults(List<AnsibleManagedHost> hosts, String output,
            boolean timedOut, boolean cancelled, int exitCode,
            Map<String, AnsibleHostResult> results, AnsibleEventListener listener) {
        Map<String, Marker> markers = new LinkedHashMap<>();
        Map<String, Recap> recaps = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher marker = MARKER.matcher(line);
            while (marker.find()) markers.put(marker.group(1), new Marker(
                    Boolean.parseBoolean(marker.group(2).toLowerCase(java.util.Locale.ROOT)),
                    marker.group(3).toLowerCase(java.util.Locale.ROOT)));
            Matcher recap = RECAP.matcher(line.trim());
            if (recap.matches()) recaps.put(recap.group(1), new Recap(
                    Integer.parseInt(recap.group(3)), Integer.parseInt(recap.group(4)),
                    Integer.parseInt(recap.group(5))));
        }
        for (AnsibleManagedHost host : hosts) {
            Marker marker = markers.get(host.inventoryName());
            Recap recap = recaps.get(host.inventoryName());
            AnsibleHostStatus status;
            String detail;
            if (cancelled) { status = AnsibleHostStatus.CANCELLED; detail = "Job cancelled"; }
            else if (timedOut) { status = AnsibleHostStatus.TIMED_OUT; detail = "Job timeout"; }
            else if (recap != null && recap.unreachable > 0) {
                status = AnsibleHostStatus.UNREACHABLE; detail = "Ansible host unreachable";
            } else if (recap != null && recap.failed > 0) {
                status = AnsibleHostStatus.FAILED; detail = "Ansible task failed";
            } else if (marker != null) {
                status = AnsibleHostStatus.SUCCESS; detail = marker.changed ? "Synchronized" : "Already synchronized";
            } else {
                status = AnsibleHostStatus.FAILED;
                detail = "No verified host result (ansible exit " + exitCode + ")";
            }
            AnsibleHostResult result = new AnsibleHostResult(host.inventoryName(), host.platform(),
                    status, marker != null && marker.changed, marker == null ? "" : marker.sha256, detail);
            results.put(host.inventoryName(), result);
            listener.onHostResult(result);
        }
    }

    private void validateCredentials() throws IOException {
        if (vaultFile == null || !Files.isRegularFile(vaultFile)) {
            throw new IOException("Configure lanmonitoring.ansible.vault.file outside the repository");
        }
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(vaultFile, StandardCharsets.UTF_8)) {
            firstLine = reader.readLine();
        }
        if (firstLine == null || !firstLine.startsWith("$ANSIBLE_VAULT;")) {
            throw new IOException("Credential vars file must be encrypted with ansible-vault");
        }
        if (vaultPasswordFile == null || !Files.isRegularFile(vaultPasswordFile)) {
            throw new IOException("Configure a protected lanmonitoring.ansible.vault.password.file");
        }
    }

    private void ensureSafeWorkspace() throws IOException {
        Files.createDirectories(workspaceDirectory);
        if (Files.isSymbolicLink(workspaceDirectory)
                || !workspaceDirectory.toRealPath().equals(
                        workspaceDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Ansible workspace cannot use symbolic links");
        }
    }

    private void writeVariables(Path path, Path source, String sha256) throws IOException {
        try {
            Files.writeString(path, JsonCodec.stringify(Map.of(
                    "lan_sync_source", controlPath(source.toAbsolutePath().normalize()),
                    "lan_sync_sha256", sha256)), StandardCharsets.UTF_8);
        } catch (ProtocolException exception) { throw new IOException(exception); }
    }

    private String controlPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (!wslPaths) return value;
        if (value.length() >= 3 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':') {
            return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2).replace('\\', '/');
        }
        throw new IllegalArgumentException("WSL mode requires a drive-letter path: " + value);
    }

    private static AnsibleHostResult pending(AnsibleManagedHost host) {
        return new AnsibleHostResult(host.inventoryName(), host.platform(),
                AnsibleHostStatus.PENDING, false, "", "Queued");
    }
    private static void appendBounded(StringBuilder target, String text) {
        int remaining = MAX_CAPTURE_CHARS - target.length();
        if (remaining > 0) target.append(text, 0, Math.min(remaining, text.length()));
    }
    private static Path normalize(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
    private static Path optionalProperty(String name) {
        String value = System.getProperty(name, "").trim();
        return value.isEmpty() ? null : Path.of(value);
    }
    private static Path defaultProjectDirectory() {
        try {
            Path codeLocation = Path.of(AnsibleRunner.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(codeLocation) && codeLocation.getParent() != null) {
                return codeLocation.getParent().resolve("ansible");
            }
        } catch (Exception ignored) {}
        return Path.of("ansible");
    }
    private record Marker(boolean changed, String sha256) {}
    private record Recap(int changed, int unreachable, int failed) {}
    private record Execution(int exitCode, boolean timedOut, boolean cancelled, String output) {}
}
