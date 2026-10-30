package lanmonitoringserver.model;

public enum AnsibleHostStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    UNREACHABLE,
    CANCELLED,
    TIMED_OUT
}
