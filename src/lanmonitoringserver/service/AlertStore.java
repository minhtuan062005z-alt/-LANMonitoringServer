package lanmonitoringserver.service;

import java.io.IOException;

public interface AlertStore {
    AlertStoreSnapshot load() throws IOException;
    void save(AlertStoreSnapshot snapshot) throws IOException;
}
