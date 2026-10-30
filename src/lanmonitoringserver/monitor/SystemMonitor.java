package lanmonitoringserver.monitor;

import lanmonitoringserver.model.SystemStats;

public interface SystemMonitor {
    SystemStats read();
}
