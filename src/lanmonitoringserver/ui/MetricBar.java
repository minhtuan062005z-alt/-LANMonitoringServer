package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.MetricReading;

final class MetricBar extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JLabel nameLabel = new JLabel();
    private final JLabel valueLabel = new JLabel();
    private final JProgressBar progress = new JProgressBar(0, 100);

    MetricBar(String name) {
        super(new BorderLayout(8, 3));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        nameLabel.setText(name);
        nameLabel.setForeground(DashboardColors.MUTED);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 12f));
        valueLabel.setForeground(DashboardColors.TEXT);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 12f));
        valueLabel.setHorizontalAlignment(JLabel.RIGHT);
        progress.setPreferredSize(new Dimension(10, 7));
        progress.setBorder(BorderFactory.createEmptyBorder());
        progress.setBackground(new Color(229, 234, 241));
        progress.setStringPainted(false);
        JPanel labels = new JPanel(new BorderLayout());
        labels.setOpaque(false);
        labels.add(nameLabel, BorderLayout.WEST);
        labels.add(valueLabel, BorderLayout.EAST);
        add(labels, BorderLayout.NORTH);
        add(progress, BorderLayout.CENTER);
    }

    void setValue(Double value) {
        setReading(value == null ? null
                : MetricReading.ok(value, System.currentTimeMillis(),
                        lanmonitoringserver.model.MetricSource.UNKNOWN));
    }

    void setReading(MetricReading reading) {
        Double value = reading == null ? null : reading.value();
        MeasurementStatus status = reading == null
                ? MeasurementStatus.UNSUPPORTED : reading.status();
        String source = reading == null ? "UNKNOWN" : reading.source().name();
        setToolTipText("Source: " + source + (reading == null || reading.measuredAt() == 0
                ? "" : " | Timestamp: " + reading.measuredAt()));
        if (status == MeasurementStatus.STALE) {
            progress.setVisible(value != null && Double.isFinite(value));
            if (progress.isVisible()) progress.setValue((int) Math.round(value));
            progress.setForeground(DashboardColors.OFFLINE);
            valueLabel.setForeground(DashboardColors.WARNING);
            valueLabel.setText(value == null ? "STALE" : String.format("STALE · %.1f%%", value));
            return;
        }
        if (status != MeasurementStatus.OK) {
            progress.setVisible(false);
            valueLabel.setText(status == MeasurementStatus.ERROR ? "ERROR" : "N/A");
            valueLabel.setForeground(DashboardColors.MUTED);
            return;
        }
        boolean available = value != null && Double.isFinite(value);
        progress.setVisible(available);
        if (!available) {
            valueLabel.setText("Không có dữ liệu");
            valueLabel.setForeground(DashboardColors.MUTED);
            return;
        }
        int rounded = (int) Math.round(Math.max(0, Math.min(100, value)));
        progress.setValue(rounded);
        Color color = rounded >= 90 ? DashboardColors.CRITICAL
                : rounded >= 75 ? DashboardColors.WARNING : DashboardColors.PRIMARY;
        progress.setForeground(color);
        valueLabel.setForeground(color);
        valueLabel.setText(String.format("%.1f%%", value));
    }

    String displayedValue() { return valueLabel.getText(); }
}
