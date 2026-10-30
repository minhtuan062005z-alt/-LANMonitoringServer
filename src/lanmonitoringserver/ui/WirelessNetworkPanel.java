package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.text.DecimalFormat;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import lanmonitoringserver.model.*;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

/** Displays network latency, direct radio observations, and derived range as separate values. */
public final class WirelessNetworkPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DecimalFormat N = new DecimalFormat("0.00");
    private final JLabel network = new JLabel("Network Type: NOT_AVAILABLE");
    private final JLabel identity = new JLabel("SSID / BSSID: Hidden or unavailable");
    private final JLabel signal = new JLabel("Signal: NOT_AVAILABLE");
    private final JLabel rtt = new JLabel("Network RTT: NOT_AVAILABLE");
    private final JLabel distance = new JLabel("Client - Hotspot (RSSI estimate): NOT_AVAILABLE");
    private final JLabel method = new JLabel("Measurement Target / Method: NOT_AVAILABLE");
    private final JLabel confidence = new JLabel("Accuracy / Confidence: NOT_AVAILABLE");
    private final JLabel updated = new JLabel("Last Updated: NOT_AVAILABLE");
    private final TimeSeries rssi = new TimeSeries("RSSI (dBm)");
    private final TimeSeries quality = new TimeSeries("Link Quality (%)");
    private final TimeSeries ranges = new TimeSeries("Estimated Client-AP distance (m)");
    private final JButton calibrateButton = new JButton("Calibrate RSSI...");
    private Consumer<List<WirelessCalibrationPoint>> calibrate = ignored -> {};
    private volatile WirelessObservation latest;
    private final Timer staleTimer;

    public WirelessNetworkPanel() {
        super(new BorderLayout(8, 8));
        setBackground(DashboardColors.SURFACE);
        JPanel values = new JPanel(new GridLayout(0, 1, 3, 3)); values.setOpaque(false);
        for (JLabel label : new JLabel[]{network, identity, signal, rtt, distance, method, confidence, updated}) values.add(label);
        calibrateButton.setToolTipText("Waiting for a current RSSI sample with a valid Access Point target");
        calibrateButton.setEnabled(false);
        calibrateButton.addActionListener(event -> requestCalibration()); values.add(calibrateButton);
        JPanel charts = new JPanel(new GridLayout(1, 2, 8, 0)); charts.setOpaque(false);
        charts.add(new ChartPanel(createSignalChart()));
        charts.add(new ChartPanel(ChartFactory.createTimeSeriesChart("Client - Hotspot distance (estimated)", "Time", "meters",
                new TimeSeriesCollection(ranges), true, true, false)));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(values, BorderLayout.CENTER);
        top.add(new JLabel("RSSI estimates distance to PHONE HOTSPOT, not Windows Server."), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH); add(charts, BorderLayout.CENTER);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        staleTimer = new Timer(2_000, event -> { if (latest != null) show(latest); });
        staleTimer.setCoalesce(true);
    }
    private JFreeChart createSignalChart() {
        TimeSeriesCollection rssiData = new TimeSeriesCollection(rssi);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                "Wi-Fi signal", "Time", "RSSI (dBm)", rssiData, true, true, false);
        XYPlot plot = chart.getXYPlot();
        ((DateAxis) plot.getDomainAxis()).setFixedAutoRange(60_000d);
        NumberAxis qualityAxis = new NumberAxis("Link Quality (%)");
        qualityAxis.setRange(0d, 100d);
        plot.setRangeAxis(1, qualityAxis);
        plot.setDataset(1, new TimeSeriesCollection(quality));
        plot.mapDatasetToRangeAxis(1, 1);
        XYLineAndShapeRenderer qualityRenderer = new XYLineAndShapeRenderer(true, false);
        qualityRenderer.setSeriesPaint(0, DashboardColors.PRIMARY);
        plot.setRenderer(1, qualityRenderer);
        return chart;
    }
    public void setCalibrationAction(Consumer<List<WirelessCalibrationPoint>> value) { calibrate = value == null ? ignored -> {} : value; }
    public void setLatency(NetworkLatencySample value) { edt(() -> rtt.setText("Network RTT: " + (value == null ? "NOT_AVAILABLE" : value.roundTripMillis() + " ms (not physical distance)"))); }
    public void setHistory(List<WirelessObservation> values) { edt(() -> {
        rssi.clear(); quality.clear(); ranges.clear();
        if (values == null || values.isEmpty()) { resetWirelessDisplay(); return; }
        for (var value : values) append(value);
        show(values.get(values.size()-1));
    }); }
    public void accept(WirelessObservation value) { edt(() -> { append(value); show(value); }); }
    private void append(WirelessObservation value) {
        Millisecond time = new Millisecond(new java.util.Date(value.signal().timestamp()));
        if (value.signal().rssiDbm() != null) rssi.addOrUpdate(time, value.signal().rssiDbm());
        if (value.signal().linkQualityPercent() != null) quality.addOrUpdate(time, value.signal().linkQualityPercent());
        if (value.distance().estimatedMeters() != null) ranges.addOrUpdate(time, value.distance().estimatedMeters());
        rssi.setMaximumItemCount(120); quality.setMaximumItemCount(120); ranges.setMaximumItemCount(120);
    }
    private void show(WirelessObservation value) {
        latest = value;
        var s=value.signal(); var d=value.distance();
        network.setText("Network Type: "+s.networkType()+" | Adapter: "+text(s.adapter()));
        identity.setText("SSID / BSSID: "+text(s.ssid())+" / "+text(s.bssid()));
        signal.setText("Signal: RSSI "+number(s.rssiDbm()," dBm")+" | Link Quality "+number(s.linkQualityPercent(),"%"));
        boolean stale = s.isStale(System.currentTimeMillis());
        boolean canCalibrate = !stale && s.status() == MeasurementStatus.OK
                && s.target() == WirelessMeasurementTarget.ACCESS_POINT
                && !s.targetId().isBlank() && s.rssiDbm() != null;
        calibrateButton.setEnabled(canCalibrate);
        calibrateButton.setToolTipText(canCalibrate
                ? "Enter RSSI values measured at known Client-to-AP distances"
                : "Calibration needs a current RSSI dBm sample and a valid Access Point target");
        distance.setText("Client - Hotspot (RSSI estimate): "+(stale ? "NOT_AVAILABLE (STALE)"
                : d.estimatedMeters()==null?d.status():N.format(d.estimatedMeters())+" m ± "+N.format(d.uncertaintyMeters())+" m"));
        method.setText("Measurement Target / Method: "+s.target()+" / "+d.method()+" | FTM: "+s.rttCapability());
        confidence.setText("Accuracy / Confidence: model uncertainty / "+d.confidence()+" | "+d.detail());
        updated.setText("Last Updated: "+java.time.Instant.ofEpochMilli(s.timestamp())+(stale?" (STALE)":""));
    }
    private void requestCalibration() {
        if (!calibrateButton.isEnabled()) return;
        JTextArea input = new JTextArea("",8,24);
        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.add(new JLabel("<html>Enter at least two measurements from this Client to its current AP.<br>"
                + "One real <b>distanceMeters,rssiDbm</b> pair per line; do not enter sample values or RTT.</html>"),
                BorderLayout.NORTH);
        content.add(new JScrollPane(input), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this,content,"Measured Client-to-AP calibration",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        try { calibrate.accept(input.getText().lines().filter(x->!x.isBlank()).map(x->{ String[] p=x.trim().split(","); if(p.length!=2) throw new IllegalArgumentException("Use distance,rssi per line"); return new WirelessCalibrationPoint(Double.parseDouble(p[0].trim()),Double.parseDouble(p[1].trim())); }).toList()); }
        catch(RuntimeException ex){ JOptionPane.showMessageDialog(this,ex.getMessage(),"Calibration",JOptionPane.ERROR_MESSAGE); }
    }
    private void resetWirelessDisplay() {
        latest = null;
        network.setText("Network Type: NOT_AVAILABLE");
        identity.setText("SSID / BSSID: Hidden or unavailable");
        signal.setText("Signal: NOT_AVAILABLE");
        distance.setText("Client - Hotspot (RSSI estimate): NOT_AVAILABLE");
        method.setText("Measurement Target / Method: NOT_AVAILABLE");
        confidence.setText("Accuracy / Confidence: NOT_AVAILABLE");
        updated.setText("Last Updated: NOT_AVAILABLE");
        calibrateButton.setEnabled(false);
        calibrateButton.setToolTipText("Waiting for a current RSSI sample with a valid Access Point target");
    }
    private static String text(String value){return value==null||value.isBlank()?"Hidden/unavailable":value;}
    private static String number(Double value,String unit){return value==null?"UNSUPPORTED":N.format(value)+unit;}
    private static void edt(Runnable action){if(SwingUtilities.isEventDispatchThread())action.run();else SwingUtilities.invokeLater(action);}
    @Override public void addNotify() { super.addNotify(); staleTimer.start(); }
    @Override public void removeNotify() { staleTimer.stop(); super.removeNotify(); }
    boolean staleTimerRunning() { return staleTimer.isRunning(); }
    String displayedSignalText() { return signal.getText(); }
    String displayedLastUpdatedText() { return updated.getText(); }
    int rssiPointCount() { return rssi.getItemCount(); }
    boolean calibrationEnabled() { return calibrateButton.isEnabled(); }
}
