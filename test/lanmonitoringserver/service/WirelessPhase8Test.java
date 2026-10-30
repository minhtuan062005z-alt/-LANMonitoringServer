package lanmonitoringserver.service;

import java.nio.file.Files;
import java.util.List;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.*;
import lanmonitoringserver.protocol.ProtocolException;
import lanmonitoringserver.protocol.WirelessMapper;
import lanmonitoringserver.ui.WirelessNetworkPanel;

public final class WirelessPhase8Test {
    public static void main(String[] args) throws Exception {
        testProtocolAndEstimator();
        testUnsupportedAndOrdering();
        testCalibrationPersistence();
        SwingUtilities.invokeAndWait(() -> {
            WirelessNetworkPanel panel = new WirelessNetworkPanel();
            panel.setLatency(new NetworkLatencySample(System.currentTimeMillis(), MeasurementStatus.OK, 12L,
                    "application RTT"));
            check(panel.getComponentCount() == 2, "wireless panel constructed headlessly on EDT");
        });
        System.out.println("WirelessPhase8Test: PASS");
    }
    private static void testProtocolAndEstimator() throws Exception {
        long now = System.currentTimeMillis();
        String id = WirelessSignalSample.targetIdForBssid("aa:bb:cc:dd:ee:ff");
        WirelessSignalSample sample = sample(now, id, -55d, null);
        WirelessSignalSample decoded = WirelessMapper.fromMap(WirelessMapper.toMap(sample));
        check(decoded.rssiDbm().equals(-55d), "wireless protocol round trip");
        WirelessTracker tracker = new WirelessTracker();
        check(tracker.accept(sample), "first sample accepted");
        var calibration = tracker.calibrate(List.of(new WirelessCalibrationPoint(1, -40),
                new WirelessCalibrationPoint(2, -46.02), new WirelessCalibrationPoint(4, -52.04),
                new WirelessCalibrationPoint(8, -58.06)));
        check(Math.abs(calibration.pathLossExponent() - 2) < .02, "path-loss exponent fitted");
        check(tracker.accept(sample(now + 1, id, -46.02, null)), "newer sample accepted");
        var estimate = tracker.latest().distance();
        check(estimate.status() == MeasurementStatus.OK, "calibrated estimate available");
        check(estimate.target() == WirelessMeasurementTarget.ACCESS_POINT, "range target is AP");
        check(estimate.estimatedMeters() > 3 && estimate.estimatedMeters() < 6,
                "EMA-smoothed estimate remains plausible and dampens a sudden change");
        try { WirelessMapper.fromMap(java.util.Map.of()); throw new AssertionError("malformed map rejected"); }
        catch (ProtocolException expected) { }
    }
    private static void testUnsupportedAndOrdering() {
        long now = System.currentTimeMillis();
        String id = WirelessSignalSample.targetIdForBssid("00:11:22:33:44:55");
        WirelessTracker tracker = new WirelessTracker();
        check(tracker.accept(sample(now, id, null, 80d)), "quality-only sample accepted");
        check(tracker.latest().distance().status() == MeasurementStatus.UNSUPPORTED,
                "link quality is not substituted for RSSI distance");
        check(!tracker.accept(sample(now, id, -50d, null)), "out-of-order/equal sample rejected");
        WirelessTracker noTarget = new WirelessTracker();
        try {
            noTarget.calibrate(List.of(new WirelessCalibrationPoint(1, -40),
                    new WirelessCalibrationPoint(2, -46)));
            throw new AssertionError("calibration without an observed AP must be rejected");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("No Access Point"),
                    "missing AP calibration error is explicit");
        }
        WirelessTracker stale = new WirelessTracker();
        check(stale.accept(sample(now - 10_000, id, -50d, null)), "stale sample retained diagnostically");
        check(stale.latest().distance().status() == MeasurementStatus.STALE,
                "stale signal cannot produce a live distance");
    }

    private static void testCalibrationPersistence() throws Exception {
        var path = Files.createTempFile("wireless-calibration-", ".json");
        Files.deleteIfExists(path);
        try {
            String id = WirelessSignalSample.targetIdForBssid("10:20:30:40:50:60");
            WirelessTracker first = new WirelessTracker("client-a", new WirelessCalibrationStore(path));
            long now = System.currentTimeMillis();
            first.accept(sample(now, id, -46d, null));
            first.calibrate(List.of(new WirelessCalibrationPoint(1, -40),
                    new WirelessCalibrationPoint(2, -46.02),
                    new WirelessCalibrationPoint(4, -52.04)));

            WirelessTracker restarted = new WirelessTracker("client-a", new WirelessCalibrationStore(path));
            restarted.accept(sample(now + 1, id, -46.02, null));
            check(restarted.calibration() != null,
                    "AP calibration restored after tracker/server restart");
            check(restarted.latest().distance().status() == MeasurementStatus.OK
                            && restarted.latest().distance().estimatedMeters() != null,
                    "restored AP calibration enables Client-to-AP estimate");

            WirelessTracker anotherClient = new WirelessTracker("client-b",
                    new WirelessCalibrationStore(path));
            anotherClient.accept(sample(now + 1, id, -46.02, null));
            check(anotherClient.calibration() == null
                            && anotherClient.latest().distance().estimatedMeters() == null,
                    "calibration is never reused for another Client/adapter");

            String otherId = WirelessSignalSample.targetIdForBssid("10:20:30:40:50:61");
            restarted.accept(sample(now + 2, otherId, -46.02, null));
            check(restarted.calibration() == null
                            && restarted.latest().distance().estimatedMeters() == null,
                    "calibration is never reused for another AP target");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
        }
    }
    private static WirelessSignalSample sample(long timestamp, String id, Double rssi, Double quality) {
        return new WirelessSignalSample(timestamp, 2000, MeasurementStatus.OK, WirelessNetworkType.WIFI,
                "wlan0", null, null, id, rssi, quality, WirelessMeasurementTarget.ACCESS_POINT,
                WirelessRttCapability.UNKNOWN, WirelessConfidence.LOW, "fixture", "direct observation");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
