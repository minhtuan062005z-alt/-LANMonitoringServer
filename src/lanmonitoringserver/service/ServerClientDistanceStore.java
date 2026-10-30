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
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Atomic, Server-owned persistence for physical positions and manual measurements. */
public final class ServerClientDistanceStore {
    private final Path path;
    private SpatialPosition serverPosition;
    private final Map<String, SpatialPosition> clientPositions = new LinkedHashMap<>();
    private final Map<String, ManualMeasurement> manualMeasurements = new LinkedHashMap<>();

    public ServerClientDistanceStore(Path path) throws IOException {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        load();
    }

    public static ServerClientDistanceStore defaultStore() throws IOException {
        String configured = System.getProperty("lanmonitoring.distance.store", "").trim();
        Path location = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lanmonitoringserver",
                        "server-client-distances-v1.json")
                : Path.of(configured);
        return new ServerClientDistanceStore(location);
    }

    public static ServerClientDistanceStore inMemory() {
        try { return new ServerClientDistanceStore(null); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized SpatialPosition serverPosition() { return serverPosition; }
    public synchronized SpatialPosition clientPosition(String clientId) {
        return clientPositions.get(clientId(clientId));
    }
    public synchronized ManualMeasurement manualMeasurement(String clientId) {
        return manualMeasurements.get(clientId(clientId));
    }
    public Path path() { return path; }

    public synchronized void setServerPosition(SpatialPosition position) throws IOException {
        if (position == null) throw new IllegalArgumentException("Server position is required");
        SpatialPosition previous = serverPosition;
        serverPosition = position;
        try { save(); } catch (IOException exception) { serverPosition = previous; throw exception; }
    }

    public synchronized void clearServerPosition() throws IOException {
        SpatialPosition previous = serverPosition;
        serverPosition = null;
        try { save(); } catch (IOException exception) { serverPosition = previous; throw exception; }
    }

    public synchronized void setClientPosition(String clientId, SpatialPosition position)
            throws IOException {
        String id = clientId(clientId);
        if (position == null) throw new IllegalArgumentException("Client position is required");
        SpatialPosition previous = clientPositions.put(id, position);
        try { save(); } catch (IOException exception) { restore(clientPositions, id, previous); throw exception; }
    }

    public synchronized void setManualMeasurement(String clientId, double meters,
            Double estimatedErrorMeters, long measuredAt) throws IOException {
        String id = clientId(clientId);
        ManualMeasurement value = new ManualMeasurement(meters, estimatedErrorMeters, measuredAt);
        ManualMeasurement previous = manualMeasurements.put(id, value);
        try { save(); } catch (IOException exception) { restore(manualMeasurements, id, previous); throw exception; }
    }

    /** Clears both Client position and manual measurement, but never another Client's data. */
    public synchronized void clearClient(String clientId) throws IOException {
        String id = clientId(clientId);
        SpatialPosition oldPosition = clientPositions.remove(id);
        ManualMeasurement oldManual = manualMeasurements.remove(id);
        try { save(); }
        catch (IOException exception) {
            restore(clientPositions, id, oldPosition);
            restore(manualMeasurements, id, oldManual);
            throw exception;
        }
    }

    private void load() throws IOException {
        if (path == null || !Files.exists(path)) return;
        try {
            Object parsed = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)
                    || !(root.get("version") instanceof Number version) || version.intValue() != 1) {
                throw new IOException("Invalid Server-Client distance store");
            }
            Object server = root.get("serverPosition");
            if (server != null) serverPosition = parsePosition(server);
            Object clients = root.get("clients");
            if (!(clients instanceof Iterable<?> entries)) throw new IOException("Invalid clients list");
            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> map)) throw new IOException("Invalid Client distance entry");
                String id = clientId(text(map, "clientId"));
                Object position = map.get("position");
                Object manual = map.get("manualMeasurement");
                if (position != null) clientPositions.put(id, parsePosition(position));
                if (manual != null) manualMeasurements.put(id, parseManual(manual));
            }
        } catch (ProtocolException | IllegalArgumentException exception) {
            throw new IOException("Invalid Server-Client distance JSON", exception);
        }
    }

    private void save() throws IOException {
        if (path == null) return;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        List<Object> clients = new ArrayList<>();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(clientPositions.keySet());
        ids.addAll(manualMeasurements.keySet());
        for (String id : ids) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("clientId", id);
            SpatialPosition position = clientPositions.get(id);
            ManualMeasurement manual = manualMeasurements.get(id);
            if (position != null) entry.put("position", positionMap(position));
            if (manual != null) entry.put("manualMeasurement", manualMap(manual));
            clients.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("serverPosition", serverPosition == null ? null : positionMap(serverPosition));
        root.put("clients", clients);
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

    private static Map<String, Object> positionMap(SpatialPosition value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("referencePoint", value.referencePoint());
        result.put("xMeters", value.xMeters());
        result.put("yMeters", value.yMeters());
        result.put("zMeters", value.zMeters());
        result.put("lastUpdated", value.lastUpdated());
        return result;
    }

    private static Map<String, Object> manualMap(ManualMeasurement value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("distanceMeters", value.distanceMeters());
        result.put("estimatedErrorMeters", value.estimatedErrorMeters());
        result.put("measuredAt", value.measuredAt());
        return result;
    }

    private static SpatialPosition parsePosition(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("Invalid position");
        Object z = map.get("zMeters");
        return new SpatialPosition(text(map, "referencePoint"), number(map, "xMeters"),
                number(map, "yMeters"), z == null ? null : finiteNumber(z, "zMeters"),
                integer(map, "lastUpdated"));
    }

    private static ManualMeasurement parseManual(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("Invalid manual measurement");
        Object error = map.get("estimatedErrorMeters");
        return new ManualMeasurement(number(map, "distanceMeters"),
                error == null ? null : finiteNumber(error, "estimatedErrorMeters"),
                integer(map, "measuredAt"));
    }

    private static String text(Map<?, ?> map, String key) throws IOException {
        if (!(map.get(key) instanceof String value)) throw new IOException("Invalid " + key);
        return value;
    }
    private static double number(Map<?, ?> map, String key) throws IOException {
        return finiteNumber(map.get(key), key);
    }
    private static double finiteNumber(Object value, String key) throws IOException {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IOException("Invalid " + key);
        }
        return number.doubleValue();
    }
    private static long integer(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IOException("Invalid " + key);
        }
        return number.longValue();
    }
    private static String clientId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 128 || id.indexOf('\0') >= 0
                || id.indexOf('\r') >= 0 || id.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid Client ID");
        }
        return id;
    }
    private static <T> void restore(Map<String, T> values, String id, T previous) {
        if (previous == null) values.remove(id); else values.put(id, previous);
    }

    public record ManualMeasurement(double distanceMeters, Double estimatedErrorMeters,
            long measuredAt) {
        public ManualMeasurement {
            if (!Double.isFinite(distanceMeters) || distanceMeters < 0
                    || (estimatedErrorMeters != null
                    && (!Double.isFinite(estimatedErrorMeters) || estimatedErrorMeters < 0))) {
                throw new IllegalArgumentException("Manual distance and error must be finite and non-negative");
            }
            if (measuredAt <= 0) throw new IllegalArgumentException("measuredAt must be positive");
        }
    }
}
