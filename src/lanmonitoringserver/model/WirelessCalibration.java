package lanmonitoringserver.model;

public record WirelessCalibration(String targetId, double referenceDistanceMeters,
        double referenceRssiDbm, double pathLossExponent, double rmseDb,
        int pointCount, long calibratedAt) {
    public WirelessCalibration {
        if (targetId == null || !targetId.matches("[0-9a-f]{64}")
                || !Double.isFinite(referenceDistanceMeters) || referenceDistanceMeters <= 0
                || !Double.isFinite(referenceRssiDbm) || referenceRssiDbm < -150 || referenceRssiDbm > 0
                || !Double.isFinite(pathLossExponent) || pathLossExponent < 0.5 || pathLossExponent > 8
                || !Double.isFinite(rmseDb) || rmseDb < 0 || pointCount < 2 || calibratedAt <= 0) {
            throw new IllegalArgumentException("Invalid wireless calibration");
        }
    }
}
