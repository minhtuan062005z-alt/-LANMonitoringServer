package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.AlertSeverity;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Lightweight JSON persistence with atomic replacement; no database runtime is required. */
public final class FileAlertStore implements AlertStore {
    private final Path path;

    public FileAlertStore(Path path) { this.path = path.toAbsolutePath().normalize(); }

    public static FileAlertStore defaultStore() {
        String configured = System.getProperty("lanmonitoring.alert.store", "").trim();
        Path location = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lanmonitoringserver", "alerts-v1.json")
                : Path.of(configured);
        return new FileAlertStore(location);
    }

    public Path path() { return path; }

    @Override
    public synchronized AlertStoreSnapshot load() throws IOException {
        if (!Files.exists(path)) return AlertStoreSnapshot.empty();
        try {
            Object parsed = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) throw new IOException("Alert store root is not an object");
            if (integer(root, "version") != 1L) {
                throw new IOException("Unsupported alert store version");
            }
            List<AlertRule> rules = new ArrayList<>();
            for (Object item : list(root.get("rules"))) rules.add(rule(map(item)));
            List<AlertRecord> alerts = new ArrayList<>();
            for (Object item : list(root.get("alerts"))) alerts.add(alert(map(item)));
            return new AlertStoreSnapshot(rules, alerts);
        } catch (ProtocolException | IllegalArgumentException exception) {
            throw new IOException("Invalid alert store: " + exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized void save(AlertStoreSnapshot snapshot) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("rules", snapshot.rules().stream().map(FileAlertStore::mapRule).toList());
        root.put("alerts", snapshot.alerts().stream().map(FileAlertStore::mapAlert).toList());
        String json;
        try { json = JsonCodec.stringify(root); }
        catch (ProtocolException exception) { throw new IOException(exception); }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> mapRule(AlertRule value) {
        return mapOf("metric", value.metric().name(), "enabled", value.enabled(),
                "warningThreshold", value.warningThreshold(),
                "criticalThreshold", value.criticalThreshold(),
                "durationMillis", value.durationMillis(),
                "hysteresisPercent", value.hysteresisPercent(),
                "cooldownMillis", value.cooldownMillis());
    }

    private static Map<String, Object> mapAlert(AlertRecord value) {
        return mapOf("id", value.id(), "clientId", value.clientId(),
                "hostname", value.hostname(), "metric", value.metric().name(),
                "severity", value.severity().name(), "value", value.value(),
                "threshold", value.threshold(), "status", value.status().name(),
                "deliveryStatus", value.deliveryStatus().name(),
                "createdAt", value.createdAt(), "acknowledgedAt", value.acknowledgedAt(),
                "resolvedAt", value.resolvedAt(), "updatedAt", value.updatedAt(),
                "deliveryRequestId", value.deliveryRequestId(),
                "templateName", value.templateName(), "title", value.title(),
                "content", value.content());
    }

    private static AlertRule rule(Map<?, ?> map) throws IOException {
        return new AlertRule(enumValue(map, "metric", AlertMetric.class),
                bool(map, "enabled"), number(map, "warningThreshold"),
                number(map, "criticalThreshold"), integer(map, "durationMillis"),
                number(map, "hysteresisPercent"), integer(map, "cooldownMillis"));
    }

    private static AlertRecord alert(Map<?, ?> map) throws IOException {
        return new AlertRecord(text(map, "id"), text(map, "clientId"), text(map, "hostname"),
                enumValue(map, "metric", AlertMetric.class),
                enumValue(map, "severity", AlertSeverity.class), number(map, "value"),
                number(map, "threshold"), enumValue(map, "status", AlertStatus.class),
                enumValue(map, "deliveryStatus", AlertDeliveryStatus.class),
                integer(map, "createdAt"), optionalInteger(map, "acknowledgedAt"),
                optionalInteger(map, "resolvedAt"), integer(map, "updatedAt"),
                optionalText(map, "deliveryRequestId"), optionalText(map, "templateName"),
                optionalText(map, "title"), optionalText(map, "content"));
    }

    private static Map<?, ?> map(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> result)) throw new IOException("Expected object");
        return result;
    }
    private static List<?> list(Object value) throws IOException {
        if (!(value instanceof List<?> result)) throw new IOException("Expected array");
        return result;
    }
    private static String text(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IOException("Invalid " + key);
        return text;
    }
    private static String optionalText(Map<?, ?> map, String key) {
        return map.get(key) instanceof String text ? text : null;
    }
    private static boolean bool(Map<?, ?> map, String key) throws IOException {
        if (!(map.get(key) instanceof Boolean value)) throw new IOException("Invalid " + key);
        return value;
    }
    private static double number(Map<?, ?> map, String key) throws IOException {
        if (!(map.get(key) instanceof Number value)) throw new IOException("Invalid " + key);
        double result = value.doubleValue();
        if (!Double.isFinite(result)) throw new IOException("Invalid " + key);
        return result;
    }
    private static long integer(Map<?, ?> map, String key) throws IOException {
        if (!(map.get(key) instanceof Number value)) throw new IOException("Invalid " + key);
        double candidate = value.doubleValue();
        long result = value.longValue();
        if (!Double.isFinite(candidate) || candidate != result) throw new IOException("Invalid " + key);
        return result;
    }
    private static Long optionalInteger(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        return value == null ? null : integer(map, key);
    }
    private static <E extends Enum<E>> E enumValue(Map<?, ?> map, String key,
            Class<E> type) throws IOException {
        try { return Enum.valueOf(type, text(map, key)); }
        catch (IllegalArgumentException exception) { throw new IOException("Invalid " + key, exception); }
    }
    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
