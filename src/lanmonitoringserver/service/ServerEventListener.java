package lanmonitoringserver.service;

import java.util.List;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.TelemetrySample;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.WirelessObservation;

public interface ServerEventListener {
    default void onLog(String message) {}
    default void onClientsChanged(List<ClientSnapshot> clients) {}
    default void onAcknowledgement(String clientId, String hostname, String requestId) {}
    default void onTelemetry(String clientId, TelemetrySample sample) {}
    default void onAlertsChanged(List<AlertRecord> alerts) {}
    default void onFileTransfersChanged(List<FileTransferProgress> transfers) {}
    default void onWirelessSignal(String clientId, WirelessObservation observation) {}
}
