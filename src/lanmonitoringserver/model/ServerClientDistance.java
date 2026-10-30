package lanmonitoringserver.model;

/**
 * Server-to-Client physical distance. This domain is intentionally separate
 * from network latency and Client-to-AP wireless estimates.
 */
public record ServerClientDistance(String clientId, Double distanceMeters,
        ServerClientDistanceMethod measurementMethod, DistanceDataSource dataSource,
        ServerClientDistanceStatus status, Double estimatedErrorMeters, long lastUpdated,
        String referencePoint, SpatialPosition serverPosition, SpatialPosition clientPosition) {

    public ServerClientDistance {
        clientId = normalizeClientId(clientId);
        if (measurementMethod == null || dataSource == null || status == null) {
            throw new IllegalArgumentException("Distance method, source and status are required");
        }
        referencePoint = referencePoint == null ? "" : referencePoint.trim();
        if (distanceMeters != null && (!Double.isFinite(distanceMeters) || distanceMeters < 0)) {
            throw new IllegalArgumentException("distanceMeters must be finite and non-negative");
        }
        if (estimatedErrorMeters != null
                && (!Double.isFinite(estimatedErrorMeters) || estimatedErrorMeters < 0)) {
            throw new IllegalArgumentException("estimatedErrorMeters must be finite and non-negative");
        }
        if (measurementMethod == ServerClientDistanceMethod.NOT_AVAILABLE
                && distanceMeters != null) {
            throw new IllegalArgumentException("An unavailable method cannot contain a distance");
        }
        if ((status == ServerClientDistanceStatus.UNSUPPORTED
                || status == ServerClientDistanceStatus.NOT_AVAILABLE
                || status == ServerClientDistanceStatus.ERROR) && distanceMeters != null) {
            throw new IllegalArgumentException("An unsupported/unavailable/error result cannot contain a distance");
        }
        if (status == ServerClientDistanceStatus.OK && distanceMeters == null) {
            throw new IllegalArgumentException("An OK distance must contain a value");
        }
        if (distanceMeters != null && lastUpdated <= 0) {
            throw new IllegalArgumentException("A distance value requires lastUpdated");
        }
    }

    public static ServerClientDistance unavailable(String clientId,
            SpatialPosition serverPosition, SpatialPosition clientPosition) {
        String reference = serverPosition != null ? serverPosition.referencePoint()
                : clientPosition != null ? clientPosition.referencePoint() : "";
        return new ServerClientDistance(clientId, null,
                ServerClientDistanceMethod.NOT_AVAILABLE, DistanceDataSource.NONE,
                ServerClientDistanceStatus.NOT_AVAILABLE, null, 0, reference,
                serverPosition, clientPosition);
    }

    private static String normalizeClientId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128 || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid Client ID");
        }
        return normalized;
    }
}
