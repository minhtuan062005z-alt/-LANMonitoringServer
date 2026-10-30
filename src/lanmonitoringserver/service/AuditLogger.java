package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Append-only JSON-lines audit trail for administrative actions. */
public final class AuditLogger {
    private final Path path;

    public AuditLogger(Path path) { this.path = path.toAbsolutePath().normalize(); }

    public static AuditLogger defaultLogger() {
        String configured = System.getProperty("lanmonitoring.audit.log", "").trim();
        Path path = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lanmonitoringserver", "audit.jsonl")
                : Path.of(configured);
        return new AuditLogger(path);
    }

    public synchronized void record(String principal, String role, String action,
            String target, String outcome, String detail) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("principal", principal);
        event.put("role", role);
        event.put("action", action);
        event.put("target", target == null ? "" : target);
        event.put("outcome", outcome);
        event.put("detail", detail == null ? "" : detail);
        try {
            Files.writeString(path, JsonCodec.stringify(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (ProtocolException exception) {
            throw new IOException(exception);
        }
    }
}
