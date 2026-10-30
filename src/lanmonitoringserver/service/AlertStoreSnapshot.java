package lanmonitoringserver.service;

import java.util.List;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertRule;

public record AlertStoreSnapshot(List<AlertRule> rules, List<AlertRecord> alerts) {
    public AlertStoreSnapshot {
        rules = List.copyOf(rules == null ? List.of() : rules);
        alerts = List.copyOf(alerts == null ? List.of() : alerts);
    }
    public static AlertStoreSnapshot empty() { return new AlertStoreSnapshot(List.of(), List.of()); }
}
