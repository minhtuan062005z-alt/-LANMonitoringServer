package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Small server-owned permission store. Clients receive a copy but cannot authoritatively change it. */
public final class ClientPermissionStore {
    private final Path path;
    private final Map<String, ClientPermissions> permissions = new LinkedHashMap<>();

    public ClientPermissionStore(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        load();
    }

    public static ClientPermissionStore defaultStore() throws IOException {
        String configured = System.getProperty("lanmonitoring.permission.store", "").trim();
        Path path = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lanmonitoringserver", "client-permissions-v1.json")
                : Path.of(configured);
        return new ClientPermissionStore(path);
    }

    public synchronized ClientPermissions get(String clientId) {
        return permissions.getOrDefault(clientId, ClientPermissions.defaults());
    }

    public synchronized Map<String, ClientPermissions> snapshot() { return Map.copyOf(permissions); }

    public synchronized void put(String clientId, ClientPermissions value) throws IOException {
        if (clientId == null || clientId.isBlank() || value == null) {
            throw new IllegalArgumentException("clientId and permissions are required");
        }
        ClientPermissions previous = permissions.put(clientId, value);
        try {
            save();
        } catch (IOException exception) {
            if (previous == null) permissions.remove(clientId); else permissions.put(clientId, previous);
            throw exception;
        }
    }

    private void load() throws IOException {
        if (!Files.exists(path)) return;
        try {
            Object parsed = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("version") instanceof Number version)
                    || version.intValue() != 1 || !(root.get("clients") instanceof Map<?, ?> clients)) {
                throw new IOException("Invalid client permission store");
            }
            for (Map.Entry<?, ?> entry : clients.entrySet()) {
                if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof Map<?, ?> value)) {
                    throw new IOException("Invalid client permission entry");
                }
                permissions.put(id, new ClientPermissions(bool(value, "receiveFiles"),
                        bool(value, "receiveAlerts"), bool(value, "exportSystemInformation")));
            }
        } catch (ProtocolException exception) {
            throw new IOException("Invalid client permission JSON", exception);
        }
    }

    private void save() throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Object> clients = new LinkedHashMap<>();
        permissions.forEach((id, value) -> clients.put(id, Map.of(
                "receiveFiles", value.receiveFiles(), "receiveAlerts", value.receiveAlerts(),
                "exportSystemInformation", value.exportSystemInformation())));
        String json;
        try { json = JsonCodec.stringify(Map.of("version", 1, "clients", clients)); }
        catch (ProtocolException exception) { throw new IOException(exception); }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean bool(Map<?, ?> map, String key) throws IOException {
        if (!(map.get(key) instanceof Boolean value)) throw new IOException("Invalid " + key);
        return value;
    }
}
