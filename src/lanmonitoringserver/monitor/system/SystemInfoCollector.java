package lanmonitoringserver.monitor.system;

import lanmonitoringserver.model.SystemInformation;

public interface SystemInfoCollector {
    SystemInformation collect();
    String collectorName();
}
