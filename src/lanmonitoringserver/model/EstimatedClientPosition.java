package lanmonitoringserver.model;

/** Result of Wi-Fi fingerprinting. It is an estimate, never direct ranging. */
public record EstimatedClientPosition(String clientId, SpatialPosition position,
        MeasurementStatus status, WirelessConfidence confidence, Double estimatedErrorMeters,
        int accessPointCount, int neighborCount, double signalDistanceDb,
        long validUntil, String provider, String detail) {
    public EstimatedClientPosition {
        clientId = clientId == null ? "" : clientId.trim();
        if (clientId.isEmpty() || clientId.length() > 128) throw new IllegalArgumentException("Invalid Client ID");
        if (status == null || confidence == null) throw new IllegalArgumentException("Position status is required");
        if (status == MeasurementStatus.OK && position == null) {
            throw new IllegalArgumentException("An OK estimate needs a position");
        }
        if (estimatedErrorMeters != null && (!Double.isFinite(estimatedErrorMeters)
                || estimatedErrorMeters < 0)) throw new IllegalArgumentException("Invalid position error");
        if (accessPointCount < 0 || accessPointCount > 128 || neighborCount < 0) {
            throw new IllegalArgumentException("Invalid fingerprint counts");
        }
        if (!Double.isFinite(signalDistanceDb) || signalDistanceDb < 0) {
            throw new IllegalArgumentException("Invalid fingerprint signal distance");
        }
        if (position != null && validUntil <= position.lastUpdated()) {
            throw new IllegalArgumentException("Estimated position validity must follow its timestamp");
        }
        provider = bounded(provider, 128);
        detail = bounded(detail, 512);
    }

    public boolean isStale(long now) {
        return position == null || now > validUntil;
    }

    /** Returns the time-aware status without mutating the historical estimate. */
    public MeasurementStatus statusAt(long now) {
        return status == MeasurementStatus.OK && isStale(now)
                ? MeasurementStatus.STALE : status;
    }

    public boolean isUsableAt(long now) {
        return statusAt(now) == MeasurementStatus.OK && position != null;
    }

    /** Preserves the last coordinate for history while preventing realtime use. */
    public EstimatedClientPosition asStale(String reason) {
        if (position == null || statusAt(System.currentTimeMillis()) != MeasurementStatus.OK) return this;
        return new EstimatedClientPosition(clientId, position, MeasurementStatus.STALE,
                confidence, estimatedErrorMeters, accessPointCount, neighborCount,
                signalDistanceDb, validUntil, provider,
                reason == null || reason.isBlank() ? "Historical Wi-Fi fingerprint estimate" : reason);
    }

    public static EstimatedClientPosition unavailable(String clientId, MeasurementStatus status,
            int accessPointCount, String detail) {
        return new EstimatedClientPosition(clientId, null, status, WirelessConfidence.UNKNOWN,
                null, accessPointCount, 0, 0, 0, "Wi-Fi fingerprint KNN", detail);
    }

    private static String bounded(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || result.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid text");
        return result;
    }
}
