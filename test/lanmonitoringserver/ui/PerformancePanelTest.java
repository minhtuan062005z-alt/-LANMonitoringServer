package lanmonitoringserver.ui;

import javax.swing.SwingUtilities;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetrySample;

public final class PerformancePanelTest {
    public static void main(String[] args) throws Exception {
        PerformancePanel[] panel = new PerformancePanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new PerformancePanel());
        for (int index = 0; index < 150; index++) panel[0].accept(sample(index));
        SwingUtilities.invokeAndWait(() -> {
            check(panel[0].historySize() == 120, "UI telemetry history must be bounded");
            panel[0].setDisplayPaused(true);
            check(panel[0].isDisplayPaused(), "display can be paused");
            panel[0].accept(sample(151));
            check(panel[0].historySize() == 120, "pause must not stop telemetry buffering");
            panel[0].setDisplayPaused(false);
            panel[0].removeNotify();
        });
        System.out.println("PerformancePanelTest: PASS");
    }

    private static TelemetrySample sample(long timestamp) {
        return new TelemetrySample(timestamp, 1_000, MeasurementStatus.OK, 10, 2, 4, null,
                MeasurementStatus.OK, 20, 20, 80, 100,
                MeasurementStatus.UNSUPPORTED, null, null, null, null, "unsupported",
                MeasurementStatus.OK, null, 0, 0, 40d,
                MeasurementStatus.OK, 0, 0, "test", "test");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
