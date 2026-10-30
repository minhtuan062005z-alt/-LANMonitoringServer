package lanmonitoringserver.model;

import java.util.Map;

public record AnsibleJobResult(String jobId, AnsibleAction action, String sourceSha256,
        long startedAt, long finishedAt, int exitCode, boolean timedOut, boolean cancelled,
        Map<String, AnsibleHostResult> hosts, String log) {
    public AnsibleJobResult {
        hosts = Map.copyOf(hosts);
        log = log == null ? "" : log;
    }

    public boolean successful() {
        return !hosts.isEmpty() && hosts.values().stream()
                .allMatch(result -> result.status() == AnsibleHostStatus.SUCCESS);
    }
}
