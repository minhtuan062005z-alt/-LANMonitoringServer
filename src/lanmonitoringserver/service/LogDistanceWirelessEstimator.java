package lanmonitoringserver.service;

import java.util.List;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessCalibration;
import lanmonitoringserver.model.WirelessCalibrationPoint;
import lanmonitoringserver.model.WirelessConfidence;
import lanmonitoringserver.model.WirelessDistanceEstimate;
import lanmonitoringserver.model.WirelessMeasurementMethod;
import lanmonitoringserver.model.WirelessMeasurementTarget;
import lanmonitoringserver.model.WirelessSignalSample;

/** Calibrated log-distance path-loss model. It only estimates Client-to-AP range. */
public final class LogDistanceWirelessEstimator implements WirelessDistanceEstimator {
    @Override public WirelessCalibration calibrate(String targetId, List<WirelessCalibrationPoint> points) {
        if (targetId == null || !targetId.matches("[0-9a-f]{64}") || points == null || points.size() < 2) {
            throw new IllegalArgumentException("Calibration needs one AP target and at least two measured points");
        }
        double meanX = 0, meanY = 0;
        for (var point : points) {
            meanX += Math.log10(point.distanceMeters());
            meanY += point.rssiDbm();
        }
        meanX /= points.size(); meanY /= points.size();
        double covariance = 0, variance = 0;
        for (var point : points) {
            double x = Math.log10(point.distanceMeters()) - meanX;
            covariance += x * (point.rssiDbm() - meanY);
            variance += x * x;
        }
        if (variance < 1e-9) throw new IllegalArgumentException("Calibration distances must differ");
        double slope = covariance / variance;
        double exponent = -slope / 10d;
        if (exponent < 0.5 || exponent > 8) {
            throw new IllegalArgumentException("Calibration produced implausible path-loss exponent: " + exponent);
        }
        double referenceRssi = meanY - slope * meanX;
        double squaredError = 0;
        for (var point : points) {
            double predicted = referenceRssi + slope * Math.log10(point.distanceMeters());
            squaredError += Math.pow(point.rssiDbm() - predicted, 2);
        }
        return new WirelessCalibration(targetId, 1d, referenceRssi, exponent,
                Math.sqrt(squaredError / points.size()), points.size(), System.currentTimeMillis());
    }

    @Override public WirelessDistanceEstimate estimate(WirelessSignalSample sample,
            double smoothedRssiDbm, WirelessCalibration calibration) {
        if (sample.target() != WirelessMeasurementTarget.ACCESS_POINT) {
            return WirelessDistanceEstimate.unavailable(sample.timestamp(), MeasurementStatus.UNSUPPORTED,
                    sample.target(), "No supported physical ranging path to this target");
        }
        if (calibration == null || !calibration.targetId().equals(sample.targetId())) {
            return WirelessDistanceEstimate.unavailable(sample.timestamp(), MeasurementStatus.UNSUPPORTED,
                    sample.target(), "Client-AP RSSI needs calibration for this Access Point");
        }
        double meters = calibration.referenceDistanceMeters() * Math.pow(10d,
                (calibration.referenceRssiDbm() - smoothedRssiDbm) / (10d * calibration.pathLossExponent()));
        if (!Double.isFinite(meters) || meters > 100_000) {
            return WirelessDistanceEstimate.unavailable(sample.timestamp(), MeasurementStatus.ERROR,
                    sample.target(), "Estimated value is outside the model range");
        }
        double factor = Math.pow(10d, calibration.rmseDb() / (10d * calibration.pathLossExponent())) - 1d;
        WirelessConfidence confidence = calibration.pointCount() >= 4 && calibration.rmseDb() <= 3
                ? WirelessConfidence.MEDIUM : WirelessConfidence.LOW;
        return new WirelessDistanceEstimate(sample.timestamp(), MeasurementStatus.OK, meters,
                meters * factor, WirelessMeasurementTarget.ACCESS_POINT,
                WirelessMeasurementMethod.RSSI_LOG_DISTANCE, confidence,
                "Estimated Client-to-AP distance from calibrated RSSI; not actual Client-to-Server distance");
    }
}
