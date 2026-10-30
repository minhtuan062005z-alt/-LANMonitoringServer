package lanmonitoringserver.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.net.ServerSocketFactory;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.UserRole;
import lanmonitoringserver.security.AuthorizationContext;
import lanmonitoringserver.security.InMemoryCredentialRegistry;

/** Release checks for bounded state, service cleanup, RBAC and audit. */
public final class Phase9StabilityTest {
    public static void main(String[] args) throws Exception {
        testPendingPingRetention();
        testAlertManagementRbacAndIdempotentClose();
        System.out.println("Phase9StabilityTest: PASS");
    }

    private static void testPendingPingRetention() {
        long now = System.currentTimeMillis();
        Map<String, Long> pending = new HashMap<>();
        for (int index = 0; index < 20; index++) {
            pending.put("old-" + index, now - 60_000 - index);
        }
        pending.put("fresh", now);
        check(LanServerService.prunePendingPings(pending, now), "expired pings are pruned");
        check(pending.size() < 8 && pending.containsKey("fresh"),
                "pending PING state remains bounded without discarding the fresh request");
    }

    private static void testAlertManagementRbacAndIdempotentClose() throws Exception {
        Path root = Files.createTempDirectory("lan-phase9-");
        Path audit = root.resolve("audit.jsonl");
        try {
            LanServerService viewer = service(root.resolve("viewer.json"), audit,
                    new AuthorizationContext("viewer", UserRole.VIEWER, Set.of()));
            expectSecurity(() -> viewer.updateAlertRule(AlertRule.defaults(AlertMetric.CPU)),
                    "VIEWER cannot configure alert rules");
            expectSecurity(() -> viewer.acknowledgeAlert("missing"),
                    "VIEWER cannot acknowledge alerts");
            expectSecurity(() -> viewer.resolveAlert("missing"),
                    "VIEWER cannot resolve alerts");
            viewer.close();
            viewer.close();
            try {
                viewer.start(0);
                throw new AssertionError("closed Server service must not restart");
            } catch (IllegalStateException expected) { }

            LanServerService admin = service(root.resolve("admin.json"), audit,
                    new AuthorizationContext("admin", UserRole.ADMIN, Set.of()));
            admin.updateAlertRule(AlertRule.defaults(AlertMetric.CPU));
            admin.close();
            admin.close();

            String entries = Files.readString(audit, StandardCharsets.UTF_8);
            check(entries.contains("ALERT_RULE_UPDATE") && entries.contains("ALERT_ACKNOWLEDGE")
                    && entries.contains("ALERT_RESOLVE") && entries.contains("DENIED"),
                    "alert administration decisions are audited");
        } finally {
            deleteTree(root);
        }
    }

    private static LanServerService service(Path permissions, Path audit,
            AuthorizationContext authorization) throws Exception {
        return new LanServerService(new InMemoryCredentialRegistry(), ServerSocketFactory.getDefault(),
                new InMemoryAlertStore(), new ClientPermissionStore(permissions),
                new AuditLogger(audit), authorization);
    }
    private static void expectSecurity(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch (SecurityException expected) { }
    }
    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
