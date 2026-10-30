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
import lanmonitoringserver.model.FingerprintSample;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.ProtocolException;

/** Server-owned, atomic persistence for administrator-surveyed radio fingerprints. */
public final class RadioMapStore {
    private final Path path;
    private final LinkedHashMap<String, FingerprintSample> samples = new LinkedHashMap<>();

    public RadioMapStore(Path path) throws IOException {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        load();
    }
    public static RadioMapStore defaultStore() throws IOException {
        String configured = System.getProperty("lanmonitoring.fingerprint.store", "").trim();
        Path file = configured.isEmpty() ? Path.of(System.getProperty("user.home"),
                ".lanmonitoringserver", "wifi-radio-map-v1.json") : Path.of(configured);
        return new RadioMapStore(file);
    }
    public static RadioMapStore inMemory() {
        try { return new RadioMapStore(null); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized List<FingerprintSample> samples() { return List.copyOf(samples.values()); }
    public Path path() { return path; }

    public synchronized void add(FingerprintSample sample) throws IOException {
        FingerprintSample previous = samples.put(sample.sampleId(), sample);
        try { save(); }
        catch (IOException error) {
            if (previous == null) samples.remove(sample.sampleId()); else samples.put(sample.sampleId(), previous);
            throw error;
        }
    }
    public synchronized void remove(String sampleId) throws IOException {
        FingerprintSample previous = samples.remove(validId(sampleId));
        if (previous == null) return;
        try { save(); } catch (IOException error) { samples.put(previous.sampleId(), previous); throw error; }
    }
    public synchronized void clearReference(String referencePoint) throws IOException {
        String reference = referencePoint == null ? "" : referencePoint.trim();
        List<FingerprintSample> removed = samples.values().stream()
                .filter(value -> value.position().referencePoint().equals(reference)).toList();
        if (removed.isEmpty()) return;
        removed.forEach(value -> samples.remove(value.sampleId()));
        try { save(); }
        catch (IOException error) { removed.forEach(value -> samples.put(value.sampleId(), value)); throw error; }
    }

    private void load() throws IOException {
        if (path == null || !Files.exists(path)) return;
        try {
            Object parsed = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("version") instanceof Number version)
                    || version.intValue() != 1 || !(root.get("samples") instanceof List<?> list)) {
                throw new IOException("Invalid Wi-Fi radio map");
            }
            for (Object raw : list) {
                FingerprintSample sample = parseSample(raw);
                if (samples.put(sample.sampleId(), sample) != null) throw new IOException("Duplicate sample ID");
            }
        } catch (ProtocolException | IllegalArgumentException error) {
            throw new IOException("Invalid Wi-Fi radio map JSON", error);
        }
    }

    private void save() throws IOException {
        if (path == null) return;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        List<Object> entries = new ArrayList<>();
        for (FingerprintSample sample : samples.values()) entries.add(sampleMap(sample));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1); root.put("samples", entries);
        String json;
        try { json = JsonCodec.stringify(root); }
        catch (ProtocolException error) { throw new IOException(error); }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> sampleMap(FingerprintSample sample) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sampleId", sample.sampleId());
        map.put("clientId", sample.clientId());
        map.put("referencePoint", sample.position().referencePoint());
        map.put("xMeters", sample.position().xMeters());
        map.put("yMeters", sample.position().yMeters());
        map.put("zMeters", sample.position().zMeters());
        map.put("capturedAt", sample.capturedAt());
        map.put("rssiByTarget", sample.rssiByTarget());
        return map;
    }
    private static FingerprintSample parseSample(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("Invalid fingerprint sample");
        long captured = integer(map.get("capturedAt"), "capturedAt");
        Object z = map.get("zMeters");
        SpatialPosition position = new SpatialPosition(text(map.get("referencePoint"), "referencePoint"),
                number(map.get("xMeters"), "xMeters"), number(map.get("yMeters"), "yMeters"),
                z == null ? null : number(z, "zMeters"), captured);
        if (!(map.get("rssiByTarget") instanceof Map<?, ?> rawSignals)) throw new IOException("Invalid signals");
        LinkedHashMap<String, Double> signals = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawSignals.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("Invalid target ID");
            signals.put(key, number(entry.getValue(), "RSSI"));
        }
        return new FingerprintSample(text(map.get("sampleId"), "sampleId"),
                text(map.get("clientId"), "clientId"), position, signals, captured);
    }
    private static String text(Object value, String name) throws IOException {
        if (!(value instanceof String text)) throw new IOException("Invalid " + name);
        return text;
    }
    private static double number(Object value, String name) throws IOException {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IOException("Invalid " + name);
        }
        return number.doubleValue();
    }
    private static long integer(Object value, String name) throws IOException {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() <= 0) throw new IOException("Invalid " + name);
        return number.longValue();
    }
    private static String validId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid sample ID");
        return id;
    }
}
