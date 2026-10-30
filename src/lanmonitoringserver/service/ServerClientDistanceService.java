package lanmonitoringserver.service;

import java.io.IOException;
import lanmonitoringserver.model.DistanceDataSource;
import lanmonitoringserver.model.ServerClientDistance;
import lanmonitoringserver.model.ServerClientDistanceMethod;
import lanmonitoringserver.model.ServerClientDistanceStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.EstimatedClientPosition;
import lanmonitoringserver.model.MeasurementStatus;

/** Computes only scientifically valid Server-to-Client distances. */
public final class ServerClientDistanceService {
    private final ServerClientDistanceStore store;
    private final java.util.concurrent.ConcurrentHashMap<String, EstimatedClientPosition> estimates =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ServerClientDistanceService(ServerClientDistanceStore store) {
        if (store == null) throw new IllegalArgumentException("Distance store is required");
        this.store = store;
    }

    public synchronized ServerClientDistance distanceFor(String clientId) {
        SpatialPosition server = store.serverPosition();
        SpatialPosition client = store.clientPosition(clientId);
        ServerClientDistanceStore.ManualMeasurement manual = store.manualMeasurement(clientId);
        if (manual != null) {
            return new ServerClientDistance(clientId, manual.distanceMeters(),
                    ServerClientDistanceMethod.MANUAL_MEASUREMENT,
                    DistanceDataSource.ADMIN_MANUAL_MEASUREMENT,
                    ServerClientDistanceStatus.OK, manual.estimatedErrorMeters(),
                    manual.measuredAt(), "Direct Server-to-Client manual measurement",
                    server, client);
        }
        if (server != null && client != null
                && server.referencePoint().equals(client.referencePoint())) {
            double dx = client.xMeters() - server.xMeters();
            double dy = client.yMeters() - server.yMeters();
            double squared = dx * dx + dy * dy;
            if (server.zMeters() != null && client.zMeters() != null) {
                double dz = client.zMeters() - server.zMeters();
                squared += dz * dz;
            }
            return new ServerClientDistance(clientId, Math.sqrt(squared),
                    ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                    DistanceDataSource.ADMIN_COORDINATES, ServerClientDistanceStatus.OK,
                    null, Math.max(server.lastUpdated(), client.lastUpdated()),
                    server.referencePoint(), server, client);
        }
        EstimatedClientPosition estimated = estimates.get(clientId);
        SpatialPosition inferred = estimated == null ? null : estimated.position();
        if (estimated != null) {
            MeasurementStatus effectiveStatus = estimated.statusAt(System.currentTimeMillis());
            if (effectiveStatus != MeasurementStatus.OK) {
                return new ServerClientDistance(clientId, null,
                        ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                        DistanceDataSource.WIFI_FINGERPRINT, distanceStatus(effectiveStatus),
                        estimated.estimatedErrorMeters(), inferred == null ? 0 : inferred.lastUpdated(),
                        inferred == null ? server == null ? "" : server.referencePoint()
                                : inferred.referencePoint(), server, inferred);
            }
        }
        if (server != null && inferred != null
                && server.referencePoint().equals(inferred.referencePoint())) {
            double dx = inferred.xMeters() - server.xMeters();
            double dy = inferred.yMeters() - server.yMeters();
            double squared = dx * dx + dy * dy;
            if (server.zMeters() != null && inferred.zMeters() != null) {
                double dz = inferred.zMeters() - server.zMeters();
                squared += dz * dz;
            }
            return new ServerClientDistance(clientId, Math.sqrt(squared),
                    ServerClientDistanceMethod.POSITION_BASED_ESTIMATION,
                    DistanceDataSource.WIFI_FINGERPRINT, ServerClientDistanceStatus.OK,
                    estimated.estimatedErrorMeters(), inferred.lastUpdated(),
                    inferred.referencePoint(), server, inferred);
        }
        return ServerClientDistance.unavailable(clientId, server,
                client == null ? inferred : client);
    }

    public void updateEstimatedPosition(String clientId, EstimatedClientPosition estimate) {
        if (estimate == null) estimates.remove(clientId);
        else if (!clientId.equals(estimate.clientId())) {
            throw new IllegalArgumentException("Estimate belongs to another Client");
        } else estimates.put(clientId, estimate);
    }

    private static ServerClientDistanceStatus distanceStatus(MeasurementStatus status) {
        return switch (status) {
            case STALE -> ServerClientDistanceStatus.STALE;
            case UNSUPPORTED -> ServerClientDistanceStatus.UNSUPPORTED;
            case ERROR -> ServerClientDistanceStatus.ERROR;
            case NOT_AVAILABLE -> ServerClientDistanceStatus.NOT_AVAILABLE;
            case OK -> throw new IllegalArgumentException("OK is not an unavailable distance status");
        };
    }

    public SpatialPosition serverPosition() { return store.serverPosition(); }
    public void setServerPosition(SpatialPosition value) throws IOException { store.setServerPosition(value); }
    public void clearServerPosition() throws IOException { store.clearServerPosition(); }
    public void setClientPosition(String clientId, SpatialPosition value) throws IOException {
        store.setClientPosition(clientId, value);
    }
    public void setManualMeasurement(String clientId, double meters, Double errorMeters,
            long measuredAt) throws IOException {
        store.setManualMeasurement(clientId, meters, errorMeters, measuredAt);
    }
    public void clearClient(String clientId) throws IOException { store.clearClient(clientId); }
}
