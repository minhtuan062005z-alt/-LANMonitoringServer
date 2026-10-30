package lanmonitoringserver.ui;

import java.util.Set;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.*;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.util.SwingTextLimiter;

/** Headless EDT checks for bounded logs, timer lifecycle and read-only alert UI. */
public final class Phase9UiStabilityTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea();
            SwingTextLimiter.append(area, "x".repeat(500) + "tail", 100);
            check(area.getDocument().getLength() == 100 && area.getText().endsWith("tail"),
                    "Swing log retains a bounded newest tail");

            WirelessNetworkPanel wireless = new WirelessNetworkPanel();
            check(!wireless.calibrationEnabled(),
                    "calibration is disabled until an AP RSSI sample is available");
            check(!wireless.staleTimerRunning(), "wireless stale timer is idle while undisplayed");
            wireless.addNotify();
            check(wireless.staleTimerRunning(), "wireless stale timer starts with component lifecycle");
            wireless.removeNotify();
            check(!wireless.staleTimerRunning(), "wireless stale timer stops when component is removed");
            long now = System.currentTimeMillis();
            WirelessSignalSample signal = new WirelessSignalSample(now, 2_000,
                    MeasurementStatus.OK, WirelessNetworkType.WIFI, "Realtek RTL8852BE WiFi 6",
                    null, null, WirelessSignalSample.targetIdForBssid("aa:bb:cc:dd:ee:ff"),
                    -38d, 100d, WirelessMeasurementTarget.ACCESS_POINT,
                    WirelessRttCapability.UNKNOWN, WirelessConfidence.LOW,
                    "Windows netsh wlan show interfaces", "direct fixture observation");
            WirelessDistanceEstimate unavailable = WirelessDistanceEstimate.unavailable(now,
                    MeasurementStatus.UNSUPPORTED, WirelessMeasurementTarget.ACCESS_POINT,
                    "Calibration required");
            wireless.accept(new WirelessObservation(signal, -38d, unavailable));
            check(wireless.displayedSignalText().contains("-38")
                            && wireless.displayedSignalText().contains("100"),
                    "Network panel shows independent RSSI and Link Quality values");
            check(!wireless.displayedLastUpdatedText().contains("NOT_AVAILABLE"),
                    "Network panel displays the received timestamp");
            check(wireless.rssiPointCount() == 1,
                    "Wi-Fi signal chart receives the observation on EDT");
            check(wireless.calibrationEnabled(),
                    "calibration becomes available for a current AP RSSI target");
            wireless.setHistory(java.util.List.of());
            check(!wireless.calibrationEnabled()
                            && wireless.displayedSignalText().contains("NOT_AVAILABLE"),
                    "empty Client history resets UI and disables calibration");

            AnsibleManagementPanel ansible = new AnsibleManagementPanel(
                    new AuthorizationContext("admin", UserRole.ADMIN, Set.of()));
            for (int index = 0; index < 250; index++) ansible.appendLog("L".repeat(1_000));
            check(ansible.logLength() <= SwingTextLimiter.DEFAULT_MAX_CHARACTERS,
                    "Ansible UI log is bounded");

            AlertManagementPanel viewerAlerts = new AlertManagementPanel(false);
            check(!viewerAlerts.managementControlsEnabled(), "VIEWER alert controls are read-only");
        });
        System.out.println("Phase9UiStabilityTest: PASS");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
