package lanmonitoringserver.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import lanmonitoringserver.model.AnsibleConnectionType;
import lanmonitoringserver.model.AnsibleManagedHost;
import lanmonitoringserver.model.AnsiblePlatform;

/** Writes a per-job YAML inventory containing no credential values. */
public final class AnsibleInventoryWriter {
    private AnsibleInventoryWriter() {}

    public static void write(Path path, List<AnsibleManagedHost> hosts) throws IOException {
        StringBuilder yaml = new StringBuilder("all:\n  children:\n");
        appendGroup(yaml, "lan_linux", hosts, AnsiblePlatform.LINUX);
        appendGroup(yaml, "lan_windows", hosts, AnsiblePlatform.WINDOWS);
        Files.writeString(path, yaml.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void appendGroup(StringBuilder yaml, String group,
            List<AnsibleManagedHost> hosts, AnsiblePlatform platform) {
        yaml.append("    ").append(group).append(":\n      hosts:\n");
        for (AnsibleManagedHost host : hosts) {
            if (host.platform() != platform) continue;
            String name = host.inventoryName();
            yaml.append("        ").append(name).append(":\n")
                    .append("          ansible_host: ").append(quote(host.address())).append('\n')
                    .append("          ansible_port: ").append(host.port()).append('\n')
                    .append("          ansible_connection: ")
                    .append(quote(host.connection().ansibleValue())).append('\n')
                    .append("          lan_sync_dest: ").append(quote(host.destinationPath())).append('\n')
                    .append("          ansible_user: \"{{ vault_hosts['").append(name)
                    .append("']['ansible_user'] }}\"\n");
            if (host.connection() == AnsibleConnectionType.SSH) {
                yaml.append("          ansible_ssh_private_key_file: \"{{ vault_hosts['")
                        .append(name).append("']['ansible_ssh_private_key_file'] }}\"\n");
                if (platform == AnsiblePlatform.WINDOWS) {
                    yaml.append("          ansible_shell_type: powershell\n");
                }
            } else if (host.connection() == AnsibleConnectionType.PSRP) {
                yaml.append("          ansible_password: \"{{ vault_hosts['").append(name)
                        .append("']['ansible_password'] }}\"\n")
                        .append("          ansible_psrp_protocol: https\n")
                        .append("          ansible_psrp_cert_validation: validate\n")
                        .append("          ansible_psrp_auth: \"{{ vault_hosts['").append(name)
                        .append("']['ansible_psrp_auth'] | default('negotiate') }}\"\n");
            } else {
                yaml.append("          ansible_password: \"{{ vault_hosts['").append(name)
                        .append("']['ansible_password'] }}\"\n")
                        .append("          ansible_winrm_scheme: https\n")
                        .append("          ansible_winrm_server_cert_validation: validate\n")
                        .append("          ansible_winrm_transport: \"{{ vault_hosts['").append(name)
                        .append("']['ansible_winrm_transport'] | default('ntlm') }}\"\n");
            }
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }
}
