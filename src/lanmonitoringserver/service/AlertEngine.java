package lanmonitoringserver.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lanmonitoringserver.model.AlertDeliveryStatus;
import lanmonitoringserver.model.AlertMetric;
import lanmonitoringserver.model.AlertRecord;
import lanmonitoringserver.model.AlertRule;
import lanmonitoringserver.model.AlertSeverity;
import lanmonitoringserver.model.AlertStatus;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.NotificationTemplate;
import lanmonitoringserver.model.MetricReading;
import lanmonitoringserver.model.TelemetrySample;

/** Stateful threshold engine. It performs no network, UI or filesystem I/O. */
public final class AlertEngine {
    static final double AUTOMATIC_RAM_THRESHOLD = 90d;
    static final long AUTOMATIC_RAM_PERIOD_MILLIS = 60_000L;
    private final Map<AlertMetric, AlertRule> rules = new EnumMap<>(AlertMetric.class);
    private final Map<String, AlertRecord> alerts = new LinkedHashMap<>();
    private final Map<Key, RuntimeState> states = new HashMap<>();

    public AlertEngine() { this(List.of(), List.of()); }

    public AlertEngine(List<AlertRule> savedRules, List<AlertRecord> savedAlerts) {
        for (AlertMetric metric : AlertMetric.values()) rules.put(metric, AlertRule.defaults(metric));
        if (savedRules != null) for (AlertRule rule : savedRules) rules.put(rule.metric(), rule);
        if (savedAlerts != null) {
            for (AlertRecord alert : savedAlerts) {
                alerts.put(alert.id(), alert);
                if (alert.manualNotification()) continue;
                RuntimeState state = states.computeIfAbsent(
                        new Key(alert.clientId(), alert.metric()), ignored -> new RuntimeState());
                if (alert.status().isOpen()) {
                    state.activeAlertId = alert.id();
                    if (alert.metric() == AlertMetric.RAM) state.lastNotificationAt = alert.updatedAt();
                } else if (alert.resolvedAt() != null) {
                    state.cooldownUntil = Math.max(state.cooldownUntil,
                            alert.resolvedAt() + rules.get(alert.metric()).cooldownMillis());
                }
            }
        }
    }

    public synchronized Result evaluate(String clientId, String hostname,
            TelemetrySample sample, long now) {
        if (sample.isStale(now)) {
            // A telemetry gap breaks a pending "continuous duration", but must never
            // fabricate recovery for an alert that is already open.
            states.forEach((key, state) -> {
                if (key.clientId().equals(clientId)) {
                    state.breachSince = 0;
                    state.recoverySince = 0;
                }
            });
            return Result.EMPTY;
        }
        List<AlertRecord> changed = new ArrayList<>();
        List<AlertRecord> notifications = new ArrayList<>();
        evaluateMetric(clientId, hostname, AlertMetric.CPU, sample.cpuStatus(),
                sample.cpuUsagePercent(), now, changed, notifications);
        evaluateRam(clientId, hostname, sample.memoryStatus(), sample.memoryUsagePercent(),
                now, changed, notifications);
        evaluateMetric(clientId, hostname, AlertMetric.GPU, sample.gpuStatus(),
                sample.gpuUsagePercent(), now, changed, notifications);
        evaluateMetric(clientId, hostname, AlertMetric.DISK_ACTIVE, sample.diskStatus(),
                sample.diskActivePercent(), now, changed, notifications);
        MeasurementStatus capacityStatus = sample.diskCapacityUsagePercent() == null
                ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK;
        evaluateMetric(clientId, hostname, AlertMetric.DISK_CAPACITY, capacityStatus,
                sample.diskCapacityUsagePercent(), now, changed, notifications);
        return changed.isEmpty() ? Result.EMPTY
                : new Result(List.copyOf(changed), List.copyOf(notifications));
    }

