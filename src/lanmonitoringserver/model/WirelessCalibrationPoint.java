package lanmonitoringserver.model;

public record WirelessCalibrationPoint(double distanceMeters, double rssiDbm) {
    public WirelessCalibrationPoint {
        if (!Double.isFinite(distanceMeters) || distanceMeters <= 0 || distanceMeters > 10_000
                || !Double.isFinite(rssiDbm) || rssiDbm < -150 || rssiDbm > 0) {
            throw new IllegalArgumentException("Invalid wireless calibration point");
        }
    }
}
