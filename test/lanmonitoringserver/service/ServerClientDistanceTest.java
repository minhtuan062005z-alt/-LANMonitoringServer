package lanmonitoringserver.service;

import java.nio.file.Files;
import java.nio.file.Path;
import lanmonitoringserver.model.ServerClientDistance;
import lanmonitoringserver.model.ServerClientDistanceMethod;
import lanmonitoringserver.model.ServerClientDistanceStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.DistanceDataSource;

public final class ServerClientDistanceTest {
    public static void main(String[] args) throws Exception {
        testUnavailableAndTwoDimensionalFormula();
        testThreeDimensionalFormulaAndReferenceIsolation();
        testManualMeasurementAndValidation();
        testPersistenceAndClientIsolation();
        System.out.println("ServerClientDistanceTest: PASS");
    }

    private static void testUnavailableAndTwoDimensionalFormula() throws Exception {
        ServerClientDistanceStore store = ServerClientDistanceStore.inMemory();
        ServerClientDistanceService service = new ServerClientDistanceService(store);
        ServerClientDistance unavailable = service.distanceFor("client-a");
        check(unavailable.status() == ServerClientDistanceStatus.NOT_AVAILABLE,
                "missing positions are unavailable");
        check(unavailable.distanceMeters() == null, "unavailable does not invent metres");
        expectInvalid(() -> new ServerClientDistance("client-a", 2d,
                ServerClientDistanceMethod.DIRECT_MEASUREMENT,
                DistanceDataSource.DIRECT_PROVIDER, ServerClientDistanceStatus.UNSUPPORTED,
                null, System.currentTimeMillis(), "direct provider", null, null));
        long now = System.currentTimeMillis();
        service.setServerPosition(new SpatialPosition("lab-a", 1, 1, null, now));
        check(service.distanceFor("client-a").distanceMeters() == null,
                "Server position alone is insufficient");
        service.setClientPosition("client-a", new SpatialPosition("lab-a", 4, 5, null, now + 1));
        ServerClientDistance result = service.distanceFor("client-a");
        check(close(result.distanceMeters(), 5), "2D Euclidean 3-4-5 formula");
        check(result.measurementMethod() == ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                "coordinate method is explicit");
    }

    private static void testThreeDimensionalFormulaAndReferenceIsolation() throws Exception {
        ServerClientDistanceService service = new ServerClientDistanceService(
                ServerClientDistanceStore.inMemory());
        long now = System.currentTimeMillis();
        service.setServerPosition(new SpatialPosition("floor-map", 0, 0, 0d, now));
        service.setClientPosition("client-3d", new SpatialPosition("floor-map", 2, 3, 6d, now));
        check(close(service.distanceFor("client-3d").distanceMeters(), 7),
                "valid Z coordinates use 3D Euclidean formula");
        service.setClientPosition("different-map", new SpatialPosition("other-map", 2, 3, null, now));
        check(service.distanceFor("different-map").distanceMeters() == null,
                "coordinates from different reference frames are rejected");
    }

    private static void testManualMeasurementAndValidation() throws Exception {
        ServerClientDistanceService service = new ServerClientDistanceService(
                ServerClientDistanceStore.inMemory());
        long now = System.currentTimeMillis();
        service.setManualMeasurement("manual-client", 12.4, 0.3, now);
        ServerClientDistance result = service.distanceFor("manual-client");
        check(close(result.distanceMeters(), 12.4), "manual measured value is preserved");
        check(result.measurementMethod() == ServerClientDistanceMethod.MANUAL_MEASUREMENT,
                "manual value is labelled manual");
        check(close(result.estimatedErrorMeters(), 0.3), "manual estimated error is preserved");
        expectInvalid(() -> service.setManualMeasurement("manual-client", -1, null, now));
        expectInvalid(() -> service.setManualMeasurement("manual-client", Double.NaN, null, now));
        expectInvalid(() -> service.setManualMeasurement("manual-client", Double.POSITIVE_INFINITY, null, now));
        expectInvalid(() -> new SpatialPosition("lab", Double.NaN, 0, null, now));
    }

    private static void testPersistenceAndClientIsolation() throws Exception {
        Path directory = Files.createTempDirectory("distance-store-test");
        Path file = directory.resolve("distances.json");
        long now = System.currentTimeMillis();
        ServerClientDistanceService first = new ServerClientDistanceService(
                new ServerClientDistanceStore(file));
        first.setServerPosition(new SpatialPosition("room-101", 10, 20, null, now));
        first.setClientPosition("client-one", new SpatialPosition("room-101", 13, 24, null, now));
        first.setManualMeasurement("client-two", 8.5, null, now);

        ServerClientDistanceService restarted = new ServerClientDistanceService(
                new ServerClientDistanceStore(file));
        check(close(restarted.distanceFor("client-one").distanceMeters(), 5),
                "coordinate distance survives restart");
        check(close(restarted.distanceFor("client-two").distanceMeters(), 8.5),
                "manual distance survives restart");
        check(restarted.distanceFor("client-three").distanceMeters() == null,
                "one Client never receives another Client's data");
        restarted.clearClient("client-one");
        check(restarted.distanceFor("client-one").distanceMeters() == null,
                "clear removes only selected Client data");
        check(close(restarted.distanceFor("client-two").distanceMeters(), 8.5),
                "clearing one Client preserves another Client");
    }

    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.000001;
    }
    private static void expectInvalid(ThrowingAction action) throws Exception {
        try { action.run(); throw new AssertionError("Expected invalid value rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
