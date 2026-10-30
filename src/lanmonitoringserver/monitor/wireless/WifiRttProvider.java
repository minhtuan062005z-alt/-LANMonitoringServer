package lanmonitoringserver.monitor.wireless;

import lanmonitoringserver.model.WirelessDistanceEstimate;
import lanmonitoringserver.model.WirelessSignalSample;

/** Extension point for a genuine hardware/API Wi-Fi RTT/FTM ranging implementation. */
public interface WifiRttProvider {
    boolean supported();
    WirelessDistanceEstimate range(WirelessSignalSample signal);
    String providerName();
}
