package lanmonitoringserver.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lanmonitoringserver.protocol.JsonCodec;

/** Separate test process that emits the stable markers/recap expected from bundled playbooks. */
public final class FakeAnsiblePlaybook {
    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("fake.ansible.mode", "normal");
        if ("sleep".equals(mode)) {
            Thread.sleep(10_000);
            return;
        }
        List<String> arguments = List.of(args);
        String limit = arguments.get(arguments.indexOf("--limit") + 1);
        String variablesArgument = arguments.get(arguments.indexOf("--extra-vars") + 1);
        Path variables = Path.of(variablesArgument.substring(1));
        Object parsed = JsonCodec.parse(Files.readString(variables, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        String sha256 = String.valueOf(((Map<String, Object>) parsed).get("lan_sync_sha256"));
        Path statePath = Path.of(System.getProperty("fake.ansible.state"));
        Properties state = new Properties();
        if (Files.exists(statePath)) try (var input = Files.newInputStream(statePath)) { state.load(input); }
        int exit = 0;
        for (String host : limit.split(",")) {
            if (host.contains("offline")) {
                System.out.println(host + " : ok=0 changed=0 unreachable=1 failed=0 skipped=0 rescued=0 ignored=0");
                exit = 2;
            } else if (host.contains("denied")) {
                System.err.println("Permission denied for " + host);
                System.out.println(host + " : ok=0 changed=0 unreachable=0 failed=1 skipped=0 rescued=0 ignored=0");
                exit = 2;
            } else {
                boolean changed = !sha256.equals(state.getProperty(host));
                state.setProperty(host, sha256);
                System.out.println("ok: [" + host + "] => {\"msg\": "
                        + "\"LANMONITORING_RESULT|host=" + host + "|status=SUCCESS|changed="
                        + changed + "|sha256=" + sha256 + "\"}");
                System.out.println(host + " : ok=4 changed=" + (changed ? 1 : 0)
                        + " unreachable=0 failed=0 skipped=0 rescued=0 ignored=0");
            }
        }
        try (var output = Files.newOutputStream(statePath)) { state.store(output, "test state"); }
        System.exit(exit);
    }
}
