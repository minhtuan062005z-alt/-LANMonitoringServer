package lanmonitoringserver.monitor.wireless;

import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessDistanceEstimate;
import lanmonitoringserver.model.WirelessMeasurementTarget;
import lanmonitoringserver.model.WirelessSignalSample;

public final class UnsupportedWifiRttProvider implements WifiRttProvider {
    @Override public boolean supported() { return false; }
    @Override public WirelessDistanceEstimate range(WirelessSignalSample signal) {
        return WirelessDistanceEstimate.unavailable(System.currentTimeMillis(),
                MeasurementStatus.UNSUPPORTED, WirelessMeasurementTarget.NOT_DETERMINED,
                "No desktop Wi-Fi RTT/FTM ranging provider is installed");
    }
    @Override public String providerName() { return "No Wi-Fi RTT/FTM provider"; }
}
