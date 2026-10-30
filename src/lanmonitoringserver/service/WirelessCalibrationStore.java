package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import lanmonitoringserver.model.WirelessCalibration;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Persists calibration by pseudonymous AP target ID; raw SSID/BSSID are never stored. */
public final class WirelessCalibrationStore {
    private final Path path;
    private final Map<String, StoredCalibration> calibrations = new LinkedHashMap<>();

    public WirelessCalibrationStore(Path path) throws IOException {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        load();
    }

    public static WirelessCalibrationStore defaultStore() throws IOException {
        String configured = System.getProperty("lanmonitoring.wifi.calibration.store", "").trim();
        Path location = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lanmonitoringserver",
                        "wireless-calibrations-v1.json")
                : Path.of(configured);
        return new WirelessCalibrationStore(location);
    }

    public static WirelessCalibrationStore inMemory() {
        try { return new WirelessCalibrationStore(null); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized WirelessCalibration get(String clientId, String targetId) {
        StoredCalibration stored = calibrations.get(key(clientId, targetId));
        return stored == null ? null : stored.calibration();
    }

    public synchronized void put(String clientId, WirelessCalibration calibration) throws IOException {
        if (calibration == null) throw new IllegalArgumentException("calibration is required");
        String normalizedClientId = clientId(clientId);
        String key = key(normalizedClientId, calibration.targetId());
        StoredCalibration previous = calibrations.put(key,
                new StoredCalibration(normalizedClientId, calibration));
        try { save(); }
        catch (IOException exception) {
            if (previous == null) calibrations.remove(key); else calibrations.put(key, previous);
            throw exception;
        }
    }

    public Path path() { return path; }

    private void load() throws IOException {
        if (path == null || !Files.exists(path)) return;
        try {
            Object parsed = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)
                    || !(root.get("version") instanceof Number version) || version.intValue() != 1
                    || !(root.get("calibrations") instanceof Iterable<?> values)) {
                throw new IOException("Invalid wireless calibration store");
            }
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> map)) throw new IOException("Invalid calibration entry");
                String clientId = clientId(text(map, "clientId"));
                WirelessCalibration calibration = new WirelessCalibration(text(map, "targetId"),
                        number(map, "referenceDistanceMeters"), number(map, "referenceRssiDbm"),
                        number(map, "pathLossExponent"), number(map, "rmseDb"),
                        integer(map, "pointCount"), longInteger(map, "calibratedAt"));
                calibrations.put(key(clientId, calibration.targetId()),
                        new StoredCalibration(clientId, calibration));
            }
        } catch (ProtocolException | IllegalArgumentException exception) {
            throw new IOException("Invalid wireless calibration JSON", exception);
        }
    }

    private void save() throws IOException {
        if (path == null) return;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        var values = calibrations.values().stream().map(stored -> {
            WirelessCalibration value = stored.calibration();
            return Map.<String, Object>of(
                    "clientId", stored.clientId(),
                    "targetId", value.targetId(),
                    "referenceDistanceMeters", value.referenceDistanceMeters(),
                    "referenceRssiDbm", value.referenceRssiDbm(),
                    "pathLossExponent", value.pathLossExponent(),
                    "rmseDb", value.rmseDb(),
                    "pointCount", value.pointCount(),
                    "calibratedAt", value.calibratedAt());
        }).toList();
        String json;
        try { json = JsonCodec.stringify(Map.of("version", 1, "calibrations", values)); }
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

    private static String text(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String text)) throw new IOException("Invalid " + key);
        return text;
    }
    private static double number(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IOException("Invalid " + key);
        }
        return number.doubleValue();
    }
    private static int integer(Map<?, ?> map, String key) throws IOException {
        long value = longInteger(map, key);
        if (value > Integer.MAX_VALUE) throw new IOException("Invalid " + key);
        return (int) value;
    }
    private static long longInteger(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IOException("Invalid " + key);
        }
        return number.longValue();
    }
    private static String clientId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128 || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid calibration Client ID");
        }
        return normalized;
    }
    private static String key(String clientId, String targetId) {
        return clientId(clientId) + "\0" + targetId;
    }
    private record StoredCalibration(String clientId, WirelessCalibration calibration) {}
}
