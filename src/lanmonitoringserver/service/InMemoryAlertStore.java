package lanmonitoringserver.service;

public final class InMemoryAlertStore implements AlertStore {
    private AlertStoreSnapshot snapshot = AlertStoreSnapshot.empty();
    @Override public synchronized AlertStoreSnapshot load() { return snapshot; }
    @Override public synchronized void save(AlertStoreSnapshot updated) { snapshot = updated; }
}