    private void evaluateRam(String clientId, String hostname, MeasurementStatus status,
            double value, long now, List<AlertRecord> changed, List<AlertRecord> notifications) {
        Key key = new Key(clientId, AlertMetric.RAM);
        RuntimeState state = states.computeIfAbsent(key, ignored -> new RuntimeState());
        if (status != MeasurementStatus.OK || !Double.isFinite(value)) {
            state.breachSince = 0;
            state.recoverySince = 0;
            return;
        }
        AlertRecord active = state.activeAlertId == null ? null : alerts.get(state.activeAlertId);
        if (active != null && active.status().isOpen()) {
            if (value <= AUTOMATIC_RAM_THRESHOLD) {
                state.breachSince = 0;
                if (state.recoverySince == 0) state.recoverySince = now;
                if (now - state.recoverySince >= AUTOMATIC_RAM_PERIOD_MILLIS) {
                    AlertRecord resolved = active.resolve(now);
                    alerts.put(resolved.id(), resolved);
                    state.activeAlertId = null;
                    state.recoverySince = 0;
                    state.lastNotificationAt = 0;
                    changed.add(resolved);
                }
                return;
            }
            state.recoverySince = 0;
            if (now - state.lastNotificationAt >= AUTOMATIC_RAM_PERIOD_MILLIS) {
                AlertRecord reminder = active.updateMeasurement(AlertSeverity.CRITICAL, value,
                        AUTOMATIC_RAM_THRESHOLD, now)
                        .withDelivery(AlertDeliveryStatus.PENDING, null, now);
                alerts.put(reminder.id(), reminder);
                state.lastNotificationAt = now;
                changed.add(reminder);
                notifications.add(reminder);
            }
            return;
        }

        state.recoverySince = 0;
        if (value <= AUTOMATIC_RAM_THRESHOLD) {
            state.breachSince = 0;
            return;
        }
        if (state.breachSince == 0) state.breachSince = now;
        if (now - state.breachSince < AUTOMATIC_RAM_PERIOD_MILLIS) return;
        AlertRecord created = new AlertRecord(UUID.randomUUID().toString(), clientId,
                hostname, AlertMetric.RAM, AlertSeverity.CRITICAL, value,
                AUTOMATIC_RAM_THRESHOLD, AlertStatus.ACTIVE, AlertDeliveryStatus.PENDING,
                now, null, null, now, null);
        alerts.put(created.id(), created);
        state.activeAlertId = created.id();
        state.breachSince = 0;
        state.lastNotificationAt = now;
        changed.add(created);
        notifications.add(created);
    }

    public synchronized AlertRecord createManualNotification(String clientId, String hostname,
            NotificationTemplate template, MetricReading reading, long now) {
        if (template == null) throw new IllegalArgumentException("notification template is required");
        double value = reading != null && reading.isCurrentValue() ? reading.value() : 0d;
        AlertRecord created = new AlertRecord(UUID.randomUUID().toString(), clientId, hostname,
                template.metric(), template.severity(), value, 0d, AlertStatus.ACTIVE,
                AlertDeliveryStatus.PENDING, now, null, null, now, null,
                template.name(), template.title(), template.content(hostname, reading));
        alerts.put(created.id(), created);
        return created;
    }

