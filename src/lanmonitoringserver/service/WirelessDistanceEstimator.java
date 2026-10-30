package lanmonitoringserver.service;

import java.util.List;
import lanmonitoringserver.model.WirelessCalibration;
import lanmonitoringserver.model.WirelessCalibrationPoint;
import lanmonitoringserver.model.WirelessDistanceEstimate;
import lanmonitoringserver.model.WirelessSignalSample;

public interface WirelessDistanceEstimator {
    WirelessCalibration calibrate(String targetId, List<WirelessCalibrationPoint> points);
    WirelessDistanceEstimate estimate(WirelessSignalSample sample, double smoothedRssiDbm,
            WirelessCalibration calibration);
}
