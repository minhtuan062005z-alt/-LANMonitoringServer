package lanmonitoringserver.monitor.wireless;

import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WirelessConfidence;
import lanmonitoringserver.model.WirelessMeasurementTarget;
import lanmonitoringserver.model.WirelessNetworkType;
import lanmonitoringserver.model.WirelessRttCapability;
import lanmonitoringserver.model.WirelessSignalSample;

public final class UnsupportedWirelessSignalCollector implements WirelessSignalCollector {
    private final String detail;
    public UnsupportedWirelessSignalCollector(String detail) { this.detail = detail; }
    @Override public WirelessSignalSample collect() {
        return new WirelessSignalSample(System.currentTimeMillis(), 2_000,
                MeasurementStatus.UNSUPPORTED, WirelessNetworkType.UNKNOWN, "", null, null, "",
                null, null, WirelessMeasurementTarget.NOT_DETERMINED,
                WirelessRttCapability.UNKNOWN, WirelessConfidence.UNKNOWN,
                collectorName(), detail);
    }
    @Override public String collectorName() { return "Unsupported wireless collector"; }
}
