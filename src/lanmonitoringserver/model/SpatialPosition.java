package lanmonitoringserver.model;

/** A position in a named, metre-based local coordinate reference frame. */
public record SpatialPosition(String referencePoint, double xMeters, double yMeters,
        Double zMeters, long lastUpdated) {
    public SpatialPosition {
        referencePoint = referencePoint == null ? "" : referencePoint.trim();
        if (referencePoint.isEmpty() || referencePoint.length() > 128
                || referencePoint.indexOf('\0') >= 0 || referencePoint.indexOf('\n') >= 0
                || referencePoint.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A valid coordinate reference point is required");
        }
        if (!Double.isFinite(xMeters) || !Double.isFinite(yMeters)
                || (zMeters != null && !Double.isFinite(zMeters))) {
            throw new IllegalArgumentException("Position coordinates must be finite");
        }
        if (lastUpdated <= 0) throw new IllegalArgumentException("lastUpdated must be positive");
    }
}
