package lanmonitoringserver.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.Color;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ClientSnapshot;
import lanmonitoringserver.model.ClientStatus;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.EstimatedClientPosition;
import lanmonitoringserver.model.FingerprintSample;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.SpatialPosition;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.model.WirelessConfidence;
import lanmonitoringserver.model.WirelessSignalSample;

public final class WifiFingerprintPanelTest {
    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                long now = System.currentTimeMillis();
                WifiFingerprintPanel panel = new WifiFingerprintPanel();
                ClientSnapshot okClient = new ClientSnapshot("client-ok", "host-ok", "10.0.0.2",
                        "Windows", ClientStatus.ONLINE, 4L, 1, 2, null, null, now, null);
                ClientSnapshot staleClient = new ClientSnapshot("client-stale", "host-stale", "10.0.0.3",
                        "Windows", ClientStatus.OFFLINE, 4L, 1, 2, null, null, now, null);
                ClientSnapshot unsupportedClient = new ClientSnapshot("client-unsupported",
                        "host-unsupported", "10.0.0.4", "Linux", ClientStatus.ONLINE,
                        4L, 1, 2, null, null, now, null);
                ClientSnapshot unavailableClient = new ClientSnapshot("client-unavailable",
                        "host-unavailable", "10.0.0.5", "Test", ClientStatus.ONLINE,
                        4L, 1, 2, null, null, now, null);
                LinkedHashMap<String, Double> signals = new LinkedHashMap<>();
                signals.put(WirelessSignalSample.targetIdForBssid("aa:00:00:00:00:01"), -40d);
                signals.put(WirelessSignalSample.targetIdForBssid("aa:00:00:00:00:02"), -50d);
                signals.put(WirelessSignalSample.targetIdForBssid("aa:00:00:00:00:03"), -60d);
                FingerprintSample sample = new FingerprintSample("sample-a", "client-ok",
                        new SpatialPosition("lab", 1, 2, null, now), signals, now);
                List<AccessPointSignal> accessPoints = signals.entrySet().stream()
                        .map(value -> new AccessPointSignal(value.getKey(), value.getValue(), 2412)).toList();
                WifiScanSnapshot okScan = new WifiScanSnapshot(now, 10_000, MeasurementStatus.OK,
                        "wifi", accessPoints, "fixture", "fresh");
                WifiScanSnapshot staleScan = new WifiScanSnapshot(now - 40_000, 10_000,
                        MeasurementStatus.OK, "wifi", accessPoints, "fixture", "expired");
                WifiScanSnapshot unsupportedScan = new WifiScanSnapshot(now, 10_000,
                        MeasurementStatus.UNSUPPORTED, "wifi", List.of(), "fixture", "unsupported");
                EstimatedClientPosition okEstimate = estimate("client-ok", now, now + 30_000);
                EstimatedClientPosition staleEstimate = estimate("client-stale", now - 40_000, now - 1);
                panel.setData(List.of(okClient, staleClient, unsupportedClient, unavailableClient),
                        List.of(sample), id -> switch (id) {
                            case "client-ok" -> okScan;
                            case "client-stale" -> staleScan;
                            case "client-unsupported" -> unsupportedScan;
                            default -> null;
                        }, id -> switch (id) {
                            case "client-ok" -> okEstimate;
                            case "client-stale" -> staleEstimate;
                            case "client-unsupported" -> EstimatedClientPosition.unavailable(id,
                                    MeasurementStatus.UNSUPPORTED, 0, "unsupported");
                            default -> EstimatedClientPosition.unavailable(id,
                                    MeasurementStatus.NOT_AVAILABLE, 0, "no data");
                        });
                check(panel.clientRowCount() == 4, "Client scan table updates on EDT");
                check(panel.sampleRowCount() == 1, "Radio Map table updates on EDT");
                check(panel.clientCell(0, 1) == MeasurementStatus.OK
                                && panel.clientCell(0, 3) == MeasurementStatus.OK,
                        "fresh scan and position are explicitly OK");
                check(panel.clientCell(1, 1) == MeasurementStatus.STALE
                                && panel.clientCell(1, 3) == MeasurementStatus.STALE,
                        "expired scan and position are explicitly STALE");
                check(panel.clientCell(1, 4).toString().startsWith("HISTORICAL"),
                        "stale coordinate is history, not a realtime position");
                check(panel.clientCell(2, 1) == MeasurementStatus.UNSUPPORTED
                                && panel.clientCell(2, 3) == MeasurementStatus.UNSUPPORTED,
                        "unsupported remains distinct in the GUI");
                check(panel.clientCell(3, 1) == MeasurementStatus.NOT_AVAILABLE
                                && panel.clientCell(3, 3) == MeasurementStatus.NOT_AVAILABLE,
                        "missing data remains distinct in the GUI");

