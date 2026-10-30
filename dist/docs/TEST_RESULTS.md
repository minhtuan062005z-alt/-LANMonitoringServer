# Kết quả kiểm thử bản bàn giao Giai đoạn 9

Ngày thực thi: 2026-10-28, Windows, JDK 17.0.20.1, Apache Ant 1.10.14 đi kèm NetBeans 22.

## Kết quả đã thực thi

### Static compile review

```text
ant clean compile -Djavac.compilerargs=-Xlint:all
151 source files
BUILD SUCCESSFUL
0 warnings
```

### Clean release gate

```text
ant release-phase9
153 source files
25 test source files
BUILD SUCCESSFUL
```

| Runner | Result | Nội dung chính |
|---|---|---|
| `ProtocolTest` | PASS | framing, JSON, validation, invalid frames |
| `ServerClientDistanceTest` | PASS | status mapping, stale expiry, historical coordinate, manual/ADMIN priority, formula, persistence/isolation |
| `DistanceManagementPanelTest` | PASS | STALE không hiện realtime metres/map, Card/detail status, 3 positioned Clients, zoom/fit, EDT/headless paint |
| `NetworkIntegrationTest` | PASS | 3 local Clients, auth, telemetry, alert delivery/ACK, messages, reconnect, Server restart, Wi-Fi observation |
| `DashboardTest` | PASS | Dashboard/Card/filter/detail/alert/file/Ansible headless EDT |
| `SystemInfoCollectorTest` | PASS with fallback | Windows CIM Access Denied được phân loại; parser/fallback vẫn kiểm tra |
| `ClientMetricTrackerTest` | PASS | timestamp/source/stale/fallback consistency |
| `MetricsCollectorTest` | PASS | actual Windows CPU/RAM/Disk/GPU collection and disk semantics |
| `PerformancePanelTest` | PASS | bounded chart/history and pause behavior |
| `AlertEngineTest` | PASS | threshold, duration, escalation, recovery, cooldown, persistence/error paths |
| `AdminNotificationIntegrationTest` | PASS | template metric thật/không khả dụng, ADMIN RBAC, quyền Admin alerts, multi-client, duplicate suppression và ALERT_ACK |
| `AdminNotificationPanelTest` | PASS | 7 mẫu cố định, chỉ Client ONLINE, multi-selection ổn định khi refresh và bảng trạng thái ACK |
| `FileTransferIntegrationTest` | PASS | TLS TXT/PNG/binary, 1/3 Clients, checksum, permission, cancel, disconnect, telemetry continuity |
| `Phase6SecurityValidationTest` | PASS | cert trust/hostname, plaintext denial, RBAC, audit, symlink/path, timeout |
| `AnsibleIntegrationTest` | PASS controlled only | ProcessBuilder/allowlist/checksum/idempotency/error/cancel; no real managed host |
| `WirelessCollectorTest` | PASS | Windows RSSI -44 và -38 cùng Signal 100%, Signal-only, Linux fixture, invalid/disconnected/access denied, calibration path |
| `WirelessPhase8Test` | PASS | protocol, EMA, estimator, stale/unsupported, Client+AP calibration persistence/restart/isolation, headless panel |
| `WirelessTransportIntegrationTest` | PASS | TCP loopback, WIFI_SCAN, Radio Map/KNN distance, disconnect→STALE, stable-ID reconnect→fresh OK |
| `Phase9StabilityTest` | PASS | PING retention, alert RBAC/audit, idempotent close/final lifecycle |
| `Phase9UiStabilityTest` | PASS | bounded Swing logs, timer lifecycle, Viewer read-only controls |
| `MultiAccessPointScannerTest` | PASS | Linux parser/privacy hash và Windows Native Wi-Fi ABI layout |
| `WifiFingerprintTest` | PASS | weighted KNN, persistence, status mapping, stale/history, manual/ADMIN priority, privacy/TLS gate |
| `WifiFingerprintPanelTest` | PASS | status/history, one-row selection, keyboard, highlight, stable-ID refresh/sort/disconnect và Add callback |

Package smoke sau TLS deployment fix chạy `FileTransferIntegrationTest`, `WirelessTransportIntegrationTest`,
`WifiFingerprintTest` và `WifiFingerprintPanelTest` với application classes lấy từ JAR mới: PASS.
Toàn bộ 7 dependency JAR đọc được; ba PowerShell TLS script parse không lỗi. Entry point ngoài IDE với hai
mode xung đột trả exit code 2 và thông báo đúng.

## Artifact

- Release marker: `phase9-wifi-fingerprint-6`.
- `dist/LANMonitoringServer.jar`: 1,203,428 bytes.
- SHA-256: `52E75D963B8AE0214E20AF2EF0FC17F112657545FF502B9494AC6139AB4E39EC`.
- Manifest: `Main-Class: lanmonitoringserver.LANMonitoringServer`, Class-Path đúng.
- `dist/lib`: 7 JAR.
- `dist/ansible`: 5 allowlisted assets.
- `dist/scripts`: 7 script, gồm launcher compatibility Windows/Linux và setup/launcher TLS Windows.
- `dist/config`: 1 non-secret example.
- `dist/docs`: 21 reviewed documents, including the Wi-Fi Fingerprinting guide, Windows wireless diagnostic, Server-Client distance design and desktop checklist.

## Chưa thực thi

- Không có interactive window station: desktop GUI/HiDPI/manual popup **NOT RUN**.
- Không có 2 Windows + 1 Linux device/VM lab: multi-device checklist **NOT RUN**.
- Linux runtime collector/GUI **NOT RUN**.
- Native Ansible and real Windows/Linux managed hosts **NOT RUN**.
- Wi-Fi physical calibration/accuracy and real FTM ranging **NOT RUN**.
- Windows adapter collection in the Codex process **NOT RUN**: `netsh` returned `WlanQueryInterface error 5`; no elevation bypass was used.
- Long-duration soak/load benchmark và LAN throughput/p95 latency **NOT RUN**.

Các mục trên không được suy diễn là PASS từ fixture, loopback hoặc headless test.
# Wi-Fi Fingerprinting integration — 2026-10-27

- `ant release-phase9`: **PASS** (clean compile, all Phase 1–9 regression tests, JAR packaging).
- New automated tests: `MultiAccessPointScannerTest`, `WifiFingerprintTest`,
  `WifiFingerprintPanelTest`; authenticated `WIFI_SCAN` transport is also covered by
  `WirelessTransportIntegrationTest`.
- Windows Native Wi-Fi runtime probe on this machine: **PASS for collection** — status `OK`,
  6 AP observations with real `WLAN_BSS_ENTRY.lRssi` values across 2.4/5 GHz; application output
  exposed only truncated SHA-256 target IDs, not SSID/BSSID.
- Fingerprint accuracy: **NOT YET FIELD-VALIDATED** — no room-specific Radio Map or measured
  ground-truth positions were supplied during this run.
- Linux active scan: parser/permission behavior covered by automated fixtures; **not run on a
  real Linux Wi-Fi adapter** in this environment.
- Swing Radio Map Survey panel: headless EDT test PASS; interactive desktop workflow with three
  physical Clients remains a manual test gate.

