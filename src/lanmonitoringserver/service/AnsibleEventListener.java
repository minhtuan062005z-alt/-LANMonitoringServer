package lanmonitoringserver.service;

import lanmonitoringserver.model.AnsibleHostResult;

public interface AnsibleEventListener {
    default void onLog(String line) {}
    default void onHostResult(AnsibleHostResult result) {}
}
