package lanmonitoringserver.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.EstimatedClientPosition;
import lanmonitoringserver.model.FingerprintSample;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.model.WirelessConfidence;

/** Weighted KNN over RSSI vectors. Missing APs use an explicit -100 dBm floor. */
public final class WifiFingerprintEstimator {
    private static final double MISSING_RSSI_DBM = -100d;
    private static final int MIN_ACCESS_POINTS = 3;
    private static final int K = 3;

    public EstimatedClientPosition estimate(String clientId, WifiScanSnapshot scan,
            List<FingerprintSample> radioMap) {
        if (scan == null) return EstimatedClientPosition.unavailable(clientId,
                MeasurementStatus.NOT_AVAILABLE, 0, "No multi-AP scan has been received");
        if (scan.status() != MeasurementStatus.OK) return EstimatedClientPosition.unavailable(clientId,
                scan.status(), scan.accessPoints().size(), scan.detail());
        if (scan.isStale(System.currentTimeMillis())) return EstimatedClientPosition.unavailable(clientId,
                MeasurementStatus.STALE, scan.accessPoints().size(), "Multi-AP scan is stale");
        if (scan.accessPoints().size() < MIN_ACCESS_POINTS) return EstimatedClientPosition.unavailable(clientId,
                MeasurementStatus.NOT_AVAILABLE, scan.accessPoints().size(),
                "Fingerprinting requires at least three AP RSSI observations");
        if (radioMap == null || radioMap.isEmpty()) return EstimatedClientPosition.unavailable(clientId,
                MeasurementStatus.NOT_AVAILABLE, scan.accessPoints().size(), "Radio Map has no survey samples");

        Map<String, Double> query = new HashMap<>();
        for (AccessPointSignal signal : scan.accessPoints()) query.put(signal.targetId(), signal.rssiDbm());
        LinkedHashMap<String, List<FingerprintSample>> references = new LinkedHashMap<>();
        for (FingerprintSample sample : radioMap) {
            if (!sample.clientId().equals(clientId)) continue;
            references.computeIfAbsent(sample.position().referencePoint(),
                    ignored -> new ArrayList<>()).add(sample);
        }
        CandidateEstimate best = null;
        for (Map.Entry<String, List<FingerprintSample>> entry : references.entrySet()) {
            CandidateEstimate candidate = estimateReference(entry.getKey(), entry.getValue(), query,
                    scan.timestamp());
            if (candidate != null && (best == null || candidate.signalDistanceDb < best.signalDistanceDb)) {
                best = candidate;
            }
        }
        if (best == null) return EstimatedClientPosition.unavailable(clientId,
                MeasurementStatus.NOT_AVAILABLE, query.size(),
                "This Client has no Radio Map with three distinct points sharing enough APs");
        WirelessConfidence confidence = best.overlap >= 5 && best.signalDistanceDb <= 8 && best.errorMeters <= 2
                ? WirelessConfidence.HIGH : best.overlap >= 3 && best.signalDistanceDb <= 15
                && best.errorMeters <= 5 ? WirelessConfidence.MEDIUM : WirelessConfidence.LOW;
        return new EstimatedClientPosition(clientId, best.position, MeasurementStatus.OK, confidence,
                best.errorMeters, query.size(), best.neighbors, best.signalDistanceDb,
                scan.timestamp() + Math.max(30_000L, scan.intervalMillis() * 3L),
                "Wi-Fi fingerprint weighted KNN",
                "Position-based estimate from surveyed multi-AP RSSI; not direct Server-Client ranging");
    }

    private CandidateEstimate estimateReference(String reference, List<FingerprintSample> samples,
            Map<String, Double> query, long timestamp) {
        long distinct = samples.stream().map(value -> value.position().xMeters() + ":"
                + value.position().yMeters() + ":" + value.position().zMeters()).distinct().count();
        if (samples.size() < 3 || distinct < 3) return null;
        List<Neighbor> neighbors = new ArrayList<>();
        for (FingerprintSample sample : samples) {
            Set<String> overlap = new HashSet<>(query.keySet());
            overlap.retainAll(sample.rssiByTarget().keySet());
            if (overlap.size() < MIN_ACCESS_POINTS) continue;
            HashSet<String> union = new HashSet<>(query.keySet());
            union.addAll(sample.rssiByTarget().keySet());
            double squared = 0;
            for (String target : union) {
                double difference = query.getOrDefault(target, MISSING_RSSI_DBM)
                        - sample.rssiByTarget().getOrDefault(target, MISSING_RSSI_DBM);
                squared += difference * difference;
            }
            neighbors.add(new Neighbor(sample, Math.sqrt(squared / union.size()), overlap.size()));
        }
        neighbors.sort(Comparator.comparingDouble(Neighbor::signalDistance));
        if (neighbors.size() < 3) return null;
        neighbors = neighbors.subList(0, Math.min(K, neighbors.size()));
        double weightSum = 0, x = 0, y = 0, z = 0;
        boolean allZ = true;
        for (Neighbor neighbor : neighbors) {
            double weight = 1d / Math.pow(neighbor.signalDistance + 1d, 2);
            weightSum += weight;
            x += neighbor.sample.position().xMeters() * weight;
            y += neighbor.sample.position().yMeters() * weight;
            if (neighbor.sample.position().zMeters() == null) allZ = false;
            else z += neighbor.sample.position().zMeters() * weight;
        }
        x /= weightSum; y /= weightSum;
        Double predictedZ = allZ ? z / weightSum : null;
        double spreadSquared = 0;
        for (Neighbor neighbor : neighbors) {
            double weight = 1d / Math.pow(neighbor.signalDistance + 1d, 2);
            double dx = neighbor.sample.position().xMeters() - x;
            double dy = neighbor.sample.position().yMeters() - y;
            double dz = predictedZ == null ? 0 : neighbor.sample.position().zMeters() - predictedZ;
            spreadSquared += weight * (dx * dx + dy * dy + dz * dz);
        }
        double resolutionFloor = Math.max(0.25, mapResolution(samples) / 2d);
        double error = Math.max(resolutionFloor, Math.sqrt(spreadSquared / weightSum));
        SpatialPosition position = new SpatialPosition(reference, x, y, predictedZ, timestamp);
        return new CandidateEstimate(position, error, neighbors.get(0).signalDistance,
                neighbors.get(0).overlap, neighbors.size());
    }

    private static double mapResolution(List<FingerprintSample> samples) {
        double total = 0; int count = 0;
        for (FingerprintSample first : samples) {
            double nearest = Double.POSITIVE_INFINITY;
            for (FingerprintSample second : samples) {
                if (first == second) continue;
                double dx = first.position().xMeters() - second.position().xMeters();
                double dy = first.position().yMeters() - second.position().yMeters();
                double distance = Math.hypot(dx, dy);
                if (distance > 0) nearest = Math.min(nearest, distance);
            }
            if (Double.isFinite(nearest)) { total += nearest; count++; }
        }
        return count == 0 ? 1d : total / count;
    }

    private record Neighbor(FingerprintSample sample, double signalDistance, int overlap) {}
    private record CandidateEstimate(SpatialPosition position, double errorMeters,
            double signalDistanceDb, int overlap, int neighbors) {}
}
