package lanmonitoringserver.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessCalibration;
import lanmonitoringserver.model.WirelessCalibrationPoint;
import lanmonitoringserver.model.WirelessDistanceEstimate;
import lanmonitoringserver.model.WirelessObservation;
import lanmonitoringserver.model.WirelessSignalSample;

/** Per-client ordered history, EMA smoothing, and AP-specific calibration. */
public final class WirelessTracker {
    private final int capacity;
    private final double alpha;
    private final WirelessDistanceEstimator estimator;
    private final WirelessCalibrationStore calibrationStore;
    private final String calibrationScope;
    private final Deque<WirelessObservation> history = new ArrayDeque<>();
    private long lastTimestamp;
    private String targetId = "";
    private Double smoothedRssi;
    private WirelessCalibration calibration;

    public WirelessTracker() { this(120, 0.30, new LogDistanceWirelessEstimator(),
            WirelessCalibrationStore.inMemory(), "local-tracker"); }
    WirelessTracker(int capacity, double alpha, WirelessDistanceEstimator estimator) {
        this(capacity, alpha, estimator, WirelessCalibrationStore.inMemory(), "local-tracker");
    }
    WirelessTracker(WirelessCalibrationStore calibrationStore) {
        this(120, 0.30, new LogDistanceWirelessEstimator(), calibrationStore, "local-tracker");
    }
    WirelessTracker(String calibrationScope, WirelessCalibrationStore calibrationStore) {
        this(120, 0.30, new LogDistanceWirelessEstimator(), calibrationStore, calibrationScope);
    }
    WirelessTracker(int capacity, double alpha, WirelessDistanceEstimator estimator,
            WirelessCalibrationStore calibrationStore, String calibrationScope) {
        if (capacity < 1 || alpha <= 0 || alpha > 1) throw new IllegalArgumentException();
        this.capacity = capacity; this.alpha = alpha; this.estimator = estimator;
        this.calibrationStore = calibrationStore == null
                ? WirelessCalibrationStore.inMemory() : calibrationStore;
        this.calibrationScope = calibrationScope == null || calibrationScope.isBlank()
                ? "local-tracker" : calibrationScope;
    }

    public synchronized boolean accept(WirelessSignalSample sample) {
        if (sample.timestamp() <= lastTimestamp) return false;
        lastTimestamp = sample.timestamp();
        if (!sample.targetId().equals(targetId)) {
            targetId = sample.targetId(); smoothedRssi = null;
            calibration = targetId.isBlank() ? null
                    : calibrationStore.get(calibrationScope, targetId);
        }
        if (sample.status() == MeasurementStatus.OK && sample.rssiDbm() != null) {
            smoothedRssi = smoothedRssi == null ? sample.rssiDbm()
                    : alpha * sample.rssiDbm() + (1 - alpha) * smoothedRssi;
        }
        WirelessDistanceEstimate distance;
        if (sample.status() != MeasurementStatus.OK || sample.isStale(System.currentTimeMillis())) {
            boolean stale = sample.status() == MeasurementStatus.OK;
            distance = WirelessDistanceEstimate.unavailable(sample.timestamp(),
                    stale ? MeasurementStatus.STALE : sample.status(), sample.target(),
                    stale ? "Wireless sample is stale" : sample.detail());
        } else if (smoothedRssi == null) {
            distance = WirelessDistanceEstimate.unavailable(sample.timestamp(), MeasurementStatus.UNSUPPORTED,
                    sample.target(), "RSSI dBm unavailable; Link Quality percent is not converted to distance");
        } else distance = estimator.estimate(sample, smoothedRssi, calibration);
        history.addLast(new WirelessObservation(sample, smoothedRssi, distance));
        while (history.size() > capacity) history.removeFirst();
        return true;
    }

    public synchronized WirelessCalibration calibrate(List<WirelessCalibrationPoint> points) {
        if (targetId.isBlank()) throw new IllegalStateException("No Access Point target is currently known");
        WirelessCalibration candidate = estimator.calibrate(targetId, points);
        try { calibrationStore.put(calibrationScope, candidate); }
        catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot persist wireless calibration: "
                    + exception.getMessage(), exception);
        }
        calibration = candidate;
        return candidate;
    }
    public synchronized List<WirelessObservation> history() { return List.copyOf(new ArrayList<>(history)); }
    public synchronized WirelessObservation latest() { return history.peekLast(); }
    public synchronized WirelessCalibration calibration() { return calibration; }
}
