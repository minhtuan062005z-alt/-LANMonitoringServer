package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import lanmonitoringserver.model.SystemInformation;

/** Structured, grouped renderer for static DXDIAG-style inventory. */
public final class SystemInfoPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private final JPanel content = new JPanel();
    private SystemInformation information;

    public SystemInfoPanel() {
        super(new BorderLayout());
        setBackground(DashboardColors.BACKGROUND);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(DashboardColors.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);
        showUnavailable("Chưa nhận SYSTEM_INFO từ Client");
    }

    public void updateInformation(SystemInformation updated) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateInformation(updated));
            return;
        }
        if (updated == null) {
            information = null;
            showUnavailable("Chưa nhận SYSTEM_INFO từ Client");
            return;
        }
        if (updated.equals(information)) return;
        information = updated;
        content.removeAll();
        Map<String, String> reasons = updated.unavailableReasons();
        addSection("System", new String[][]{
            {"Hostname", value(updated.system().hostname(), "system.hostname", reasons)},
            {"Operating System", join(updated.system().osName(), updated.system().osVersion())},
            {"Architecture", value(updated.system().architecture(), "system.architecture", reasons)},
            {"Manufacturer", value(updated.system().manufacturer(), "system.manufacturer", reasons)},
            {"Model", value(updated.system().model(), "system.model", reasons)},
            {"BIOS / UEFI", value(join(updated.system().firmwareName(),
                    updated.system().firmwareVersion()), "system.firmware", reasons)},
            {"Java", value(updated.system().javaVersion(), "system.java", reasons)},
            {"Collector", updated.collector()},
            {"Collected At", TIME.format(Instant.ofEpochMilli(updated.collectedAt()))}
        });
        addSection("CPU", new String[][]{
            {"Model", value(updated.cpu().model(), "cpu.model", reasons)},
            {"Vendor", value(updated.cpu().vendor(), "cpu.vendor", reasons)},
            {"Physical Cores", value(updated.cpu().physicalCores(), "cpu.physicalCores", reasons)},
            {"Logical Processors", value(updated.cpu().logicalProcessors(), "cpu.logicalProcessors", reasons)},
            {"Maximum Frequency", updated.cpu().maxFrequencyMhz() == null
                    ? value(null, "cpu.frequency", reasons) : updated.cpu().maxFrequencyMhz() + " MHz"}
        });
        addSection("Memory", new String[][]{
            {"Total RAM", bytes(updated.memory().totalBytes(), "memory", reasons)},
            {"Available RAM", bytes(updated.memory().availableBytes(), "memory", reasons)}
        });
        if (updated.gpus().isEmpty()) {
            addSection("GPU", new String[][]{{"GPU", value(null, "gpu", reasons)}});
        } else {
            int index = 1;
            for (SystemInformation.GpuDetails gpu : updated.gpus()) {
                addSection("GPU " + index++, new String[][]{
                    {"Name", value(gpu.name(), "gpu.name", reasons)},
                    {"Vendor", value(gpu.vendor(), "gpu.vendor", reasons)},
                    {"Driver", value(gpu.driver(), "gpu.driver", reasons)},
                    {"VRAM", bytes(gpu.vramBytes(), "gpu.vram", reasons)},
                    {"Category", value(gpu.category(), "gpu.category", reasons)}
                });
            }
        }
        if (updated.storage().isEmpty()) {
            addSection("Storage", new String[][]{{"Storage", value(null, "storage", reasons)}});
        } else {
            int index = 1;
            for (SystemInformation.StorageDetails storage : updated.storage()) {
                addSection("Storage " + index++, new String[][]{
                    {"Device", value(storage.device(), "storage", reasons)},
                    {"Model", value(storage.model(), "storage.model", reasons)},
                    {"Mount Point", value(storage.mountPoint(), "storage.mount", reasons)},
                    {"Filesystem", value(storage.fileSystem(), "storage.filesystem", reasons)},
                    {"Total", bytes(storage.totalBytes(), "storage.total", reasons)},
                    {"Used", bytes(storage.usedBytes(), "storage.used", reasons)},
                    {"Free", bytes(storage.freeBytes(), "storage.free", reasons)},
                    {"Media Type", value(storage.mediaType(), "storage.mediaType", reasons)}
                });
            }
        }
        if (updated.network().isEmpty()) {
            addSection("Network", new String[][]{{"Adapter", value(null, "network", reasons)}});
        } else {
            int index = 1;
            for (SystemInformation.NetworkDetails network : updated.network()) {
                addSection("Network " + index++, new String[][]{
                    {"Name", value(network.name(), "network", reasons)},
                    {"Adapter", value(network.displayName(), "network", reasons)},
                    {"Connection Type", value(network.connectionType(), "network.type", reasons)},
                    {"MAC Address", value(network.macAddress(), "network.mac", reasons)},
                    {"IPv4", value(String.join(", ", network.ipv4Addresses()), "network.ipv4", reasons)},
                    {"IPv6", value(String.join(", ", network.ipv6Addresses()), "network.ipv6", reasons)}
                });
            }
        }
        content.add(Box.createVerticalGlue());
        content.revalidate();
        content.repaint();
    }

    private void addSection(String title, String[][] rows) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBackground(DashboardColors.SURFACE);
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DashboardColors.BORDER),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        section.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.insets = new Insets(0, 0, 8, 0);
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        heading.setForeground(DashboardColors.PRIMARY);
        section.add(heading, constraints);
        constraints.gridwidth = 1;
        for (String[] row : rows) {
            constraints.gridy++;
            constraints.gridx = 0;
            constraints.weightx = 0;
            constraints.insets = new Insets(4, 0, 4, 18);
            JLabel name = new JLabel(row[0]);
            name.setForeground(DashboardColors.MUTED);
            section.add(name, constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.insets = new Insets(4, 0, 4, 0);
            JLabel field = new JLabel(row[1]);
            field.setForeground(DashboardColors.TEXT);
            section.add(field, constraints);
        }
        content.add(section);
        content.add(Box.createVerticalStrut(10));
    }

    private void showUnavailable(String reason) {
        content.removeAll();
        JLabel label = new JLabel("N/A - " + reason, JLabel.CENTER);
        label.setForeground(DashboardColors.MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setAlignmentX(CENTER_ALIGNMENT);
        content.add(Box.createVerticalStrut(30));
        content.add(label);
        content.revalidate();
        content.repaint();
    }

    private String value(Object value, String reasonKey, Map<String, String> reasons) {
        if (value != null && !value.toString().isBlank()) return value.toString();
        String reason = reasons.get(reasonKey);
        return reason == null ? "N/A - Not Supported" : "N/A - " + reason;
    }

    private String bytes(Long value, String reasonKey, Map<String, String> reasons) {
        return value == null ? value(null, reasonKey, reasons)
                : String.format("%.2f GiB (%d bytes)", value / 1_073_741_824.0, value);
    }

    private String join(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + " " + second;
    }

    public SystemInformation information() { return information; }
}
