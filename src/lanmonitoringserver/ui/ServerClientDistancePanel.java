package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.ServerClientDistance;
import lanmonitoringserver.model.SpatialPosition;

/** Editor/view for physical Server-to-Client distance, separate from Wireless AP estimates. */
public final class ServerClientDistancePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private final JLabel serverPosition = new JLabel();
    private final JLabel clientPosition = new JLabel();
    private final JLabel distance = new JLabel();
    private final JLabel method = new JLabel();
    private final JLabel source = new JLabel();
    private final JLabel status = new JLabel();
    private final JLabel error = new JLabel();
    private final JLabel updated = new JLabel();
    private final JLabel reference = new JLabel();
    private ServerClientDistance value;
    private Consumer<SpatialPosition> serverAction = ignored -> {};
    private Consumer<SpatialPosition> clientAction = ignored -> {};
    private Consumer<ManualDistanceInput> manualAction = ignored -> {};
    private Runnable clearAction = () -> {};
    private Runnable clearServerAction = () -> {};

    public ServerClientDistancePanel() {
        super(new BorderLayout(0, 14));
        setBackground(DashboardColors.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JPanel values = new JPanel(new GridLayout(0, 1, 0, 9));
        values.setOpaque(false);
        for (JLabel label : new JLabel[]{serverPosition, clientPosition, distance, method,
                source, status, error, updated, reference}) {
            label.setForeground(DashboardColors.TEXT);
            values.add(label);
        }
        JButton serverButton = new JButton("Set Server Position");
        JButton clearServerButton = new JButton("Clear Server Position");
        JButton clientButton = new JButton("Edit Client Position");
        JButton manualButton = new JButton("Enter Measured Distance");
        JButton clearButton = new JButton("Clear Distance");
        serverButton.addActionListener(event -> editPosition(true));
        clearServerButton.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Clear the shared Server position? Coordinate distances will become NOT_AVAILABLE.",
                    "Clear Server Position", JOptionPane.YES_NO_OPTION)
                    == JOptionPane.YES_OPTION) clearServerAction.run();
        });
        clientButton.addActionListener(event -> editPosition(false));
        manualButton.addActionListener(event -> enterManualDistance());
        clearButton.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this,
                    "Clear this Client's stored position and manual distance?",
                    "Clear Server-Client Distance", JOptionPane.YES_NO_OPTION)
                    == JOptionPane.YES_OPTION) clearAction.run();
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(serverButton);
        actions.add(clearServerButton);
        actions.add(clientButton);
        actions.add(manualButton);
        actions.add(clearButton);
        JLabel note = new JLabel("Manual/admin coordinates take priority. Wi-Fi fingerprint values are position-based estimates; RTT is never distance.");
        note.setForeground(DashboardColors.MUTED);
        JPanel south = new JPanel(new BorderLayout(0, 10));
        south.setOpaque(false);
        south.add(note, BorderLayout.NORTH);
        south.add(actions, BorderLayout.SOUTH);
        add(values, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);
    }

    public void setDistance(ServerClientDistance newValue) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setDistance(newValue));
            return;
        }
        value = newValue;
        SpatialPosition server = newValue == null ? null : newValue.serverPosition();
        SpatialPosition client = newValue == null ? null : newValue.clientPosition();
        serverPosition.setText("Server Position: " + positionText(server));
        clientPosition.setText("Client Position: " + positionText(client));
        distance.setText("Distance: " + (newValue == null ? "NOT_AVAILABLE"
                : newValue.distanceMeters() == null ? newValue.status().name()
                : String.format(Locale.ROOT, "%.2f m", newValue.distanceMeters())));
        method.setText("Method: " + (newValue == null ? "NOT_AVAILABLE" : newValue.measurementMethod()));
        source.setText("Data Source: " + (newValue == null ? "NONE" : newValue.dataSource()));
        status.setText("Data Status: " + (newValue == null ? "NOT_AVAILABLE" : newValue.status()));
        error.setText("Confidence / Estimated Error: " + (newValue == null
                || newValue.estimatedErrorMeters() == null ? "NOT_AVAILABLE"
                : String.format(Locale.ROOT, "± %.2f m", newValue.estimatedErrorMeters())));
        updated.setText("Last Updated: " + (newValue == null || newValue.lastUpdated() <= 0
                ? "NOT_AVAILABLE" : TIME.format(Instant.ofEpochMilli(newValue.lastUpdated()))));
        reference.setText("Reference Point: " + (newValue == null
                || newValue.referencePoint().isBlank() ? "NOT_AVAILABLE" : newValue.referencePoint()));
    }

    private void editPosition(boolean server) {
        SpatialPosition existing = value == null ? null
                : server ? value.serverPosition() : value.clientPosition();
        JTextField referenceField = new JTextField(existing == null ? "" : existing.referencePoint());
        JTextField xField = new JTextField(existing == null ? "" : Double.toString(existing.xMeters()));
        JTextField yField = new JTextField(existing == null ? "" : Double.toString(existing.yMeters()));
        JTextField zField = new JTextField(existing == null || existing.zMeters() == null
                ? "" : Double.toString(existing.zMeters()));
        Object[] fields = {"Reference point / room map:", referenceField,
            "X (metres):", xField, "Y (metres):", yField, "Z (optional, metres):", zField};
        if (JOptionPane.showConfirmDialog(this, fields,
                server ? "Set Server Position" : "Edit Client Position",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            Double z = zField.getText().isBlank() ? null : Double.valueOf(zField.getText().trim());
            SpatialPosition position = new SpatialPosition(referenceField.getText(),
                    Double.parseDouble(xField.getText().trim()),
                    Double.parseDouble(yField.getText().trim()), z, System.currentTimeMillis());
            (server ? serverAction : clientAction).accept(position);
        } catch (RuntimeException exception) { showInvalid(exception.getMessage()); }
    }

    private void enterManualDistance() {
        JTextField distanceField = new JTextField();
        JTextField errorField = new JTextField();
        Object[] fields = {"Measured Server-to-Client distance (metres):", distanceField,
            "Estimated error (optional, metres):", errorField};
        if (JOptionPane.showConfirmDialog(this, fields, "Enter Measured Distance",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            double meters = Double.parseDouble(distanceField.getText().trim());
            Double estimatedError = errorField.getText().isBlank() ? null
                    : Double.valueOf(errorField.getText().trim());
            manualAction.accept(new ManualDistanceInput(meters, estimatedError,
                    System.currentTimeMillis()));
        } catch (RuntimeException exception) { showInvalid(exception.getMessage()); }
    }

    private void showInvalid(String detail) {
        JOptionPane.showMessageDialog(this, "Invalid value: " + detail,
                "Server-Client Distance", JOptionPane.ERROR_MESSAGE);
    }

    private static String positionText(SpatialPosition position) {
        if (position == null) return "NOT_AVAILABLE";
        return String.format(Locale.ROOT, "(%.2f, %.2f%s) m [%s]", position.xMeters(),
                position.yMeters(), position.zMeters() == null ? "" : ", " + position.zMeters(),
                position.referencePoint());
    }

    public void setServerPositionAction(Consumer<SpatialPosition> action) {
        serverAction = action == null ? ignored -> {} : action;
    }
    public void setClientPositionAction(Consumer<SpatialPosition> action) {
        clientAction = action == null ? ignored -> {} : action;
    }
    public void setManualDistanceAction(Consumer<ManualDistanceInput> action) {
        manualAction = action == null ? ignored -> {} : action;
    }
    public void setClearAction(Runnable action) { clearAction = action == null ? () -> {} : action; }
    public void setClearServerAction(Runnable action) {
        clearServerAction = action == null ? () -> {} : action;
    }
    String distanceText() { return distance.getText(); }
    String methodText() { return method.getText(); }
    String statusText() { return status.getText(); }

    public record ManualDistanceInput(double meters, Double estimatedErrorMeters, long measuredAt) {
        public ManualDistanceInput {
            if (!Double.isFinite(meters) || meters < 0 || (estimatedErrorMeters != null
                    && (!Double.isFinite(estimatedErrorMeters) || estimatedErrorMeters < 0))) {
                throw new IllegalArgumentException("Distance and error must be finite and non-negative");
            }
        }
    }
}
