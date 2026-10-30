package lanmonitoringserver.service;

import lanmonitoringserver.model.ClientConnectionState;
import lanmonitoringserver.model.SystemStats;
import lanmonitoringserver.model.AlertNotification;
import lanmonitoringserver.model.ClientPermissions;
import lanmonitoringserver.model.FileTransferProgress;
import lanmonitoringserver.model.ReceivedFileInfo;

public interface ClientEventListener {
    default void onLog(String message) {}
    default void onStateChanged(ClientConnectionState state, String detail) {}
    default void onStats(SystemStats stats) {}
    default void onMessage(String content, String requestId) {}
    default void onAlert(AlertNotification alert) {}
    default void onAcknowledgementSent(String requestId) {}
    default void onDisconnectedByServer() {}
    default void onReconnectedAfterServerDisconnect() {}
    default void onPermissionsChanged(ClientPermissions permissions) {}
    default void onFileTransferProgress(FileTransferProgress progress) {}
    default void onFileReceived(ReceivedFileInfo file) {}
}
