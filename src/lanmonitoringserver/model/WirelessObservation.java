package lanmonitoringserver.model;

public record WirelessObservation(WirelessSignalSample signal, Double smoothedRssiDbm,
        WirelessDistanceEstimate distance) {}
