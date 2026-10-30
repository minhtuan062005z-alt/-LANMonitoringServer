package lanmonitoringserver.model;

public record WirelessDistanceEstimate(long timestamp, MeasurementStatus status,
        Double estimatedMeters, Double uncertaintyMeters,
        WirelessMeasurementTarget target, WirelessMeasurementMethod method,
        WirelessConfidence confidence, String detail) {
    public WirelessDistanceEstimate {
        if (timestamp <= 0 || status == null || target == null || method == null || confidence == null) {
            throw new IllegalArgumentException("Invalid distance estimate");
        }
        if (estimatedMeters != null && (!Double.isFinite(estimatedMeters) || estimatedMeters < 0)) {
            throw new IllegalArgumentException("Invalid estimated distance");
        }
        if (uncertaintyMeters != null && (!Double.isFinite(uncertaintyMeters) || uncertaintyMeters < 0)) {
            throw new IllegalArgumentException("Invalid distance uncertainty");
        }
        detail = detail == null ? "" : detail;
    }

    public static WirelessDistanceEstimate unavailable(long timestamp, MeasurementStatus status,
            WirelessMeasurementTarget target, String detail) {
        return new WirelessDistanceEstimate(timestamp, status, null, null, target,
                WirelessMeasurementMethod.NONE, WirelessConfidence.UNKNOWN, detail);
    }
}
