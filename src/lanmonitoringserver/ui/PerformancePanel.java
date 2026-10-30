package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.TelemetryRingBuffer;
import lanmonitoringserver.model.TelemetrySample;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

/** Task-Manager-style view. Incoming samples continue buffering while rendering is paused. */
public final class PerformancePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int HISTORY_CAPACITY = 120;
    private static final double MIB = 1024d * 1024d;
    private final TelemetryRingBuffer history = new TelemetryRingBuffer(HISTORY_CAPACITY);
    private final Map<String, MetricView> views = new LinkedHashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel chartCards = new JPanel(cards);
    private final JLabel status = new JLabel("Đang chờ dữ liệu telemetry thực tế...");
    private final JToggleButton pause = new JToggleButton("Tạm dừng hiển thị");
    private final Timer repaintTimer;
    private String selected = "CPU";
    private long lastRenderedTimestamp;

    public PerformancePanel() {
        super(new BorderLayout(0, 10));
        setBackground(DashboardColors.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createToolbar(), BorderLayout.NORTH);
        register("CPU", view("CPU Usage", "%", new String[]{"CPU %"},
                new Color[]{new Color(0x00A6A6)}, true));
        register("Memory", view("Memory Usage", "%", new String[]{"RAM %"},
                new Color[]{new Color(0x7B61FF)}, true));
        register("GPU", view("GPU Performance", "% / °C", new String[]{"GPU %", "Temperature °C"},
                new Color[]{new Color(0xE05A87), new Color(0xF5A623)}, true));
        register("Disk", view("Disk Performance", "% / MB/s",
                new String[]{"Active %", "Read MB/s", "Write MB/s"},
                new Color[]{new Color(0x3BA272), new Color(0x5470C6), new Color(0xEE6666)}, false));
        register("Network", view("Network Throughput", "MB/s",
                new String[]{"Download MB/s", "Upload MB/s"},
                new Color[]{new Color(0x2F80ED), new Color(0xF2994A)}, false));
        add(chartCards, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        repaintTimer = new Timer(500, event -> renderSelected());
        repaintTimer.setCoalesce(true);
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.setOpaque(false);
        for (String name : new String[]{"CPU", "Memory", "GPU", "Disk", "Network"}) {
            JButton button = new JButton(name);
            button.addActionListener(event -> select(name));
            toolbar.add(button);
        }
        pause.addActionListener(event -> pause.setText(pause.isSelected()
                ? "Tiếp tục hiển thị" : "Tạm dừng hiển thị"));
        toolbar.add(pause);
        return toolbar;
    }

    private void register(String name, MetricView metricView) {
        views.put(name, metricView);
        chartCards.add(metricView.panel, name);
    }

    private void select(String name) {
        selected = name;
        cards.show(chartCards, name);
        lastRenderedTimestamp = 0;
        renderSelected();
    }

    public void setHistory(List<TelemetrySample> samples) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setHistory(samples));
            return;
        }
        history.clear();
        for (TelemetrySample sample : samples) history.add(sample);
        lastRenderedTimestamp = 0;
        renderSelected();
    }

    public void accept(TelemetrySample sample) { history.add(sample); }

    private void renderSelected() {
        if (pause.isSelected()) return;
        List<TelemetrySample> samples = history.snapshot();
        if (samples.isEmpty()) return;
        TelemetrySample latest = samples.get(samples.size() - 1);
        if (latest.timestamp() == lastRenderedTimestamp) {
            if (latest.isStale(System.currentTimeMillis())) {
                status.setText("STALE • Không nhận được telemetry mới đúng hạn");
                status.setForeground(DashboardColors.WARNING);
            }
            return;
        }
        MetricView metricView = views.get(selected);
        metricView.clear();
        long cutoff = latest.timestamp() - 60_000L;
        for (TelemetrySample sample : samples) {
            if (sample.timestamp() >= cutoff) addSelected(metricView, sample);
        }
        lastRenderedTimestamp = latest.timestamp();
        showStatus(latest);
    }

    private void addSelected(MetricView view, TelemetrySample sample) {
        switch (selected) {
            case "CPU" -> view.add(sample.timestamp(), sample.cpuUsagePercent());
            case "Memory" -> view.add(sample.timestamp(), sample.memoryUsagePercent());
            case "GPU" -> view.add(sample.timestamp(), sample.gpuUsagePercent(), sample.gpuTemperatureCelsius());
            case "Disk" -> view.add(sample.timestamp(), sample.diskActivePercent(),
                    sample.diskReadBytesPerSecond() / MIB, sample.diskWriteBytesPerSecond() / MIB);
            case "Network" -> view.add(sample.timestamp(), sample.networkDownloadBytesPerSecond() / MIB,
                    sample.networkUploadBytesPerSecond() / MIB);
            default -> { }
        }
    }

    private void showStatus(TelemetrySample sample) {
        String text = switch (selected) {
            case "CPU" -> String.format("CPU %.1f%% • %d lõi / %d luồng%s", sample.cpuUsagePercent(),
                    sample.physicalCores(), sample.logicalProcessors(), sample.cpuFrequencyMhz() == null
                            ? "" : String.format(" • %.0f MHz", sample.cpuFrequencyMhz()));
            case "Memory" -> String.format("RAM %.1f%% • %.1f / %.1f GiB", sample.memoryUsagePercent(),
                    sample.memoryUsedBytes() / 1073741824d, sample.memoryTotalBytes() / 1073741824d);
            case "GPU" -> sample.gpuStatus() == MeasurementStatus.OK
                    ? "GPU " + number(sample.gpuUsagePercent(), "%") + " • VRAM "
                            + bytes(sample.vramUsedBytes(), sample.vramTotalBytes()) + " • "
                            + number(sample.gpuTemperatureCelsius(), " °C")
                    : "GPU: UNSUPPORTED - " + sample.gpuDetail();
            case "Disk" -> String.format("Active %s • Read %.2f MB/s • Write %.2f MB/s • Capacity %s",
                    number(sample.diskActivePercent(), "%"), sample.diskReadBytesPerSecond() / MIB,
                    sample.diskWriteBytesPerSecond() / MIB, number(sample.diskCapacityUsagePercent(), "%"));
            case "Network" -> String.format("%s • Download %.2f MB/s • Upload %.2f MB/s",
                    sample.networkInterface(), sample.networkDownloadBytesPerSecond() / MIB,
                    sample.networkUploadBytesPerSecond() / MIB);
            default -> "";
        };
        boolean stale = sample.isStale(System.currentTimeMillis());
        status.setText((stale ? "STALE • " : "LIVE • ") + text);
        status.setForeground(stale ? DashboardColors.WARNING : DashboardColors.TEXT);
    }

    private static String number(Number value, String suffix) {
        return value == null ? "UNSUPPORTED" : String.format("%.1f%s", value.doubleValue(), suffix);
    }
    private static String bytes(Long used, Long total) {
        return used == null || total == null ? "UNSUPPORTED"
                : String.format("%.0f / %.0f MiB", used / MIB, total / MIB);
    }

    private static MetricView view(String title, String axis, String[] names, Color[] colors, boolean percent) {
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        TimeSeries[] series = new TimeSeries[names.length];
        for (int index = 0; index < names.length; index++) {
            series[index] = new TimeSeries(names[index]);
            series[index].setMaximumItemCount(HISTORY_CAPACITY);
            dataset.addSeries(series[index]);
        }
        JFreeChart chart = ChartFactory.createTimeSeriesChart(title, "Time", axis, dataset, true, true, false);
        chart.setBackgroundPaint(DashboardColors.SURFACE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(0xF7F9FC));
        plot.setDomainGridlinePaint(new Color(0xDDE3EA));
        plot.setRangeGridlinePaint(new Color(0xDDE3EA));
        ((DateAxis) plot.getDomainAxis()).setFixedAutoRange(60_000d);
        if (percent) ((NumberAxis) plot.getRangeAxis()).setRange(0d, 100d);
        for (int index = 0; index < colors.length; index++) {
            plot.getRenderer().setSeriesPaint(index, colors[index]);
            plot.getRenderer().setSeriesStroke(index, new java.awt.BasicStroke(2f));
        }
        ChartPanel panel = new ChartPanel(chart, true);
        panel.setMouseWheelEnabled(true);
        panel.setPopupMenu(null);
        return new MetricView(panel, series);
    }

    @Override public void addNotify() { super.addNotify(); if (repaintTimer != null) repaintTimer.start(); }
    @Override public void removeNotify() { if (repaintTimer != null) repaintTimer.stop(); super.removeNotify(); }
    int historySize() { return history.size(); }
    void setDisplayPaused(boolean paused) {
        pause.setSelected(paused);
        pause.setText(paused ? "Tiếp tục hiển thị" : "Tạm dừng hiển thị");
    }
    boolean isDisplayPaused() { return pause.isSelected(); }

    private record MetricView(ChartPanel panel, TimeSeries[] series) {
        void clear() { for (TimeSeries value : series) value.clear(); }
        void add(long timestamp, Double... values) {
            for (int index = 0; index < series.length; index++) {
                Double value = index < values.length ? values[index] : null;
                if (value != null && Double.isFinite(value)) {
                    series[index].addOrUpdate(new Millisecond(new java.util.Date(timestamp)), value);
                }
            }
        }
    }
}
