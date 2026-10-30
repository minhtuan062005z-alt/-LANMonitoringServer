package lanmonitoringserver.ui;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import lanmonitoringserver.model.FileTransferProgress;

/** Client-side live receive status and received-path/checksum result. */
public final class ClientFileTransferPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int HISTORY_LIMIT = 500;
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"File", "Progress", "Bytes", "Status", "Checksum / Detail", "Saved path"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final Map<String, FileTransferProgress> values = new LinkedHashMap<>();

    public ClientFileTransferPanel() {
        super(new BorderLayout());
        add(new JLabel("Files are saved only in the configured application receive directory."),
                BorderLayout.NORTH);
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(1).setCellRenderer((target, value, selected, focus, row, column) -> {
            JProgressBar bar = new JProgressBar(0, 100);
            int percent = value instanceof Number number ? number.intValue() : 0;
            bar.setValue(percent); bar.setStringPainted(true);
            return bar;
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void updateTransfer(FileTransferProgress progress) {
        Runnable update = () -> {
            values.put(progress.transferId(), progress);
            while (values.size() > HISTORY_LIMIT) {
                values.remove(values.keySet().iterator().next());
            }
            model.setRowCount(0);
            for (FileTransferProgress item : values.values()) model.addRow(new Object[]{
                item.fileName(), item.percent(), item.bytesTransferred() + "/" + item.fileSize(),
                item.status(), item.detail(), item.savedPath() == null ? "" : item.savedPath()});
        };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    int transferCount() { return model.getRowCount(); }
}
