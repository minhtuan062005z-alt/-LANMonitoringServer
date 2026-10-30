package lanmonitoringserver.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.DistanceDataSource;
import lanmonitoringserver.model.EstimatedClientPosition;
import lanmonitoringserver.model.FingerprintSample;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.ServerClientDistanceMethod;
import lanmonitoringserver.model.ServerClientDistanceStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.model.WirelessSignalSample;
import lanmonitoringserver.protocol.JsonCodec;
import lanmonitoringserver.protocol.WifiScanMapper;

public final class WifiFingerprintTest {
    public static void main(String[] args) throws Exception {
        testWeightedKnnAndScientificLabels();
        testUnavailableAndStaleInputs();
        testRadioMapPersistence();
        testDistancePriorityAndIsolation();
        testPrivacySafeProtocolMapping();
        testTlsCapabilityGate();
        System.out.println("WifiFingerprintTest: PASS");
    }

    private static void testWeightedKnnAndScientificLabels() {
        long now = System.currentTimeMillis();
        List<FingerprintSample> map = List.of(
                sample("p0", 0, 0, now, -40, -61, -72),
                sample("p1", 2, 0, now, -68, -42, -64),
                sample("p2", 0, 2, now, -65, -70, -41),
                sample("p3", 2, 2, now, -58, -55, -53));
        WifiScanSnapshot query = scan(now, -41, -60, -71);
        EstimatedClientPosition result = new WifiFingerprintEstimator().estimate("client-a", query, map);
        check(result.status() == MeasurementStatus.OK, "fresh matching scan is estimated");
        check(result.position().xMeters() < 0.2 && result.position().yMeters() < 0.2,
                "weighted KNN remains near matching surveyed coordinate");
        check(result.estimatedErrorMeters() != null && result.estimatedErrorMeters() >= 1,
                "error includes a non-zero Radio Map resolution floor");
        check(result.detail().contains("not direct"), "estimate explicitly rejects direct-ranging label");
    }

    private static void testUnavailableAndStaleInputs() {
        long now = System.currentTimeMillis();
        WifiFingerprintEstimator estimator = new WifiFingerprintEstimator();
        EstimatedClientPosition insufficient = estimator.estimate("client-a",
                new WifiScanSnapshot(now, 10_000, MeasurementStatus.OK, "wifi",
                        List.of(signal("a", -40), signal("b", -50)), "fixture", "two APs"), List.of());
        check(insufficient.status() == MeasurementStatus.NOT_AVAILABLE,
                "fewer than three APs never invent a position");
        EstimatedClientPosition stale = estimator.estimate("client-a",
                scan(now - 40_000, -40, -60, -70),
                List.of(sample("p0", 0, 0, now, -40, -60, -70),
                        sample("p1", 1, 0, now, -60, -40, -70),
                        sample("p2", 0, 1, now, -70, -60, -40)));
        check(stale.status() == MeasurementStatus.STALE && stale.position() == null,
                "stale scans carry no coordinate");
    }

    private static void testRadioMapPersistence() throws Exception {
        Path directory = Files.createTempDirectory("radio-map-test-");
        Path file = directory.resolve("map.json");
        long now = System.currentTimeMillis();
        RadioMapStore first = new RadioMapStore(file);
        first.add(sample("persisted", 3, 4, now, -45, -55, -65));
        RadioMapStore restarted = new RadioMapStore(file);
        check(restarted.samples().size() == 1, "Radio Map survives restart");
        check(restarted.samples().get(0).position().xMeters() == 3,
                "persisted surveyed coordinate is preserved");
        restarted.remove("persisted");
        check(new RadioMapStore(file).samples().isEmpty(), "sample removal persists");
    }

