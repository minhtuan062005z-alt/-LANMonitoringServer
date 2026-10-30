package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lanmonitoringserver.model.AnsibleConnectionType;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.model.AnsiblePlatform;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Loads the explicit Ansible allowlist. It never derives inventory from online Java Clients. */
public final class AnsibleInventoryStore {
    private final Path path;

    public AnsibleInventoryStore(Path path) { this.path = path.toAbsolutePath().normalize(); }

    public static AnsibleInventoryStore defaultStore() {
        String configured = System.getProperty("lanmonitoring.ansible.hosts", "").trim();
        Path path = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lanmonitoringserver",
                        "ansible-managed-hosts-v1.json") : Path.of(configured);
        return new AnsibleInventoryStore(path);
    }

    public Path path() { return path; }

    public List<AnsibleManagedHost> load() throws IOException {
        if (!Files.exists(path)) return List.of();
        try {
            Object parsed = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root) || number(root, "version") != 1
                    || !(root.get("hosts") instanceof List<?> values)) {
                throw new IOException("Invalid Ansible managed-host configuration");
            }
            List<AnsibleManagedHost> result = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> host)) throw new IOException("Invalid Ansible host entry");
                AnsibleManagedHost item = new AnsibleManagedHost(string(host, "inventoryName"),
                        string(host, "displayName"), optional(host, "applicationClientId"),
                        enumValue(AnsiblePlatform.class, host, "platform"), string(host, "address"),
                        Math.toIntExact(number(host, "port")),
                        enumValue(AnsibleConnectionType.class, host, "connection"),
                        string(host, "destinationPath"), bool(host, "enabled"));
                if (!names.add(item.inventoryName())) throw new IOException("Duplicate Ansible inventory name");
                result.add(item);
            }
            return List.copyOf(result);
        } catch (ProtocolException | IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid Ansible managed-host configuration: "
                    + exception.getMessage(), exception);
        }
    }

    private static String string(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text)) throw new IOException("Missing string: " + key);
        return text;
    }
    private static String optional(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new IOException("Invalid string: " + key);
        return text;
    }
    private static long number(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IOException("Invalid integer: " + key);
        }
        return number.longValue();
    }
    private static boolean bool(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Boolean result)) throw new IOException("Invalid boolean: " + key);
        return result;
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, Map<?, ?> map, String key)
            throws IOException {
        try { return Enum.valueOf(type, string(map, key).toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IOException("Invalid enum: " + key, exception); }
    }
}
