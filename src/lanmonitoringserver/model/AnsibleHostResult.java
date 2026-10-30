package lanmonitoringserver.model;

public record AnsibleHostResult(String inventoryName, AnsiblePlatform platform,
        AnsibleHostStatus status, boolean changed, String sha256, String detail) {
    public AnsibleHostResult {
        sha256 = sha256 == null ? "" : sha256;
        detail = detail == null ? "" : detail;
    }
}