    private void evaluateMetric(String clientId, String hostname, AlertMetric metric,
            MeasurementStatus measurementStatus, Double value, long now,
            List<AlertRecord> changed, List<AlertRecord> notifications) {
        AlertRule rule = rules.get(metric);
        Key key = new Key(clientId, metric);
        RuntimeState state = states.computeIfAbsent(key, ignored -> new RuntimeState());
        if (!rule.enabled() || measurementStatus != MeasurementStatus.OK || value == null
                || !Double.isFinite(value)) {
            state.breachSince = 0;
            return;
        }

        AlertRecord active = state.activeAlertId == null ? null : alerts.get(state.activeAlertId);
        if (active != null && active.status().isOpen()) {
            if (value <= rule.warningThreshold() - rule.hysteresisPercent()) {
                AlertRecord resolved = active.resolve(now);
                alerts.put(resolved.id(), resolved);
                state.activeAlertId = null;
                state.cooldownUntil = now + rule.cooldownMillis();
                state.breachSince = 0;
                changed.add(resolved);
                return;
            }
            AlertSeverity severity = severity(rule, value);
            double threshold = severity == AlertSeverity.CRITICAL
                    ? rule.criticalThreshold() : rule.warningThreshold();
            if (severity != active.severity()) {
                AlertRecord updated = active.updateMeasurement(severity, value, threshold, now);
                if (severity.ordinal() > active.severity().ordinal()) {
                    updated = updated.withDelivery(AlertDeliveryStatus.PENDING, null, now);
                    notifications.add(updated);
                }
                alerts.put(updated.id(), updated);
                changed.add(updated);
            }
            return;
        }

        if (value <= rule.warningThreshold()) {
            state.breachSince = 0;
            return;
        }
        if (now < state.cooldownUntil) return;
        if (state.breachSince == 0) state.breachSince = now;
        if (now - state.breachSince < rule.durationMillis()) return;

        AlertSeverity severity = severity(rule, value);
        double threshold = severity == AlertSeverity.CRITICAL
                ? rule.criticalThreshold() : rule.warningThreshold();
        AlertRecord created = new AlertRecord(UUID.randomUUID().toString(), clientId,
                hostname, metric, severity, value, threshold, AlertStatus.ACTIVE,
                AlertDeliveryStatus.PENDING, now, null, null, now, null);
        alerts.put(created.id(), created);
        state.activeAlertId = created.id();
        state.breachSince = 0;
        changed.add(created);
        notifications.add(created);
    }

    private static AlertSeverity severity(AlertRule rule, double value) {
        return value > rule.criticalThreshold() ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
    }

    public synchronized AlertRecord acknowledge(String alertId, long now) {
        AlertRecord current = alerts.get(alertId);
        if (current == null) return null;
        AlertRecord updated = current.acknowledge(now);
        alerts.put(alertId, updated);
        return updated;
    }

    public synchronized AlertRecord resolve(String alertId, long now) {
        AlertRecord current = alerts.get(alertId);
        if (current == null) return null;
        AlertRecord updated = current.resolve(now);
        alerts.put(alertId, updated);
        if (current.manualNotification()) return updated;
        RuntimeState state = states.computeIfAbsent(new Key(current.clientId(), current.metric()),
                ignored -> new RuntimeState());
        state.activeAlertId = null;
        state.breachSince = 0;
        state.cooldownUntil = now + rules.get(current.metric()).cooldownMillis();
        return updated;
    }

    public synchronized AlertRecord updateDelivery(String alertId, String clientId,
            AlertDeliveryStatus delivery, String requestId, long now) {
        AlertRecord current = alerts.get(alertId);
        if (current == null || !current.clientId().equals(clientId)) return null;
        AlertRecord updated = current.withDelivery(delivery,
                requestId == null ? current.deliveryRequestId() : requestId, now);
        alerts.put(alertId, updated);
        return updated;
    }

    public synchronized void updateRule(AlertRule rule) { rules.put(rule.metric(), rule); }
    public synchronized List<AlertRule> rules() { return List.copyOf(rules.values()); }
    public synchronized List<AlertRecord> alerts() { return List.copyOf(alerts.values()); }

    public synchronized Summary summary(String clientId) {
        int count = 0;
        AlertSeverity highest = null;
        for (AlertRecord alert : alerts.values()) {
            if (!alert.clientId().equals(clientId) || !alert.status().isOpen()) continue;
            count++;
            if (highest == null || alert.severity().ordinal() > highest.ordinal()) highest = alert.severity();
        }
        return new Summary(count, highest);
    }

    public record Result(List<AlertRecord> changed, List<AlertRecord> notifications) {
        static final Result EMPTY = new Result(List.of(), List.of());
    }
    public record Summary(int activeCount, AlertSeverity highestSeverity) {}
    private record Key(String clientId, AlertMetric metric) {}
    private static final class RuntimeState {
        private long breachSince;
        private long recoverySince;
        private long lastNotificationAt;
        private long cooldownUntil;
        private String activeAlertId;
    }
}
