package lanmonitoringserver.model;

public enum ClientConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED_BY_SERVER
}