                int okViewRow = panel.clientsTableForTest().convertRowIndexToView(0);
                check(panel.clientsTableForTest().getSelectionModel().getSelectionMode()
                                == javax.swing.ListSelectionModel.SINGLE_SELECTION
                                && panel.clientsTableForTest().getRowSelectionAllowed()
                                && !panel.clientsTableForTest().getColumnSelectionAllowed(),
                        "table is configured for one-click whole-row selection");
                panel.clientsTableForTest().changeSelection(okViewRow, 0, false, false);
                check("client-ok".equals(panel.selectedClientIdForTest()),
                        "JTable selection resolves the stable Client ID");
                panel.clientsTableForTest().getActionMap().get("selectNextRow").actionPerformed(
                        new java.awt.event.ActionEvent(panel.clientsTableForTest(), 0, "selectNextRow"));
                check("client-stale".equals(panel.selectedClientIdForTest()),
                        "keyboard row navigation changes the selected Client");
                panel.clientsTableForTest().changeSelection(okViewRow, 0, false, false);
                check(panel.addButtonEnabledForTest(),
                        "Add Survey Sample is enabled when a Client is selected");
                check(panel.clientsTableForTest().getSelectionBackground().equals(DashboardColors.PRIMARY)
                                && panel.clientsTableForTest().getSelectionForeground().equals(Color.WHITE),
                        "selected Client row has an explicit high-contrast highlight");

                AtomicReference<String> addedClient = new AtomicReference<>();
                AtomicReference<SpatialPosition> addedPosition = new AtomicReference<>();
                SpatialPosition surveyPosition = new SpatialPosition("lab", 4, 5, null, now + 1);
                panel.setActions((clientId, position) -> {
                    addedClient.set(clientId); addedPosition.set(position);
                }, null);
                panel.setSurveyPromptForTest(() -> surveyPosition);
                panel.clickAddForTest();
                check("client-ok".equals(addedClient.get())
                                && surveyPosition.equals(addedPosition.get()),
                        "Add Survey Sample button submits the selected stable Client ID");

                ClientSnapshot disconnectedOkClient = new ClientSnapshot("client-ok", "host-ok",
                        "10.0.0.2", "Windows", ClientStatus.OFFLINE, 4L, 1, 2,
                        null, null, now + 2, null);
                panel.setData(List.of(unavailableClient, disconnectedOkClient, staleClient,
                                unsupportedClient), List.of(sample), id -> switch (id) {
                            case "client-ok" -> okScan;
                            case "client-stale" -> staleScan;
                            case "client-unsupported" -> unsupportedScan;
                            default -> null;
                        }, id -> switch (id) {
                            case "client-ok" -> okEstimate;
                            case "client-stale" -> staleEstimate;
                            case "client-unsupported" -> EstimatedClientPosition.unavailable(id,
                                    MeasurementStatus.UNSUPPORTED, 0, "unsupported");
                            default -> EstimatedClientPosition.unavailable(id,
                                    MeasurementStatus.NOT_AVAILABLE, 0, "no data");
                        });
                check("client-ok".equals(panel.selectedClientIdForTest())
                                && panel.clientsTableForTest().getSelectedRow() >= 0,
                        "realtime refresh/reorder and disconnect preserve selection by Client ID");

                panel.clientsTableForTest().getRowSorter().toggleSortOrder(0);
                check("client-ok".equals(panel.selectedClientIdForTest()),
                        "sorting preserves the selected Client mapping");
                addedClient.set(null);
                panel.clickAddForTest();
                check("client-ok".equals(addedClient.get()),
                        "Add Survey Sample remains mapped correctly after sorting and refresh");

                panel.setData(List.of(unavailableClient, staleClient, unsupportedClient),
                        List.of(sample), id -> null, id -> null);
                check(panel.selectedClientIdForTest() == null
                                && panel.clientsTableForTest().getSelectedRow() == -1
                                && !panel.addButtonEnabledForTest(),
                        "selection clears and Add disables when the selected Client disappears");
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("WifiFingerprintPanelTest: PASS");
    }
    private static EstimatedClientPosition estimate(String clientId, long measuredAt, long validUntil) {
        return new EstimatedClientPosition(clientId,
                new SpatialPosition("lab", 1, 2, null, measuredAt), MeasurementStatus.OK,
                WirelessConfidence.MEDIUM, 1.0, 3, 3, 2.0, validUntil,
                "fixture", "position estimate");
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