    private static void testDistancePriorityAndIsolation() throws Exception {
        long now = System.currentTimeMillis();
        ServerClientDistanceService service = new ServerClientDistanceService(
                ServerClientDistanceStore.inMemory());
        service.setServerPosition(new SpatialPosition("room", 0, 0, null, now));
        EstimatedClientPosition estimate = new EstimatedClientPosition("client-a",
                new SpatialPosition("room", 3, 4, null, now), MeasurementStatus.OK,
                lanmonitoringserver.model.WirelessConfidence.MEDIUM, 1.2, 5, 3, 4,
                now + 30_000, "fixture", "position-based");
        service.updateEstimatedPosition("client-a", estimate);
        var inferred = service.distanceFor("client-a");
        check(close(inferred.distanceMeters(), 5), "fingerprinted coordinate produces Euclidean distance");
        check(inferred.measurementMethod() == ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                "fingerprinting is position-based");
        check(inferred.dataSource() == DistanceDataSource.WIFI_FINGERPRINT,
                "fingerprinting source is explicit");

        EstimatedClientPosition expired = new EstimatedClientPosition("client-a",
                new SpatialPosition("room", 3, 4, null, now - 60_000), MeasurementStatus.OK,
                lanmonitoringserver.model.WirelessConfidence.MEDIUM, 1.2, 5, 3, 4,
                now - 1, "fixture", "expired historical position");
        service.updateEstimatedPosition("client-a", expired);
        var stale = service.distanceFor("client-a");
        check(stale.status() == ServerClientDistanceStatus.STALE && stale.distanceMeters() == null,
                "expired fingerprint never remains a realtime distance");
        check(stale.clientPosition() != null && stale.clientPosition().xMeters() == 3,
                "expired coordinate is retained as explicitly stale history");

        service.updateEstimatedPosition("client-a", EstimatedClientPosition.unavailable("client-a",
                MeasurementStatus.UNSUPPORTED, 0, "scanner unsupported"));
        var unsupported = service.distanceFor("client-a");
        check(unsupported.status() == ServerClientDistanceStatus.UNSUPPORTED
                        && unsupported.distanceMeters() == null,
                "unsupported scanner remains distinct from unavailable and stale");

        service.updateEstimatedPosition("client-a", estimate);
        service.setClientPosition("client-a", new SpatialPosition("room", 6, 8, null, now));
        check(close(service.distanceFor("client-a").distanceMeters(), 10)
                && service.distanceFor("client-a").dataSource() == DistanceDataSource.ADMIN_COORDINATES,
                "administrator coordinates override the estimate");
        service.updateEstimatedPosition("client-a", expired);
        check(close(service.distanceFor("client-a").distanceMeters(), 10),
                "stale fingerprint does not overwrite administrator coordinates");
        service.setManualMeasurement("client-a", 2.5, null, now);
        check(close(service.distanceFor("client-a").distanceMeters(), 2.5),
                "manual distance has highest priority");
        check(service.distanceFor("client-b").distanceMeters() == null,
                "one Client never receives another Client's estimate");
    }

    private static void testPrivacySafeProtocolMapping() throws Exception {
        WifiScanSnapshot original = scan(System.currentTimeMillis(), -44, -60, -73);
        String json = JsonCodec.stringify(WifiScanMapper.toMap(original));
        check(!json.contains("aa:bb") && !json.toLowerCase().contains("\"ssid\"")
                        && !json.toLowerCase().contains("\"bssid\""),
                "transport has neither raw BSSID nor SSID");
        Object parsed = JsonCodec.parse(json);
        WifiScanSnapshot restored = WifiScanMapper.fromMap((java.util.Map<?, ?>) parsed);
        check(restored.accessPoints().size() == 3 && restored.accessPoints().get(0).rssiDbm() == -44,
                "real RSSI survives strict JSON mapping");
    }

    private static void testTlsCapabilityGate() {
        String previous = System.getProperty("lanmonitoring.fingerprint.requireTls");
        try {
            System.clearProperty("lanmonitoring.fingerprint.requireTls");
            check(!LanServerService.fingerprintCapabilityAllowed(false),
                    "plaintext does not advertise fingerprinting by default");
            check(LanServerService.fingerprintCapabilityAllowed(true),
                    "authenticated TLS permits fingerprinting");
            System.setProperty("lanmonitoring.fingerprint.requireTls", "false");
            check(LanServerService.fingerprintCapabilityAllowed(false),
                    "explicit lab override is deterministic");
        } finally {
            if (previous == null) System.clearProperty("lanmonitoring.fingerprint.requireTls");
            else System.setProperty("lanmonitoring.fingerprint.requireTls", previous);
        }
    }

    private static FingerprintSample sample(String id, double x, double y, long time,
            double a, double b, double c) {
        LinkedHashMap<String, Double> signals = new LinkedHashMap<>();
        signals.put(target("a"), a); signals.put(target("b"), b); signals.put(target("c"), c);
        return new FingerprintSample(id, "client-a",
                new SpatialPosition("room", x, y, null, time), signals, time);
    }
    private static WifiScanSnapshot scan(long time, double a, double b, double c) {
        return new WifiScanSnapshot(time, 10_000, MeasurementStatus.OK, "wifi",
                List.of(signal("a", a), signal("b", b), signal("c", c)), "fixture", "real RSSI fixture");
    }
    private static AccessPointSignal signal(String id, double rssi) {
        return new AccessPointSignal(target(id), rssi, 2412);
    }
    private static String target(String suffix) {
        String mac = switch (suffix) {
            case "a" -> "aa:00:00:00:00:01";
            case "b" -> "aa:00:00:00:00:02";
            default -> "aa:00:00:00:00:03";
        };
        return WirelessSignalSample.targetIdForBssid(mac);
    }
    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.000001;
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
