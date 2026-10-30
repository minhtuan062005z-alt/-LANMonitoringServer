/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package lanmonitoringserver;

import java.util.Arrays;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import lanmonitoringserver.util.BuildInfo;

/**
 *
 * @author Lenovo
 */
public class LANMonitoringServer {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        boolean clientMode = Arrays.stream(args).anyMatch("--client"::equalsIgnoreCase);
        boolean serverMode = Arrays.stream(args).anyMatch("--server"::equalsIgnoreCase);
        if (clientMode && serverMode) {
            System.err.println("Choose only one mode: --server or --client");
            System.exit(2);
        }
        System.out.println("LANMonitoringServer " + BuildInfo.runtimeDescription(LANMonitoringServer.class)
                + ", mode=" + (clientMode ? "client" : "server"));
        installLookAndFeel();
        SwingUtilities.invokeLater(() -> {
            if (clientMode) new frmClient().setVisible(true);
            else new frmServer().setVisible(true);
        });
    }

    private static void installLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
        } catch (Exception exception) {
            System.err.println("Không thể bật Nimbus Look & Feel: " + exception.getMessage());
        }
    }
}
