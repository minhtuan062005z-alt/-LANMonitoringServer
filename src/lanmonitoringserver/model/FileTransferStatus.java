package lanmonitoringserver.model;

public enum FileTransferStatus {
    QUEUED,
    OFFERED,
    IN_PROGRESS,
    VERIFYING,
    SUCCESS,
    FAILED,
    CANCELLED
}
