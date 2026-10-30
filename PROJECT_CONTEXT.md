# PROJECT CONTEXT — LANMonitoringServer

> Bổ sung 2026-10-27 — Wi-Fi Fingerprinting: đã thêm pipeline Java độc lập để quét nhiều AP,
> lưu Radio Map theo từng stable Client ID và ước lượng tọa độ bằng weighted KNN. Windows dùng
> Native Wi-Fi `WLAN_BSS_ENTRY.lRssi`; Linux dùng `iw`. Protocol chỉ truyền AP target SHA-256,
> RSSI dBm thật và frequency; mặc định yêu cầu TLS. Kết quả được gắn nhãn
> `POSITION_BASED_ESTIMATION` / `WIFI_FINGERPRINT`, có confidence/error/stale và không ghi đè
> manual distance hoặc ADMIN coordinates. `WIRELESS_SIGNAL_V1`, telemetry, alerts, file transfer
> và Ansible giữ pipeline riêng. Radio Map mẫu từ repository Python không được nhập vào ứng dụng.
> Hướng dẫn vận hành và giới hạn: `docs/WIFI_FINGERPRINTING_GUIDE.md`.

> Cập nhật lần cuối: 2026-10-28 (Asia/Saigon)  
> Trạng thái: Giai đoạn 9 Final Stabilization đã hoàn thành ở mức code review, automated regression Giai đoạn 1–9, clean Ant package, launcher và tài liệu bàn giao. GUI desktop, 2 Windows + 1 Linux lab, real-host Ansible và Wi-Fi accuracy vẫn là các gate thủ công chưa chạy.

## 1. Mục tiêu dự án

LANMonitoringServer là đồ án Java về lập trình mạng máy tính. Hệ thống gồm một ứng dụng Server và nhiều Client trong LAN, hướng tới Windows và Linux. Server quản lý trạng thái Client, inventory, telemetry/alert và truyền file có phân quyền tới nhiều Client.

Lộ trình đã định:

1. Tái cấu trúc Client–Server, giao thức và lifecycle kết nối.
2. Dashboard nhiều Client.
3. Thu thập System Information kiểu DXDIAG trên Windows/Linux.
4. Telemetry và biểu đồ CPU/GPU/RAM/Disk/Network realtime.
4.5. Ổn định baseline: merge metric có timestamp/source/status, chuẩn hóa Disk và test CIM — **đã triển khai**.
5. Cảnh báo CPU/GPU/Disk, giao nhận và lịch sử bền vững — **đã triển khai**.
6. Truyền file Server → nhiều Client và phân quyền — **đã triển khai**.
7. Tích hợp Ansible để đồng bộ file văn bản cho Windows/Linux — **đã triển khai source/integration; chưa xác minh managed host thật**.
8. Nghiên cứu ước lượng khoảng cách realtime qua Wi-Fi — **đã triển khai source/automated test; chưa xác minh accuracy trên thiết bị thật**.
9. Kiểm thử, tối ưu và hoàn thiện đồ án — **đã hoàn thành automated/release/documentation; còn checklist thiết bị thật**.

## 2. Trạng thái repository và nguồn bối cảnh

- Repository hiện chỉ có một commit: `541f4eb LANMonitoringServer`. Nhánh `main`, `master` và `origin/main` cùng trỏ tới commit này.
- Toàn bộ thay đổi Giai đoạn 1–4 hiện nằm chủ yếu trong working tree chưa commit. Không được reset/checkout hoặc xem commit gốc là trạng thái mới nhất.
- Working tree đã bẩn trước phiên rà soát: có sửa `build.xml`, `nbproject/project.properties`, ba lớp JFrame/main, nhiều `.class`; đồng thời các package mới, `lib/`, `test/`, `README.md` và `THIRD_PARTY_NOTICES.md` chưa được track.
- Không có `AGENTS.md` và không có lịch sử commit theo từng giai đoạn. Thư mục `docs/` hiện chứa checklist desktop cho Giai đoạn 5–6.
- `README.md` là tài liệu chính của Giai đoạn 1–4; `THIRD_PARTY_NOTICES.md` liệt kê dependency/license.
- Không có `.gitignore`; build artifact đang được Git theo dõi hoặc xuất hiện dưới dạng untracked, làm working tree nhiễu và tăng nguy cơ commit nhầm binary.

## 3. Công nghệ, dependency và build

### Công nghệ

- Java 17 (source/target 17), Swing/AWT.
- NetBeans Java Application + Apache Ant (`build.xml`, `nbproject/`).
- TCP socket blocking I/O, JSON UTF-8 tự cài đặt, frame có length-prefix.
- Java Preferences lưu identity/credential.
- PowerShell/CIM trên Windows; procfs/sysfs và công cụ hệ điều hành trên Linux.

### Dependency cục bộ trong `lib/`

| Dependency | Version | Vai trò |
|---|---:|---|
| OSHI (`oshi-core`, `oshi-common`) | 7.7.2 | CPU, RAM, disk, network đa nền tảng |
| JNA (`jna-jpms`, `jna-platform-jpms`) | 5.19.1 | Native access cho OSHI |
| JFreeChart | 1.5.6 | Biểu đồ Swing |
| SLF4J API + NOP | 2.0.20 | Logging facade/binding cho OSHI |

Dependency được tham chiếu bằng đường dẫn tương đối trong `nbproject/project.properties`. Repository không có lockfile/checksum manifest để tự tái kiểm chứng tuyên bố về nguồn tải/chữ ký của các JAR; chỉ có thông tin license trong `THIRD_PARTY_NOTICES.md`.

### Cách build và chạy dự kiến

Với Ant:

```powershell
ant test
ant release-phase8
java -jar dist/LANMonitoringServer.jar --server
java -jar dist/LANMonitoringServer.jar --client
```

- Main class: `lanmonitoringserver.LANMonitoringServer`.
- Không truyền tham số hoặc truyền `--server`: mở Server.
- `--client`: mở Client.
- Truyền đồng thời `--server` và `--client`: thoát với mã 2.
- Có thể cấu hình chu kỳ telemetry bằng `-Dlanmonitoring.telemetry.interval.ms=N`; giá trị bị chặn trong khoảng 500–10000 ms, mặc định 1000 ms.

Máy kiểm tra ngày 2026-10-26 có JDK/Javac 17.0.20.1. Ant không nằm trong `PATH`, nhưng Apache Ant 1.10.14 đi kèm NetBeans 22 tại `D:/NetBeans/NetBeans-22/netbeans/extide/ant/bin/ant.bat` đã được dùng để chạy `release-phase6`.

Artifact hiện hành dùng release marker `phase9-wifi-fingerprint-6`. Kích thước/SHA-256 của clean
release mới nhất được ghi tại mục 26 và `docs/TEST_RESULTS.md`; manifest có
`Main-Class: lanmonitoringserver.LANMonitoringServer`, Class-Path đúng, package có 7 dependency,
5 asset Ansible allowlist, 7 script launcher/setup, 21 tài liệu và 1 config example không chứa secret.

## 4. Kiến trúc hiện tại

```text
LANMonitoringServer/
├── src/lanmonitoringserver/
│   ├── LANMonitoringServer.java       # entry point, chọn Server/Client
│   ├── frmServer.java/.form            # Swing view/controller Server
│   ├── frmClient.java/.form            # Swing view/controller Client
│   ├── model/                          # snapshot, status, telemetry, system inventory
│   ├── network/                        # framed TCP transport
│   ├── protocol/                       # message types, JSON codec, mapper/validation
│   ├── security/                       # identity, HMAC, credential registry
│   ├── service/                        # lifecycle và nghiệp vụ Client/Server
│   ├── monitor/
│   │   ├── metrics/                    # realtime metrics: OSHI + nvidia-smi
│   │   └── system/                     # static inventory Windows/Linux/generic
│   ├── ui/                             # Dashboard, Card, Detail, Performance, System Info
│   └── util/                           # validation, command runner, formatter, threads
├── test/lanmonitoringserver/            # 9 test runner tự chứa, không dùng JUnit
├── lib/                                 # JAR dependency cục bộ
├── nbproject/                           # cấu hình NetBeans/Ant
├── build.xml
├── docs/PHASE5_GUI_TEST_CHECKLIST.md    # smoke test thủ công 1 Server + 3 Client
├── README.md
└── THIRD_PARTY_NOTICES.md
```

Luồng phụ thuộc chính:

```text
Swing forms → service → protocol/network/security
                    ↘ model ← monitors/collectors
Server events → Dashboard/Detail/Performance UI
```

`LanServerService` không phụ thuộc Swing. Socket accept/read, heartbeat, telemetry, system-info collection và reconnect đều chạy ngoài Swing Event Dispatch Thread. Các listener UI tự chuyển về EDT nơi cần thiết. Ghi frame trên mỗi connection được khóa để tránh xen byte.

## 5. Giao thức mạng v1

### Transport

- TCP mặc định, chưa bật TLS trong UI/runtime mặc định.
- Mỗi frame: số nguyên 4 byte big-endian biểu diễn độ dài, sau đó là JSON UTF-8.
- Kích thước frame tối đa: 1 MiB.
- Server nhận diện protocol cũ theo byte đầu tiên: frame v1 hợp lệ tối đa 1 MiB luôn có byte đầu bằng `0`; dữ liệu line-based cũ thường bắt đầu bằng ký tự khác `0`.
- Legacy dùng dòng `|` và giới hạn 64 KiB; identity bị cô lập thành `legacy:<id>@<remoteAddress>`.

### Envelope

Mỗi message có:

```json
{
  "version": 1,
  "type": "HEARTBEAT",
  "requestId": "uuid",
  "clientId": "uuid",
  "timestamp": 1793000000000,
  "payload": {}
}
```

Các type: `REGISTER`, `REGISTERED`, `CONNECT`, `CHALLENGE`, `AUTH`, `AUTH_OK`, `HEARTBEAT`, `TELEMETRY`, `SYSTEM_INFO`, `SYSTEM_INFO_REQUEST`, `ALERT`, `ALERT_ACK`, `MESSAGE`, `ACK`, `PING`, `PONG`, `ERROR`.

### Đăng ký/xác thực

1. Client gửi `CONNECT` với hostname, IP, OS và client nonce.
2. Nếu chưa đăng ký, Server trả `ERROR/NOT_REGISTERED`.
3. Client gửi `REGISTER` với secret Base64; Server lưu theo hash ổn định của client ID trong Java Preferences.
4. Client gửi lại `CONNECT`; Server trả `CHALLENGE` với server nonce.
5. Client gửi `AUTH` chứa HMAC-SHA256 của client ID + hai nonce.
6. Server trả `AUTH_OK` và capability `SYSTEM_INFO_V1`, `TELEMETRY_V1`.

Identity của Client (UUID + secret 32 byte) được tạo một lần và lưu trong Java Preferences. Đây là mô hình trust-on-first-use, không phải phân quyền người dùng/role.

### Message và liveness

- Client heartbeat mỗi 3 giây; Server coi Client offline sau 10 giây không có hoạt động hợp lệ.
- Server PING mỗi 3 giây; Client PONG theo cùng request ID để đo RTT.
- Client tự reconnect với backoff 1, 2, 4, 8, 16, tối đa 30 giây.
- Server gửi `MESSAGE`; Client hiển thị và trả `ACK/MESSAGE_RECEIVED`.

## 6. Server quản lý Client

- `LanServerService` giữ `ConcurrentHashMap<clientId, ClientRecord>` và danh sách session đang mở.
- Mỗi Client ID có một record; reconnect cập nhật record/card cũ. Session mới đóng session cũ cùng identity.
- Snapshot gồm ID, hostname, IP, OS, online/offline, RTT, last-seen, System Information và `ClientMetrics`. Mỗi metric có value, `MeasurementStatus`, timestamp và `MetricSource`.
- Client offline được giữ lại trong bộ nhớ để hiển thị lịch sử trong vòng đời tiến trình.
- Telemetry mỗi Client dùng `TelemetryRingBuffer` thread-safe, dung lượng 120 mẫu.
- Client snapshot, telemetry và system-info vẫn chỉ ở RAM. Alert rule/lịch sử được persist bằng JSON atomic store; credential/identity dùng Java Preferences.
- Network worker là blocking I/O với `ThreadPoolExecutor` tối đa 32 thread và `SynchronousQueue`; do mỗi session chiếm một worker lâu dài, giới hạn thực tế hiện tại là tối đa khoảng 32 kết nối đồng thời. Kết nối vượt mức bị từ chối.

## 7. Thu thập System Information

`SystemInfoCollectorFactory` chọn collector theo `os.name`:

- Windows: baseline Java + PowerShell `Get-CimInstance` cho ComputerSystem, OperatingSystem, BIOS, Processor, VideoController và LogicalDisk. Timeout 12 giây, output tối đa 2 MiB, UTF-8, không chủ động yêu cầu elevation.
- Linux: baseline Java + `/etc/os-release`, `/proc/cpuinfo`, DMI sysfs, cpufreq sysfs; dùng `lsblk` và `lspci` nếu có. Không cài package, không gọi `sudo`.
- OS khác: Java-only fallback.

Baseline Java thu hostname, OS/JVM, memory, filesystem và network adapter. Model `SystemInformation` có các nhóm System, CPU, GPU, Memory, Storage, Network và `unavailableReasons` cho dữ liệu không lấy được.

Client cache inventory; tự gửi một lần sau `AUTH_OK` nếu Server quảng bá capability. `SYSTEM_INFO_REQUEST` buộc thu thập lại. Server lưu snapshot mới nhất trong RAM. UI hiển thị theo nhóm và hỗ trợ export JSON/TXT UTF-8.

Môi trường kiểm tra Windows hiện bị hạn chế quyền: PowerShell/CIM trả `HRESULT 0x80041003 Access denied`. Collector hạ cấp an toàn thành dữ liệu Java + lý do unavailable. Test đã được tách semantics: parser Windows vẫn dùng fixture đầy đủ; integration runtime bắt buộc dữ liệu CIM khi CIM thành công, còn khi policy chặn thì bắt buộc baseline Java và diagnostic reason. Không yêu cầu Administrator và không tắt test.

Linux mới được kiểm tra parser bằng dữ liệu mẫu; chưa có kiểm chứng runtime Linux trong phiên này.

## 8. Telemetry realtime

### Thu thập và gửi

- `OshiMetricsCollector` lấy CPU, RAM, disk counter và network counter.
- Chu kỳ mặc định 1 giây trên scheduler riêng; heartbeat và telemetry không dùng chung executor.
- CPU: usage hệ thống, physical cores, logical processors, tần số trung bình nếu có.
- RAM: used/free/total và phần trăm usage.
- Disk: read/write byte/s theo delta counter, active time theo transfer-time delta, và capacity usage là trường riêng. Nếu không quan sát được cumulative transfer-time counter, Active Time trả `UNSUPPORTED`/null; capacity không được dùng thay thế.
- Network: upload/download byte/s; chọn interface đang UP có tổng lưu lượng của mẫu lớn nhất.
- GPU: gọi `nvidia-smi` với timeout 2 giây; trả utilization, VRAM và nhiệt độ. Không có NVIDIA/tool thì trả `UNSUPPORTED`, lỗi parse trả `ERROR`; không tạo dữ liệu giả.
- Payload `TELEMETRY` được mapper kiểm tra type/range; Server từ chối timestamp quá 60 giây trong tương lai.

### Lưu và phát sự kiện

- Server lưu 120 mẫu/Client, phát `onTelemetry(clientId, sample)` cho UI Performance và publish snapshot mới cho Dashboard.
- `ClientMetricTracker` giữ riêng TELEMETRY và HEARTBEAT, có timestamp nhận, timestamp đo, freshness và source. Telemetry còn hạn luôn ưu tiên; sample telemetry cũ/duplicate bị loại trước khi vào ring buffer, Dashboard và Performance.
- Heartbeat chỉ cập nhật CPU/RAM, Disk Capacity và GPU nếu payload thực sự có GPU; field thiếu không xóa dữ liệu.
- Khi telemetry quá hạn, CPU/RAM/Disk Capacity có thể dùng heartbeat còn hạn. GPU và Disk Active không có fallback hợp lệ sẽ thành `STALE`. Session mới reset ordering để Client reboot/clock reset không bị chặn bởi timestamp session cũ.

## 9. Dashboard và biểu đồ Performance

### Dashboard

- Mỗi Client ID có đúng một `ClientCard`; cập nhật được coalesce bằng Swing Timer 250 ms.
- Card wrap trong scroll pane; tìm theo ID/hostname/IP/OS; lọc Online/Offline/Warning/Critical; chọn nhiều Client.
- Health màu cơ sở vẫn dùng 75/90 và RTT; Card ưu tiên badge WARNING/CRITICAL khi có alert mở. Threshold Alert Engine độc lập với màu sức khỏe.
- Card hiển thị riêng `Disk Active` và `Disk Capacity`; read/write throughput tiếp tục nằm trong Performance view. Tooltip metric ghi source và timestamp.
- Detail có 6 tab: Overview, System Information, Performance, File Transfer, Alerts, Network. Tab Alerts dẫn tới cửa sổ quản lý chung.
- Nút Alerts trên Dashboard mở Current Alerts/Alert History, lọc Client/resource/severity/time, Acknowledge/Resolve và editor rule. File Transfer vẫn là placeholder; Network chưa có Wi-Fi distance.

## 10. Alert Engine và persistence — Giai đoạn 5

- Metric rule độc lập: CPU, GPU, Disk Active Time, Disk Capacity Usage. Capacity không thay thế Active Time.
- Mặc định từng rule: WARNING `>80%`, CRITICAL `>90%`, duration 5 giây, hysteresis 5%, cooldown 60 giây; có thể sửa/tắt từ UI.
- Chỉ sample còn hạn và status `OK` được đánh giá. Spike ngắn, `UNSUPPORTED`, `ERROR`, `STALE` không phát alert.
- Lifecycle: `ACTIVE`, `ACKNOWLEDGED`, `RESOLVED`. Admin Acknowledge giữ alert mở; manual/automatic Resolve đóng alert và bắt đầu cooldown.
- Mỗi lifecycle phát tối đa popup WARNING và thêm một popup nếu escalation lên CRITICAL; sample tiếp diễn không spam popup.
- Popup alert Client chạy non-modal trên EDT và tự đóng sau khoảng 15 giây; việc nhận telemetry/network không chờ người dùng đóng popup.
- `ALERT` chỉ gửi qua session đúng Client ID. Client trả `ALERT_ACK`; delivery state tách riêng `PENDING`, `SENT`, `ACKNOWLEDGED`, `FAILED`.
- `FileAlertStore` lưu rule và toàn bộ alert history tại `~/.lanmonitoringserver/alerts-v1.json`, cấu hình lại bằng `-Dlanmonitoring.alert.store=<path>`. Ghi UTF-8, temp file + atomic replace và chạy ngoài network thread.
- Store kiểm tra schema version và số nguyên persisted. Nếu load lỗi, Server chạy alert trong bộ nhớ nhưng vô hiệu persistence cho phiên hiện tại để không ghi đè file hỏng; nếu save lỗi, trạng thái tương tự được công bố qua log/API diagnostic.
- Restart Server nạp lại history, alert đang mở và cooldown đã persist.

## 10.5. File Transfer, TLS và RBAC — Giai đoạn 6

- Protocol bổ sung `PERMISSIONS_UPDATE`, `FILE_OFFER/ACCEPT/REJECT`, `FILE_CHUNK`, `FILE_COMPLETE`, `FILE_RESULT`, `FILE_CANCEL` nhưng vẫn giữ protocol version 1 và frame JSON length-prefix tối đa 1 MiB.
- Chunk file 64 KiB được Base64 để binary-safe. Source, checksum và file nhận đều xử lý streaming; không nạp toàn bộ file vào RAM. Write lock chỉ giữ trong từng frame nên heartbeat/telemetry có thể xen giữa chunk.
- Server tạo kết quả riêng cho từng Client: progress %, byte, tốc độ, `QUEUED/OFFERED/IN_PROGRESS/VERIFYING/SUCCESS/FAILED/CANCELLED`, timeout 15/30 giây, Cancel và lỗi disconnect.
- Client dùng `FileReceiver`: mặc định tối đa 100 MiB, thư mục riêng của ứng dụng, tên đơn giản duy nhất, `.part` + SHA-256 + atomic move. Chặn absolute/path traversal/ký tự Windows cấm, không overwrite, không auto-execute. File trùng dùng hậu tố `(N)`.
- Resume chưa triển khai: sau disconnect, `.part` bị xóa. Đây là quyết định fail-safe; retry là tạo transfer mới thay vì tin offset chưa được xác thực.
- `ClientPermissionStore` persist ba quyền do Server sở hữu: receive files (mặc định false), receive admin notifications và export System Information. Client chỉ nhận update permission qua session TLS đã xác thực.
- RBAC local console: `ADMIN`, `OPERATOR`, `VIEWER`, `CLIENT`. `ADMIN` quản lý permission; `OPERATOR` chỉ gửi tới ID trong scope; `VIEWER/CLIENT` không gửi. Role/principal không do Client cung cấp.
- `AuditLogger` ghi JSON Lines append-only cho permission, file send/cancel, kết quả/denied. Mặc định dưới `~/.lanmonitoringserver/`.
- `TlsSupport` dùng TLS 1.3, PKCS12, trust manager chuẩn và endpoint identification; không có trust-all. TLS xác thực Server, HMAC challenge hiện tại xác thực Client ID. File transfer bị từ chối trên plaintext để giữ tương thích monitoring cũ mà không hạ cấp bảo mật file.
- Server UI có cửa sổ File Transfer/permission với chọn file, multi-select/all Client, Send/Cancel, progress/history. Client UI hiện panel nhận file với progress, path và checksum status.

### Performance

- `PerformancePanel` dùng JFreeChart với 5 view: CPU, Memory, GPU, Disk, Network.
- Cửa sổ hiển thị 60 giây; buffer UI tối đa 120 mẫu; chỉ dataset đang chọn được dựng lại.
- Swing Timer render tối đa 2 lần/giây.
- Pause chỉ dừng render; dữ liệu vẫn tiếp tục vào ring buffer.
- Có trạng thái LIVE/STALE dựa trên timestamp và interval của mẫu.
- Test headless kiểm tra buffer và pause, nhưng chưa kiểm tra nội dung từng series, chuyển view, stale label, layout/interaction trên desktop thật hoặc tải UI lâu dài.

## 11. Chức năng đã được xác minh trong phiên 2026-10-26

Môi trường: Windows, OpenJDK/Javac 17.0.20.1, Apache Ant 1.10.14 đi kèm NetBeans 22.

| Hạng mục | Kết quả | Phạm vi thực sự đã kiểm tra |
|---|---|---|
| Compile toàn bộ `src` + `test` bằng Java 17 | PASS | `javac -encoding UTF-8 -source 17 -target 17`, classpath `lib/*` |
| Compile source với `-Xlint:all` | PASS | Không có warning từ javac trong lần chạy này |
| `ProtocolTest` | PASS | JSON Unicode/delimiter round-trip, malformed JSON, version, type/range |
| `NetworkIntegrationTest` | PASS | TCP loopback thật, 3 Client, register/auth, heartbeat, telemetry, system info generic, PING/PONG, message/ACK, offline/reconnect, impersonation, legacy, server restart |
| `DashboardTest` | PASS | Headless; card reuse, filter/search, select, 75 snapshot UI, 6 detail tabs, alert filters/actions và Card badge/priority |
| `SystemInfoCollectorTest` | PASS | Parser fixture Windows/Linux, missing command, timeout, mapper và Windows Java fallback có diagnostic khi CIM `Access denied` |
| `ClientMetricTrackerTest` | PASS | Source priority, stale/fallback, chống out-of-order, reset session và threshold mặc định |
| `MetricsCollectorTest` | PASS | OSHI thật, CPU/RAM/disk, GPU status; GPU hiện tại `OK` |
| `PerformancePanelTest` | PASS | Headless; buffer 120 mẫu và pause không dừng nhận dữ liệu |
| `AlertEngineTest` | PASS | Biên `>80/>90`, duration, recovery, hysteresis, cooldown, disconnect, active alert khi stale/unsupported, delivery ACK tách admin ACK, escalation không spam và persistence missing/corrupt/unwritable |
| `FileTransferIntegrationTest` | PASS | TLS thật; 1 Server + 3 Client; text/PNG/binary; SHA-256; permission/offline/size/traversal/checksum/Cancel/disconnect/RBAC/audit |
| Alert network integration | PASS | 3 Client TCP thật nhận đúng alert theo Client ID và trả `ALERT_ACK` |
| Ant `release-phase6` | PASS | Clean, compile 89 source, compile 10 test source, chạy toàn bộ test rồi CopyLibs package |
| JAR + `dist/lib` | PASS | Main-Class/Class-Path đúng, class Phase 6 có mặt, 7 dependency, package-level smoke PASS |
| Restart/persistence | PASS | `LanServerService` + `FileAlertStore`: rule, ACTIVE → ACKNOWLEDGED → RESOLVED sống qua ba lần tạo Server |
| GUI desktop tương tác thủ công | CHƯA KIỂM TRA | Server process khởi động từ JAR nhưng phiên công cụ không có interactive window station; không thể quan sát/click popup |
| Runtime Linux | CHƯA KIỂM TRA | Chỉ parser/fallback test trên Windows |
| TLS file transfer | PASS | TLS 1.3 loopback, certificate trust + hostname verification, HMAC Client auth |
| WAN/adversarial security, tải >32 Client | CHƯA KIỂM TRA | Không có test tương ứng |

Các kết quả trên được chạy thực tế sau Giai đoạn 6; không phải kết quả suy đoán từ README hay JAR cũ.

## 12. Chức năng chưa hoàn thành

- Resume file giữa phiên (hiện retry an toàn bằng transfer mới và xóa `.part`).
- Persistence riêng cho bảng file-transfer history; audit JSONL đã persist nhưng bảng runtime reset khi restart.
- Xác minh Ansible end-to-end trên Linux/Windows managed host thật và hoàn tất checklist Phase 7.
- Đo/ước lượng khoảng cách qua Wi-Fi.
- UI tạo/quản lý certificate; hiện TLS cấu hình bằng PKCS12 + JVM property/environment.
- Persistence cho client inventory và telemetry (alert history đã persist).
- Xác minh thực tế trên Linux và GUI desktop end-to-end.

## 13. Lỗi và rủi ro kỹ thuật cần xử lý/ra quyết định

### Mức cao

1. **Plaintext monitoring vẫn tồn tại để tương thích:** nếu người dùng không cấu hình TLS, đăng ký/telemetry cũ vẫn có rủi ro nghe lén/MITM. File transfer và permission update đã fail-closed trên plaintext; bản triển khai thật phải cấu hình TLS cho mọi Server/Client.
2. **Giới hạn khoảng 32 session:** blocking worker pool tối đa 32; chưa phù hợp nếu yêu cầu “nhiều Client” vượt ngưỡng này. Dashboard test 75 card không chứng minh 75 kết nối mạng.
3. **Persistence chưa bao phủ telemetry/inventory:** alert đã bền vững, nhưng restart vẫn mất client snapshot, system-info và telemetry chart history.

### Mức trung bình

1. **CIM vẫn bị policy môi trường chặn:** fallback và test đã đúng, nhưng dữ liệu DXDIAG-style đầy đủ vẫn cần xác minh trên một máy Windows mục tiêu cho phép CIM.
2. **Validation System Information chưa chặt:** `SystemInfoMapper` ít giới hạn độ dài/list/range hơn telemetry mapper. Frame 1 MiB giới hạn tổng thể nhưng payload độc hại vẫn có thể tạo danh sách/chuỗi lớn trong giới hạn đó.
3. **JSON parser không giới hạn nesting:** frame bị giới hạn 1 MiB nhưng JSON lồng sâu có thể gây stack exhaustion. Chưa có fuzz/adversarial test.
4. **Credential lưu dạng plaintext trong Java Preferences:** phù hợp demo nhưng chưa dùng OS keystore; không có UI revoke/rotate/list credential.
5. **ACK của MESSAGE thường chưa có pending registry:** ALERT_ACK đã ràng buộc alert/client và persist delivery state; MESSAGE/ACK cũ vẫn chưa có timeout/retry registry.

### Mức thấp / chất lượng dự án

- Không có `.gitignore`; binary/build output gây nhiễu Git.
- Chỉ có một commit nên khó truy vết từng giai đoạn và rollback chọn lọc.
- Test là runner với `main`/assert tùy biến, chưa có report chuẩn JUnit/CI.
- Kết quả CIM đầy đủ phụ thuộc policy Windows; luôn ưu tiên kết quả test thực tế và diagnostic fallback của phiên hiện tại.
- Chưa xác minh bằng thao tác GUI thật, HiDPI, resize, đóng/mở detail nhiều lần hoặc soak test. Checklist chi tiết nằm ở `docs/PHASE5_GUI_TEST_CHECKLIST.md`.

## 14. Kết quả Giai đoạn 5

**Giai đoạn 5 đã hoàn thành ở mức source, automated tests và package. Regression của Giai đoạn 5 tiếp tục PASS trong `release-phase6`; desktop checklist vẫn là gate trước demo cuối kỳ.**

Điểm thuận lợi:

- Telemetry/Dashboard metric có status, timestamp, source và chính sách stale/fallback đã có regression test.
- Server có ring buffer theo Client và event realtime.
- Dashboard đã có health color và tab Alerts placeholder.
- Model/service/UI đã tách tương đối rõ; không cần viết lại kiến trúc.

Các quyết định đã áp dụng:

1. Disk threshold mặc định áp dụng cho **Disk Active Time**, không phải capacity. `UNSUPPORTED/ERROR/STALE` không được coi là vượt ngưỡng.
2. Rule mặc định yêu cầu duy trì 5 giây, hysteresis 5%, cooldown 60 giây và chỉ notify khi tạo/escalate.
3. Alert rule/history persist bằng JSON store nhẹ; không thêm dependency SQLite.
4. `AlertThresholds.DEFAULT` đặt CPU/GPU/Disk Active ở `>80%`, độc lập với health color 75/90.
5. Artifact phải luôn được tạo bằng `release-phase6`; không dùng JAR cũ sau thay đổi source.
6. Alert Engine test dùng sample xác định; network ACK test dùng telemetry capacity thật, không gây tải nguy hiểm.
7. `ALERT_ACK` chỉ cập nhật delivery state; Admin Acknowledge vẫn là thao tác riêng và alert tiếp tục mở.
8. Load persistence lỗi không được phép ghi đè store bằng snapshot rỗng; save lỗi không làm dừng Alert Engine nhưng lịch sử mới chỉ còn trong bộ nhớ phiên đó.

## 14.5. Kết quả Giai đoạn 6

**Giai đoạn 6 đã hoàn thành ở mức source, TLS/TCP integration tests và Ant package.**

- File transfer streaming Server → một/nhiều Client, tiến độ riêng, timeout, Cancel, checksum và xử lý disconnect đã triển khai.
- Folder policy, size limit, duplicate rename, path validation, `.part` và không auto-execute đã triển khai.
- ADMIN/OPERATOR/VIEWER/CLIENT, scope OPERATOR, permission theo Client và audit log đã triển khai.
- File transfer/permission command yêu cầu TLS; không có insecure fallback hay trust-all.
- `FileTransferIntegrationTest` đã thực thi text → 1 Client, PNG → 3 Client, binary, offline, size/space policy, permission, checksum lỗi, path traversal, Cancel, disconnect, RBAC và audit.
- Desktop visual interaction của File chooser/progress/dialog vẫn cần smoke test trên interactive Windows desktop; automated Swing headless đã PASS.

## 15. Kế hoạch đề xuất cho các giai đoạn tiếp theo

### Giai đoạn 7 — Ansible

- Source/integration đã triển khai; công việc còn lại là syntax-check bằng Ansible thật và checklist managed host Windows/Linux.
- Giữ credential trong Ansible Vault ngoài repository và chỉ bật host bằng allowlist rõ ràng.
- Không mở rộng sang arbitrary playbook/remote command; scope vẫn là file văn bản cố định và idempotency.

### Giai đoạn 8 — Ước lượng khoảng cách Wi-Fi

- Làm prototype riêng vì RSSI→distance phụ thuộc phần cứng/môi trường và quyền OS.
- Xác định API lấy RSSI Windows/Linux, tần suất lấy mẫu, calibration và sai số.
- Trình bày là ước lượng, không phải khoảng cách chính xác.

### Giai đoạn 9 — Hoàn thiện

- CI trên Windows + Linux, test Java 17, package smoke test.
- Load/soak test số Client mục tiêu; xem lại mô hình blocking thread hoặc giới hạn được công bố.
- Bắt buộc TLS cho toàn bộ monitoring, certificate lifecycle/mTLS, credential rotation/revoke, input hardening và fuzz protocol.
- Persistence/retention, logging có cấu trúc, UX/accessibility và tài liệu demo/deploy.

## 16. Hướng dẫn cho phiên Codex tiếp theo

1. Đọc file này, `README.md`, `THIRD_PARTY_NOTICES.md`, `build.xml` và `nbproject/project.properties` trước khi sửa.
2. Chạy `git status --short`; **không reset** vì Giai đoạn 1–4 chưa commit và build output/nguồn mới đang cùng tồn tại.
3. Không dùng `dist/LANMonitoringServer.jar` làm baseline; build từ source.
4. Nếu có Ant, chạy `ant release-phase7` (clean → test Giai đoạn 1–7 → jar). Nếu không có Ant, dùng lệnh `javac/java` trong README nhưng không xem đó là xác minh JAR.
5. Ghi riêng kết quả từng test; CIM bị chặn phải PASS qua nhánh fallback có diagnostic, không được bỏ qua toàn bộ collector test.
6. Trên Windows, kiểm tra `Get-CimInstance Win32_Processor` có được policy cho phép. Trên Linux, chạy collector/test thật thay vì chỉ dựa vào parser unit test.
7. Không gộp Disk Active và Disk Capacity; giữ delivery ACK tách khỏi admin Acknowledge khi mở rộng alert.
8. Giữ các `.form` của NetBeans đồng bộ nếu thay đổi vùng generated UI; không viết lại các chức năng Giai đoạn 1–4 đang hoạt động nếu không có lỗi/test chứng minh.
9. Mọi message mới phải giữ giới hạn frame, validation, capability negotiation và tương thích protocol v1 hoặc tăng version có chiến lược migration.
10. Sau khi thay đổi, kiểm tra ít nhất protocol + network integration + dashboard/performance headless và thực hiện `docs/PHASE5_GUI_TEST_CHECKLIST.md` trước khi đánh dấu release-ready.
11. File transfer phải tiếp tục fail-closed nếu không có TLS; không thêm trust-all, không bypass hostname, không tự execute file và không tin filename/offset từ mạng.
12. Trước Giai đoạn 8, hoàn tất `docs/PHASE6_GUI_TEST_CHECKLIST.md` và `docs/PHASE7_TEST_CHECKLIST.md` trên desktop/managed host thật.

## 17. Các file quan trọng để bắt đầu đọc mã

- Entry/UI shell: `LANMonitoringServer.java`, `frmServer.java`, `frmClient.java`.
- Network lifecycle: `service/LanServerService.java`, `service/LanClientService.java`.
- Alerts: `service/AlertEngine.java`, `FileAlertStore.java`, `model/Alert*.java`, `ui/AlertManagementPanel.java`.
- File/RBAC: `service/FileReceiver.java`, `ClientPermissionStore.java`, `AuditLogger.java`, `model/FileTransfer*.java`, `security/AuthorizationContext.java`, `TlsSupport.java`, `ui/FileTransferPanel.java`.
- Protocol: `network/FramedConnection.java`, `protocol/ProtocolMessage.java`, `MessageType.java`, `TelemetryMapper.java`, `SystemInfoMapper.java`.
- Security: `security/Authentication.java`, hai Preferences store/registry.
- System info: `monitor/system/*`.
- Telemetry: `monitor/metrics/OshiMetricsCollector.java`, `NvidiaSmiGpuMetricsProvider.java`, `model/TelemetrySample.java`.
- UI: `ui/ClientDashboardPanel.java`, `ClientCard.java`, `ClientDetailPanel.java`, `PerformancePanel.java`, `SystemInfoPanel.java`.
- Tests: mười một runner dưới `test/lanmonitoringserver/`; `FakeAnsiblePlaybook` là child-process fixture, không phải runner độc lập.

## 18. Phase 6 Final Validation — 2026-10-27

### Kết quả đã thực thi

- `release-phase6` chạy bằng JDK 17.0.20.1 và Apache Ant 1.10.14 đi kèm NetBeans 22: clean, compile 89 source + 10 test source, toàn bộ runner Giai đoạn 1–6 PASS, sau đó mới tạo JAR.
- `FileTransferIntegrationTest` PASS với TCP/TLS thật: TXT tới một Client, PNG đồng thời tới ba Client, binary, SHA-256, retry bằng transfer mới/tên không trùng, permission/offline/size/free-space, checksum lỗi, Cancel, disconnect và telemetry tiếp tục hoạt động.
- `Phase6SecurityValidationTest` PASS: TLS không cấu hình là opt-in/off; CA không tin cậy và hostname/IP sai SAN bị handshake từ chối; plaintext file/permission bị từ chối; offer timeout thành `FAILED`; symlink receive root bị chặn; bốn role được kiểm tra ở service layer; permission reload/rollback và audit JSONL được kiểm tra.
- Regression `NetworkIntegrationTest` tiếp tục PASS cho ba Client, Client ID gắn với HMAC credential, cảnh báo đúng Client, reconnect và restart. Monitoring/Alert không bị ảnh hưởng bởi thay đổi Giai đoạn 6.
- Static GUI review xác nhận callback progress/model chạy qua EDT; Send/Apply đã bất đồng bộ. Final Validation sửa Cancel để không thực hiện network I/O trên EDT.
- Artifact smoke test chạy `Phase6SecurityValidationTest` với application classes lấy từ JAR mới đã PASS. JAR 668629 byte, SHA-256 `A44454CE55887070CCDC8C2750C39547708C1032B3CE41364703A713F368FF54`; manifest đúng và `dist/lib` có 7 JAR.

### Lỗi đã sửa trong Final Validation

1. Cancel trên Server GUI từng gọi `cancelFileTransfer` trực tiếp từ EDT; nay chạy qua `CompletableFuture`, lỗi được đưa lại EDT để hiển thị.
2. ADMIN từng có thể persist permission trong khi Client online qua plaintext nhưng Client không nhận update; nay thao tác bị fail-closed và audit `DENIED/TLS required`.
3. Server từng chấp nhận `FILE_ACCEPT/REJECT/RESULT` từ session plaintext đã xác thực; nay các response này cũng bắt buộc TLS.
4. `ClientPermissionStore.put` từng giữ giá trị mới trong RAM nếu save thất bại; nay rollback về trạng thái trước và ném lỗi cho caller.
5. Receive directory là symbolic link có thể dẫn ra ngoài đường dẫn cấu hình; nay so sánh real/no-follow path và từ chối symlink root/parent escape.
6. Timeout 15/30 giây trước đây khó kiểm thử deterministically; nay giữ nguyên mặc định nhưng hỗ trợ `lanmonitoring.file.offer.timeout.ms` và `lanmonitoring.file.result.timeout.ms` để cấu hình/test.

### Giới hạn và release gate còn lại

- Phiên công cụ hiện tại không có interactive window station, do đó chưa chạy/quan sát desktop GUI với 1 Server + 3 Client. Headless/static check không được ghi nhận là GUI smoke PASS. Manual gate nằm tại `docs/PHASE6_GUI_TEST_CHECKLIST.md`.
- Chưa chạy runtime trên Linux; chỉ các parser/fallback đa nền tảng chạy trên Windows.
- TLS được hỗ trợ nhưng **không bật mặc định**: thiếu PKCS12/truststore thì monitoring dùng plaintext tương thích cũ. File Transfer và permission fail-closed, nhưng monitoring/credential bootstrap plaintext vẫn có rủi ro MITM.
- TLS hiện xác thực Server certificate + hostname; Client ID được xác thực riêng bằng HMAC secret. Đây chưa phải mTLS và RBAC vẫn là identity của console local/JVM property, không phải multi-user login/SSO.
- Audit ghi lỗi ra log nếu audit file không ghi được nhưng hiện không fail-closed toàn bộ hành động quản trị. Khi triển khai thật cần bảo vệ/quản lý quyền filesystem và giám sát audit failure.
- Resume giữa phiên và persistence bảng transfer history vẫn chưa có; retry tạo transfer mới an toàn.

### Baseline Giai đoạn 6 khi bắt đầu Giai đoạn 7

Backend/network/security Giai đoạn 6 đã PASS automated regression và package. Người dùng đã yêu cầu tiếp tục triển khai Giai đoạn 7 dù desktop checklist Giai đoạn 6 chưa hoàn tất; vì vậy checklist đó vẫn là release gate tích lũy, không được hiểu là đã PASS.

## 19. Giai đoạn 7 — Ansible Integration

### Kiến trúc đã triển khai

- Java vẫn là ứng dụng chính. `AnsibleService` quản lý source UTF-8, allowlist, RBAC/audit và job lifecycle; `AnsibleRunner` chỉ chạy hai playbook bundled bằng `ProcessBuilder(List<String>)`.
- `AnsibleInventoryStore` đọc cấu hình riêng `ansible-managed-hosts-v1.json`. `applicationClientId` chỉ là liên kết tùy chọn; không suy ra inventory/quyền Ansible từ trạng thái Online của Java Client.
- Inventory YAML được sinh riêng cho từng job với nhóm `lan_linux`/`lan_windows`. Alias, address, port, connection và destination đều được validate; host phải `enabled=true`.
- Credential không nằm trong inventory/source/log. Runner bắt buộc một file Ansible Vault có header hợp lệ và một vault-password file ngoài repository. `.gitignore` chặn các pattern secret/runtime Phase 7.
- Linux playbook dùng `ansible.builtin.copy` + `stat`; Windows dùng `ansible.windows.win_copy` + `win_stat`. Cả hai assert SHA-256 và phát marker ổn định để Java parse per host.
- Tab Swing **Ansible Management** có managed-host list, content editor, Generate, Deploy/Sync, Verify, Cancel, bảng kết quả và log. Process chạy ngoài EDT; callback UI marshal về EDT.
- Chỉ ADMIN được Generate/Deploy/Verify/Cancel ở service layer. Không có API từ Client để cung cấp playbook hay command. Không có auto-sync nền.
- Native Linux và WSL2 được hỗ trợ bằng cấu hình. WSL mode chuyển drive-letter path sang `/mnt/<drive>` và gọi `wsl.exe -- ansible-playbook` không qua shell.

### Automated validation đã thực thi

- `release-phase7` BUILD SUCCESSFUL: clean-compile 102 source + 12 test source, chạy toàn bộ regression Giai đoạn 1–7 rồi mới package.
- `AnsibleIntegrationTest` dùng một Java child process giả lập output Ansible có kiểm soát; đây là ProcessBuilder integration thật nhưng **không phải deploy host thật**.
- Đã kiểm tra: exact UTF-8 source, SHA-256, inventory Windows/Linux không chứa credential, ba host thành công, lần hai `changed=false`, verify-only, host offline `UNREACHABLE`, permission failure `FAILED`, kết quả partial success, disabled host, ADMIN/VIEWER enforcement, audit, timeout và Cancel.
- `DashboardTest` headless kiểm tra panel mới render managed host và result.
- Test cấu trúc xác nhận playbook chứa đúng FQCN copy/stat, SHA-256 và verify tags. Chưa chạy `ansible-playbook --syntax-check` vì executable Ansible không có trong môi trường.
- Package smoke test chạy lại `AnsibleIntegrationTest` với application classes từ JAR mới và PASS. JAR SHA-256 `E51A231EA3E92BFADC11C6018E6183D8EFA6DA1369F2172115A351A38722C00F`.

### Giới hạn xác minh

- Windows host và Linux host thật: **CHƯA CHẠY**.
- `ansible-playbook` native: không được cài. `wsl.exe` tồn tại nhưng WSL service trả `E_ACCESSDENIED`, nên không chạy syntax-check/playbook thật.
- GUI desktop tương tác: chưa smoke-test do không có interactive window station; chỉ headless/static EDT test PASS.
- File source/job artifacts không chứa secret nhưng hiện chưa có retention cleanup định kỳ.
- Ansible Vault bảo vệ at-rest, không thay thế SSH/TLS, host-key/certificate validation hoặc filesystem permissions.
- WSL2 phù hợp demo nhưng tài liệu Ansible không xem WSL là Control Node production được hỗ trợ; production nên dùng Linux Control Node.

### File quan trọng Giai đoạn 7

- Java: `service/AnsibleService.java`, `AnsibleRunner.java`, `AnsibleInventoryStore.java`, `AnsibleInventoryWriter.java`.
- Model/UI: `model/Ansible*.java`, `ui/AnsibleManagementPanel.java`, tab tích hợp trong `frmServer.java`.
- Automation: `ansible/playbooks/sync_linux.yml`, `sync_windows.yml`, `ansible/collections/requirements.yml`, `ansible/config/managed-hosts.example.json`.
- Test/docs: `AnsibleIntegrationTest.java`, `FakeAnsiblePlaybook.java`, `docs/PHASE7_ANSIBLE_SETUP.md`, `docs/PHASE7_TEST_CHECKLIST.md`.

### Gate trước Giai đoạn 8

Source và controlled integration của Giai đoạn 7 hoàn chỉnh, nhưng chưa được coi là production/release-ready cho Windows/Linux. Trước Giai đoạn 8 cần chạy `ansible-playbook --syntax-check`, hoàn tất checklist real-host với ít nhất một Linux + một Windows (và ba host nếu lab cho phép), kiểm chứng idempotency/checksum/permission/offline, đồng thời smoke-test tab Swing trên desktop thật.
## 20. Giai đoạn 8 - Wi-Fi Signal & Estimated Distance (2026-10-27)

### Kiến trúc đã triển khai

- Protocol thêm capability `WIRELESS_SIGNAL_V1` và message `WIRELESS_SIGNAL`. Message chỉ được nhận sau HMAC authentication và session buộc khớp `clientId`; timestamp tương lai/out-of-order bị từ chối.
- `WirelessSignalSample`, `NetworkLatencySample` và `WirelessDistanceEstimate` là ba model riêng. RTT ứng dụng không tham gia công thức khoảng cách.
- Windows collector gọi `netsh wlan show interfaces`, không scan chủ động. Nếu output có trường riêng `Rssi`/`RSSI`, collector dùng trực tiếp giá trị dBm hợp lệ; `Signal`/Link Quality % vẫn là metric độc lập và tuyệt đối không được quy đổi thành dBm. Parser chuẩn hóa case, BOM, Unicode accent/Unicode minus, hỗ trợ nhiều interface và chọn interface connected. Có thể override charset bằng `-Dlanmonitoring.wifi.netsh.charset=<charset>` khi code page của `netsh` khác JVM default.
- Linux collector gọi `iw dev` và `iw dev <adapter> link`; RSSI dBm nếu driver cung cấp là tín hiệu Client-AP. Probe `iw phy` chỉ báo capability FTM cục bộ, không tuyên bố phép ranging đã hoạt động.
- Client đo opt-in mỗi 2 giây trên executor riêng, không chặn heartbeat/telemetry/EDT. SSID/BSSID mặc định bị ẩn; target AP được pseudonym hóa bằng SHA-256 BSSID để gắn calibration.
- `WirelessTracker` giữ tối đa 120 observation/Client, bỏ sample cũ và dùng EMA alpha 0.30. `LogDistanceWirelessEstimator` fit reference RSSI/path-loss exponent/RMSE từ ít nhất hai điểm thực tế khác khoảng cách.
- Chỉ ước lượng `ACCESS_POINT`; không đủ RSSI/calibration, stale/unsupported hoặc target khác thì trả NOT AVAILABLE/UNSUPPORTED. `WifiRttProvider` là extension point, provider mặc định không giả lập hỗ trợ.
- Tab Network hiển thị Network Type, adapter, SSID/BSSID theo privacy setting, RSSI, Link Quality, RTT, target/method/FTM, Estimated Client-AP Distance, confidence/uncertainty, last updated và hai biểu đồ.

### Kết quả đã thực thi

- `ant release-phase8`: BUILD SUCCESSFUL từ clean source; compile 125 source + 14 test source, toàn bộ runner Giai đoạn 1-8 PASS rồi mới đóng gói.
- Package smoke chạy hai test Phase 8 với application class lấy từ JAR mới: PASS. JAR 875073 byte, SHA-256 `21DE7F035F0A7D57205B4EA4C9A7728672785904734327300C80A013799B8D16`; manifest/Main-Class/Class-Path, 7 file `dist/lib` và 5 asset `dist/ansible` đã xác minh.
- `WirelessCollectorTest`: fixture Windows thực tế Realtek RTL8852BE kiểm tra `Rssi=-44`, Signal 100%, adapter, tiếng Anh/Việt, Unicode/BOM, nhiều interface, Signal-only, RSSI thiếu/malformed/out-of-range, disconnected, Access Denied và thiếu `netsh`. RSSI thật đi qua tracker và chỉ tạo Estimated Client-AP Distance sau calibration hợp lệ. Fixture Linux/collector Linux không thay đổi.
- `WirelessPhase8Test`: protocol strict round-trip/malformed input, calibration regression, EMA, AP labeling, quality-only UNSUPPORTED, out-of-order rejection và Swing headless EDT construction PASS.
- Người dùng đã xác minh trực tiếp trên Windows/Realtek RTL8852BE rằng `netsh wlan show interfaces` có `Signal: 100%` và trường riêng `Rssi: -44`; fixture regression phản ánh đúng định dạng này. Phiên công cụ Codex vẫn không truy cập được adapter Wi-Fi (lần probe trước exit code 1), nên thay đổi parser được xác minh tự động từ fixture do người dùng cung cấp, chưa phải runtime collection trong tiến trình Java của phiên này.

### Giới hạn bắt buộc phải công bố

- Chưa đo ở khoảng cách thực, chưa phân tích tường/multipath/nhiễu/adapter và chưa chạy Linux hardware. Không tuyên bố accuracy thực tế hoặc Windows/Linux device validation PASS.
- Windows/driver mới có thể cung cấp RSSI dBm trực tiếp và khi đó estimator được phép dùng sau calibration đúng AP. Máy không có trường RSSI, RSSI lỗi hoặc chỉ có Link Quality vẫn trả RSSI `NOT_AVAILABLE` và distance `UNSUPPORTED`; không áp dụng quy đổi phần trăm tùy tiện.
- RSSI là Client-AP, không phải Client-Server. Calibration nằm trong RAM Server của phiên hiện tại; đổi AP làm reset smoothing và calibration cũ không được dùng cho target mới.
- Wi-Fi RTT/FTM chưa có provider desktop thực tế. Capability phần cứng đơn lẻ không đủ nếu AP/API/driver không hỗ trợ phiên ranging.
- Phiên công cụ không có interactive desktop nên GUI chỉ được headless/static test, chưa smoke-test trực quan.

### Hướng dẫn phiên tiếp theo

- Baseline mới: chạy `ant release-phase8`, không dùng JAR Phase 7 cũ.
- Hoàn tất `docs/PHASE8_WIFI_TEST_CHECKLIST.md` với khoảng cách Client-AP đo thủ công, không ghi Client-Server nếu chỉ dùng RSSI.
- Không log SSID/BSSID nếu không cần demo. Không đổi mặc định privacy và không dùng RTT mạng để suy ra mét.
- Trước khi nâng confidence, cần đủ điểm calibration, ghi điều kiện môi trường, AP/adapter/band và kiểm chứng độc lập.

## 21. Giai đoạn 9 — Final Stabilization & Release Readiness (2026-10-27)

### Thay đổi ổn định đã thực hiện

- Giới hạn tài nguyên dài hạn: log Swing được cắt theo dung lượng, lịch sử truyền file được giới hạn, pending PING được dọn theo thời gian và số lượng, các chart tiếp tục dùng cửa sổ dữ liệu hữu hạn.
- `CommandRunner` dùng executor daemon dùng chung thay vì tạo thread pool mới cho từng lệnh hệ điều hành. Vòng đời Server/Client được đóng idempotent; Server đã đóng hoàn toàn không thể khởi động lại nhầm.
- `WirelessNetworkPanel` quản lý timer theo `addNotify/removeNotify`, tránh timer giữ panel sau khi bị tháo. RSSI dBm và Link Quality % dùng dataset/trục riêng, không trộn hai đại lượng.
- Alert mutation được kiểm tra ở service layer: chỉ ADMIN có thể đổi rule, Acknowledge hoặc Resolve. VIEWER không chỉ bị ẩn/khóa nút mà còn bị từ chối tại backend và có audit `DENIED`.
- `AnsibleManagementPanel` nạp host ngoài EDT; log UI của Server, Client và Ansible đều dùng `SwingTextLimiter`. Các callback cập nhật UI tiếp tục marshal về EDT.
- Bản phân phối có launcher Windows/Linux, config example không chứa secret, Ansible allowlist assets, tài liệu vận hành/kiến trúc/test và third-party notices. Không thay đổi build system NetBeans/Apache Ant.

### Kết quả thực thi

- `ant release-phase9`: **BUILD SUCCESSFUL** từ clean source; baseline ban đầu compile 126 source + 16 test source. Bản wireless fix cuối compile 128 source + 17 test source và chạy toàn bộ runner Giai đoạn 1–9 trước khi đóng gói.
- `ant clean compile -Djavac.compilerargs=-Xlint:all`: **BUILD SUCCESSFUL**, không có cảnh báo compiler.
- Package smoke chạy `Phase9StabilityTest` và `Phase9UiStabilityTest` với application classes lấy từ JAR mới: **PASS**.
- Entry point ngoài IDE được kiểm tra với hai mode xung đột: tiến trình trả exit code 2 và thông báo lỗi đúng.
- Bằng chứng chi tiết nằm ở `docs/TEST_RESULTS.md`; kiến trúc và sơ đồ ở `docs/ARCHITECTURE.md`; quy trình demo ở `docs/DEMO_GUIDE.md`.

### Gate thủ công chưa được thực thi

- Chưa mở và thao tác GUI desktop trong phiên Codex hiện tại; headless Swing test không được coi là GUI smoke PASS.
- Chưa chạy lab vật lý 1 Server + 2 Windows Client + 1 Linux Client, chưa kiểm chứng tải/nhiều thiết bị, TLS certificate thực tế, Ansible real-host hoặc Wi-Fi accuracy ngoài fixture/calibration model.
- Các bước cần người dùng thực hiện và chỗ ghi bằng chứng nằm trong `docs/PHASE9_MULTI_DEVICE_CHECKLIST.md`. Chỉ sau khi checklist này PASS mới nên gắn nhãn bản phát hành đã được xác minh đầy đủ trên thiết bị thật.

### Baseline cho phiên tiếp theo

- Dùng `ant release-phase9`; không dùng JAR Giai đoạn 8 cũ. Khởi chạy ngoài IDE qua `dist/scripts/run-server.*` và `run-client.*`.
- Đọc `docs/README.md` làm mục lục tài liệu. Không ghi secret vào `config/runtime.example.env`; truyền cấu hình nhạy cảm qua JVM properties/environment hoặc kho bí mật ngoài repository theo hướng dẫn cài đặt.
- Duy trì ranh giới khoa học của Giai đoạn 8: RSSI chỉ mô tả Client–AP, RTT không phải khoảng cách, và không hiển thị distance nếu thiếu calibration hợp lệ.
- Không tuyên bố production/release-ready tuyệt đối cho đến khi hoàn tất gate desktop và multi-device nêu trên.

## 22. Windows Wireless diagnostics hotfix (2026-10-27)

- Điều tra đường đi đầy đủ xác nhận JAR hiện đại dùng framed JSON v1; Server quảng bá `WIRELESS_SIGNAL_V1`, bắt buộc HMAC-authenticated `clientId`, mapper giữ riêng RSSI dBm/Signal %, tracker lưu observation và panel cập nhật qua EDT.
- Parser Windows đã có hỗ trợ trực tiếp `Rssi`/`RSSI`; regression bổ sung trường hợp Realtek có đồng thời `Signal: 100%` và `Rssi: -38 dBm`.
- Bản wireless hotfix đã bổ sung marker `phase9-wireless-fix-2`; baseline distance đầu tiên dùng `phase9-server-client-distance-1`; baseline hiện tại là `phase9-distance-map-1`. CONNECT báo release marker tùy chọn, tương thích ngược; Server lọc marker trước khi log.
- Bổ sung log riêng tư theo từng chặng: modern/legacy detection, capability negotiation, scheduler state, mẫu đầu tiên gửi, mẫu đã xác thực/lưu và số observation UI nạp. Log không chứa SSID/BSSID hoặc secret.
- `WirelessTransportIntegrationTest` dùng TCP loopback thật và collector fixture có kiểm soát để xác nhận RSSI `-38 dBm` đi qua modern authentication, JSON mapping, đúng Client ID, privacy filter, `WirelessTracker` và callback Server. Đây không phải test adapter Wi-Fi thật.
- Callback wireless trong `frmServer` được marshal rõ ràng về Swing EDT; UI regression xác nhận RSSI/quality/Last Updated và chart nhận điểm.
- Môi trường Codex gọi `netsh wlan show interfaces` nhận `WlanQueryInterface error 5`; không chạy nâng quyền và không tuyên bố Windows adapter runtime PASS. Quy trình desktop/log cần thu thập nằm tại `docs/WINDOWS_WIRELESS_DIAGNOSTICS.md`.
- Client có lệnh **Test Wi-Fi collector now** chạy một probe cục bộ không gửi identifier. Nút calibration bị khóa cho tới khi có RSSI dBm hiện tại và AP target hash hợp lệ; dialog không còn điền sẵn số ví dụ có thể bị hiểu nhầm là dữ liệu thật.
- Calibration được persist atomically theo cặp Client ID + SHA-256 AP target tại `~/.lanmonitoringserver/wireless-calibrations-v1.json`, không lưu SSID/BSSID, tự khôi phục sau Server restart và không dùng chéo Client/adapter hoặc AP. Lỗi persistence rollback calibration mới; store lỗi khi startup fallback về memory-only với diagnostic stderr.
- Sửa trạng thái distance của mẫu quá hạn từ `OK` không có giá trị sang `STALE` rõ ràng.

## 23. Server-Client Physical Distance (2026-10-27)

### Kiến trúc đã triển khai

- Miền dữ liệu mới `ServerClientDistance` độc lập hoàn toàn với `NetworkLatencySample` và `WirelessDistanceEstimate`. Các method là `DIRECT_MEASUREMENT`, `POSITION_BASED_ESTIMATION`, `MANUAL_MEASUREMENT`, `NOT_AVAILABLE`; bản hiện tại không có direct-ranging provider nên không tự sinh `DIRECT_MEASUREMENT`.
- `SpatialPosition` lưu X/Y theo mét, Z tùy chọn, reference point và timestamp. Chỉ tính Euclidean 2D khi Server/Client có cùng reference point; chỉ dùng 3D khi cả hai đầu đều có Z. Không mặc định Server ở `(0,0)`.
- `ServerClientDistanceStore` lưu atomically tại `~/.lanmonitoringserver/server-client-distances-v1.json`, có rollback khi ghi lỗi, key theo Client ID ổn định và không dùng IP làm identity. Override bằng `-Dlanmonitoring.distance.store=<path>`.
- Manual measurement được hiển thị trực tiếp với `MANUAL_MEASUREMENT`; timestamp không tự chạy theo heartbeat nên không bị mô tả là realtime. Giá trị âm, NaN, Infinity, reference rỗng và Client ID lỗi đều bị từ chối.
- API thay đổi vị trí/khoảng cách nằm trong `LanServerService`, chỉ ADMIN được phép; backend ghi audit cho update/clear và không chỉ dựa vào trạng thái nút Swing.
- `ClientSnapshot` mang distance riêng. `ClientCard` hiển thị `Distance from Server` và nguồn Manual/Position-Based/Direct. `ClientDetailPanel` có tab thứ 7 `Server-Client Distance`, gồm vị trí hai đầu, distance, method, source, error, timestamp, reference và các nút cấu hình/xóa.
- Module Wireless không đổi semantics: RSSI/calibration vẫn chỉ cho Estimated Client-to-AP Distance; RTT vẫn chỉ là latency.

### Kiểm thử và artifact đã thực thi

- `ant release-phase9`: **BUILD SUCCESSFUL**, clean compile 136 source + 18 test source; toàn bộ runner Giai đoạn 1–9 PASS.
- `ServerClientDistanceTest`: PASS cho `NOT_AVAILABLE`, công thức 2D 3-4-5, công thức 3D, reference mismatch, manual/error, validation âm/NaN/Infinity, persistence/restart và cách ly nhiều Client.
- `NetworkIntegrationTest`: PASS với 3 Client loopback; distance gắn stable authenticated Client ID, còn nguyên khi Offline và reconnect cùng ID. Monitoring, telemetry, alerts và wireless regression tiếp tục PASS.
- `DashboardTest`: PASS headless EDT với tab thứ 7, Card hiển thị distance/source và Client Offline vẫn giữ dữ liệu vị trí quản trị.
- Package smoke chạy `ServerClientDistanceTest` và `Phase9UiStabilityTest` với application classes từ JAR mới: PASS.
- Artifact: `dist/LANMonitoringServer.jar`, 976173 byte, SHA-256 `0799B096677654910DD2B37F9D77DFFFFB50F915602C7C14B37EBE160F6F42ED`; manifest/Main-Class/Class-Path đúng, 7 dependency trong `dist/lib`, 18 tài liệu trong `dist/docs`.

### Giới hạn còn lại

- Phiên công cụ không có interactive desktop nên chưa thao tác trực quan dialog nhập tọa độ/manual trên Windows; chỉ headless Swing regression PASS.
- Không có direct-ranging hardware/API provider đã kiểm chứng. Vì vậy automatic Server-to-Client distance vẫn `NOT_AVAILABLE`; manual và tọa độ do ADMIN nhập không phải realtime.
- Chưa đo/đối chiếu bằng thước trên nhiều máy thật. RSSI Client-AP và RTT tuyệt đối không được dùng để thay thế.

### Hướng dẫn tiếp tục

1. Chạy `ant release-phase9`, dùng launcher mới trong `dist/scripts`; kiểm tra log có marker hiện hành trong mục 26.
2. Mở Card Client > tab `Server-Client Distance`; nhập Server Position và Client Position trong cùng reference point, hoặc nhập quãng đường đã đo thật.
3. Kiểm tra restart Server, Offline/reconnect và nhiều Client trên desktop thật; xác nhận từng Client giữ đúng record của mình.
4. Chỉ thêm `DIRECT_MEASUREMENT` khi có provider ranging thật, xác thực đúng hai endpoint và có timestamp/quality; không nối RSSI/AP hay ping vào domain này.

## 24. Multi-Client Distance Table & 2D Room Map (2026-10-27)

### Thay đổi đã triển khai

- `ServerClientDistanceManagementPanel` hiển thị bảng tất cả Client với Client ID, hostname, IP, distance, measurement method, data status và last updated.
- `RoomMapPanel` chỉ vẽ Server và các Client có `SpatialPosition` được ADMIN cấu hình trong cùng reference point. Không có auto-layout giả làm tọa độ thực; manual-only Client vẫn ở bảng nhưng không được đặt lên map.
- Đường nối Server–Client hiển thị khoảng cách tính trực tiếp từ tọa độ và có nhãn `(coords)`, tránh nhầm với manual measurement đang được ưu tiên ở bảng.
- Có Zoom +/-, mouse-wheel zoom và Fit to View. Các thao tác chỉ đổi screen transform, không sửa model/persistence.
- Online/Offline chỉ đổi màu điểm kết nối. `ServerClientDistanceStatus` độc lập và không bị đổi khi Client disconnect.
- Dashboard có nút `Distance Map`; mỗi `ClientCard` có `Open Room Map` để mở view chung và chọn đúng stable Client ID.
- Callback từ network/service cập nhật bảng/map qua Swing EDT; view dùng cùng `ClientSnapshot` với Card và Client Detail.

### Kiểm thử tự động đã thực thi

- `DistanceManagementPanelTest`: PASS với ba Client có tọa độ thật trong fixture kiểm thử, một Client thiếu vị trí, selection từ Card, Client Offline, background-to-EDT update, headless paint, zoom/fit và move Client làm distance đổi từ tọa độ.
- `ServerClientDistanceTest`: tiếp tục PASS cho công thức, manual, invalid values, persistence/restart và Client isolation.
- `NetworkIntegrationTest`: tiếp tục PASS với ba TCP Client loopback, Offline/reconnect, Monitoring, telemetry, Alerts và Wireless transport.
- Toàn bộ runner Giai đoạn 1–9 tiếp tục PASS; Ansible vẫn là controlled process, Wireless vẫn là fixture/loopback thay vì real radio accuracy.

### Giới hạn và manual gate

- Không có interactive desktop trong phiên công cụ, vì vậy chưa tuyên bố bảng/map/dialog đã GUI desktop PASS.
- Chưa dùng ba máy thật để nhập vị trí đo bằng thước và quan sát map. Checklist bắt buộc nằm tại `docs/SERVER_CLIENT_DISTANCE_TEST_CHECKLIST.md`.
- Không có direct ranging provider. Không có manual/common-coordinate data thì automatic Server–Client distance vẫn `NOT_AVAILABLE`.

### Artifact hiện tại

- `ant release-phase9`: BUILD SUCCESSFUL, clean compile 138 source + 19 test source và chạy toàn bộ regression Giai đoạn 1–9.
- `dist/LANMonitoringServer.jar`: 1011518 byte, SHA-256 `387CEE179A09A65DEED2E8C0889AE2FBEE1A57CEB3BD2217C1D8A5FF2CFDE106`.
- Package gồm 7 JAR dependency và 19 tài liệu; release marker `phase9-distance-map-1`.

Các số liệu ngay trên là artifact Distance Map tại thời điểm mục 24 được viết; baseline hiện hành nằm
ở mục 26.

## 25. Audit tiếp nhận dự án và Wi-Fi Fingerprinting baseline (2026-10-27)

> Đây là mục trạng thái mới nhất và có ưu tiên hơn các số liệu artifact/kế hoạch lịch sử ở
> các mục phía trên. Phiên audit chỉ đọc/đánh giá, chạy build-regression và cập nhật tài liệu này;
> không sửa source, không triển khai tính năng và không reset Git.

### Repository và nguồn đối chiếu

- Nhánh hiện tại `main` trỏ tới commit duy nhất `541f4eb` (`LANMonitoringServer`, ngày
  2026-10-16). Phần lớn kiến trúc Giai đoạn 1–9, test, tài liệu và module Wi-Fi mới vẫn nằm trong
  working tree chưa commit. Không được dùng commit gốc hoặc JAR 58 KB trong commit làm baseline.
- Worktree đã bẩn trước audit, gồm source đã sửa, source/test/tài liệu mới và build output. Không có
  `AGENTS.md`. Phiên audit không xóa hoặc hoàn nguyên các thay đổi này.
- Repository tham khảo `D:\LT_MMT\Wifi-Indoor-Location` có 5 commit và là prototype Python không
  cấu trúc: thu thập scan, CSV Radio Map, `scikit-learn` KNN và multilateration. Dự án Java chỉ tái
  sử dụng ý tưởng fingerprint vector/KNN; không nhập Python, CSV mẫu, SSID/BSSID thật hoặc dependency
  `pandas`/`scikit-learn`.

### Đối chiếu tiến độ 9 giai đoạn

| Giai đoạn | Trạng thái source/automated test | Gate hoặc giới hạn còn lại |
|---|---|---|
| 1 — protocol/network | Hoàn thành; `ProtocolTest`, `NetworkIntegrationTest` PASS | Chưa có LAN nhiều máy thật trong phiên audit |
| 2 — Dashboard | Hoàn thành ở mức headless; `DashboardTest` PASS | Desktop/HiDPI/manual interaction chưa chạy |
| 3 — System Information | Hoàn thành; `SystemInfoCollectorTest` PASS với Java fallback | Windows CIM bị Access Denied; Linux runtime chưa chạy |
| 4/4.5 — telemetry/performance/stabilization | Hoàn thành; tracker, collector và chart tests PASS | Chưa soak/load dài hạn hoặc đo p95/throughput LAN |
| 5 — Alerting | Hoàn thành ở mức source/test/persistence; `AlertEngineTest` PASS | Checklist GUI 1 Server + 3 Client còn để trống |

| 6 — TLS File Transfer/RBAC | Hoàn thành ở mức TCP/TLS integration và security regression; hai runner PASS | Checklist desktop thật, certificate setup và ba Client thật chưa chạy |
| 7 — Ansible | Hoàn thành ở mức controlled `ProcessBuilder` test; PASS | Chưa syntax-check/deploy/idempotency trên managed host Linux/Windows thật |
| 8 — Wi-Fi signal/Client–AP estimate | Source, parser/model/transport/calibration tests PASS | Chưa đo accuracy thực địa; Linux adapter thật và FTM/direct ranging chưa có |
| 9 — stabilization/distance/map/fingerprinting | Clean release và toàn bộ regression PASS; Multi-AP, Radio Map, weighted KNN đã tích hợp | Multi-device desktop, room survey và ground truth vẫn NOT RUN |

### Trạng thái Wi-Fi Fingerprinting, Multi-AP, Radio Map và KNN

- **Multi-AP Scanner — hoàn thành ở mức code/test:** Client có opt-in scheduler riêng (mặc định
  10 giây, giới hạn 5–300 giây), Windows Native Wi-Fi/JNA và Linux `iw` active scan. Chỉ RSSI dBm
  thật, frequency và SHA-256 AP target được đưa vào model/protocol; SSID/BSSID thô không được gửi.
  `WIFI_SCAN` gắn authenticated stable Client ID, giới hạn 128 AP và bỏ sample out-of-order.
- **Radio Map — hoàn thành ở mức code/test:** Server/ADMIN thêm-xóa survey sample từ scan còn mới,
  yêu cầu ít nhất ba AP, scope theo Client ID và persist atomically trong
  `wifi-radio-map-v1.json`. Có rollback khi save lỗi và fallback memory-only nếu load store mặc định
  thất bại. Persistence/restart/remove đã có automated test.
- **Weighted KNN — hoàn thành ở mức thuật toán/test tổng hợp:** Java implementation dùng `K=3`,
  inverse-square weight, tối thiểu ba AP chồng lấp và ba tọa độ survey khác nhau; AP thiếu dùng floor
  `-100 dBm`. Kết quả có confidence, estimated error, TTL và nhãn position-based/not-direct. Chưa có
  Radio Map thật nên chưa thể kết luận độ chính xác.
- **UI — hoàn thành headless:** Server có tab `Wi-Fi Radio Map Survey`, bảng scan/estimate và sample;
  Client có Enable/Test multi-AP scanner. Automated EDT construction/update PASS. Workflow survey
  tương tác trên desktop và nhiều Client vật lý chưa chạy.
- Evidence runtime Windows Native Wi-Fi trước audit trong `docs/TEST_RESULTS.md` ghi nhận scan `OK`,
  6 AP thật ở 2.4/5 GHz và không lộ identifier thô. Audit hiện tại không lặp lại probe phần cứng;
  Linux mới chỉ có parser/permission fixture.

### Ước lượng khoảng cách Server–Client realtime

- Domain khoảng cách vẫn tách khỏi TCP RTT và RSSI Client–AP. Manual measurement có ưu tiên cao nhất,
  sau đó ADMIN coordinates; cả hai không phải automatic realtime ranging.
- Pipeline fingerprint mới có thể tạo **ước lượng position-based gần realtime**: scan định kỳ → weighted
  KNN → tọa độ Client → Euclidean distance tới Server position trong cùng reference frame. Nguồn được
  gắn rõ `WIFI_FINGERPRINT`, method `POSITION_BASED_ESTIMATION`, có estimated error và chuyển `STALE`
  khi hết TTL.
- Đây không phải đo trực tiếp Server–Client. Không có room Radio Map/Server coordinate hợp lệ thì kết
  quả vẫn `NOT_AVAILABLE`; `DIRECT_MEASUREMENT` chưa có provider. Vì chưa field-validate nên chức năng
  realtime được xem là **đã nối end-to-end về phần mềm, chưa hoàn thành về accuracy/real-device gate**.

### Build và regression thực thi trong audit

- Toolchain: Microsoft OpenJDK/Javac `17.0.20.1`; `ant` không có trong `PATH`, dùng Apache Ant
  `1.10.14` đi kèm NetBeans 22 tại đường dẫn đã ghi trong README.
- Lệnh đã chạy: `D:\NetBeans\NetBeans-22\netbeans\extide\ant\bin\ant.bat release-phase9`.
- Kết quả: **BUILD SUCCESSFUL**, clean compile **151 source files + 22 test source files**, toàn bộ
  21 runner Giai đoạn 1–9 PASS (`FakeAnsiblePlaybook` là helper test, không phải runner). Các runner mới `MultiAccessPointScannerTest`, `WifiFingerprintTest`,
  `WifiFingerprintPanelTest` đều PASS; `WirelessTransportIntegrationTest` xác nhận `WIFI_SCAN` qua
  authenticated protocol bằng fixture và lab-only plaintext override.
- Artifact mới: `dist/LANMonitoringServer.jar`, **1,128,930 bytes**, SHA-256
  `44EBAFA67004884AB269E7881F1B7C5A402B446B63AC7288CF7D94A5FCE0CE73`; manifest có đúng Main-Class
  và 7 dependency. Số liệu launcher lịch sử tại mục này đã được thay bởi package 7 script ở mục 28.

### Lỗi/rủi ro tồn đọng và công việc kế tiếp

1. **Lịch sử — đã xử lý tại mục 26–28 — release metadata/tài liệu từng không đồng bộ:** source `BuildInfo.RELEASE_ID` là
   `phase9-wifi-fingerprint-1`, nhưng README, checklist và các mục context cũ vẫn ghi
   `phase9-distance-map-1`; artifact/count/hash cũ 138/19 và 1,011,518 bytes cũng đã lỗi thời. Cần
   đồng bộ tài liệu release trước khi bàn giao, nhưng audit này chỉ cập nhật context theo yêu cầu.
2. **Chưa có validation thực địa:** cần tạo Radio Map của phòng thật, ít nhất ba tọa độ/ba AP mỗi
   Client, đo ground truth và báo sai số; chạy Windows/Linux adapter thật và ít nhất ba Client.
3. **Lịch sử — TLS negotiation coverage đã bổ sung tại mục 28:** Windows test hiện kiểm ABI `WLAN_BSS_ENTRY`;
   Linux test dùng fixture. Accuracy trên thiết bị thật vẫn là manual gate.
4. **Lịch sử — đã xử lý tại mục 26 — hiển thị stale trong survey panel:** `ServerClientDistanceService` trả `STALE` đúng sau TTL,
   nhưng `WifiFingerprintPanel` hiện hiển thị tọa độ/confidence nếu estimate còn object mà không kiểm
   `isStale(now)`. Sau khi Client dừng scan/disconnect, tab survey có thể tiếp tục trông như estimate
   còn hiện hành cho tới scan hoặc recalculation tiếp theo. Chưa có test cho tình huống này.
5. **Chất lượng survey:** mỗi lần Add Survey Sample lưu một snapshot tức thời; chưa có averaging,
   outlier rejection hoặc cross-validation tích hợp. Người kiểm thử phải lấy nhiều sample ở mỗi điểm
   và đánh giá sai số trước khi tuyên bố accuracy.
6. Hoàn tất các manual gate đang để trống trong `PHASE5_GUI_TEST_CHECKLIST.md`,
   `PHASE6_GUI_TEST_CHECKLIST.md`, `PHASE7_TEST_CHECKLIST.md`, `PHASE8_WIFI_TEST_CHECKLIST.md`,
   `PHASE9_MULTI_DEVICE_CHECKLIST.md` và `SERVER_CLIENT_DISTANCE_TEST_CHECKLIST.md`.

Không phát hiện lỗi compile hoặc regression tự động trong baseline này. Không được diễn giải kết quả
fixture/loopback/headless là PASS cho GUI desktop, native Wi-Fi/Linux, Ansible real-host, độ chính xác
fingerprinting hay direct Server–Client ranging.

## 26. Fingerprinting stale-state hotfix (2026-10-28)

### Lỗi đã sửa

- `WifiFingerprintPanel` trước đây dùng `estimate.position() != null` để hiển thị tọa độ/confidence,
  nên estimate đã hết TTL vẫn trông như dữ liệu realtime. Panel nay tính status theo thời điểm render,
  có cột riêng `Scan Status`/`Position Status` và chỉ estimate `OK` còn hạn mới hiện như current.
  Tọa độ stale còn được giữ với nhãn `HISTORICAL`, không bị xóa khỏi dữ liệu chẩn đoán.
- `ServerClientDistanceService.updateEstimatedPosition` trước đây xóa mọi estimate không có tọa độ,
  làm `STALE`, `UNSUPPORTED` và `NOT_AVAILABLE` cùng rơi về generic unavailable. Service nay giữ
  status cuối và ánh xạ riêng sang `ServerClientDistanceStatus`; chỉ `OK` còn TTL mới tạo metres.
- Khi Client disconnect, heartbeat timeout hoặc Server stop, estimate tự động nguồn
  `WIFI_FINGERPRINT` chuyển ngay sang `STALE`, giữ last coordinate/error nhưng xóa realtime distance.
  Reconnect cùng stable Client ID phải gửi scan mới hơn mới trở lại `OK`.
- Distance table, Client Card và detail panel hiển thị đúng `STALE/UNSUPPORTED/NOT_AVAILABLE` thay vì
  ghi chung `NOT_AVAILABLE`. `RoomMapPanel` vốn chỉ vẽ status `OK` và tiếp tục không vẽ stale point.
- Manual measurement và ADMIN coordinates vẫn có ưu tiên cao hơn fingerprint và không bị đổi status
  khi Client Offline. Radio Map persistence/sample và lịch sử tọa độ stale không bị xóa.

### Regression bổ sung

- `WifiFingerprintTest`: TTL expiry không tạo metres, giữ historical coordinate, phân biệt
  `UNSUPPORTED`, và xác nhận stale fingerprint không ghi đè ADMIN/manual.
- `WifiFingerprintPanelTest`: headless EDT kiểm đủ `OK`, `STALE`, `UNSUPPORTED`, `NOT_AVAILABLE` và
  nhãn `HISTORICAL`.
- `DistanceManagementPanelTest`: stale row/Card/detail không hiển thị realtime metres và stale client
  không được vẽ trên Room Map.
- `WirelessTransportIntegrationTest`: `WIFI_SCAN` → Radio Map/KNN → distance `OK`, disconnect →
  `STALE` không metres, reconnect cùng Client ID → scan mới → `OK`, không tạo Client trùng.

### Release metadata

- Marker lịch sử của hotfix negotiation: `phase9-wifi-fingerprint-3`; baseline TLS launcher hiện hành nằm ở mục 28.
- README, documentation index, feature/protocol/architecture/design/limitations, Windows diagnostics,
  checklist và test results đã được đồng bộ với semantics stale mới.
- Final clean build `release-phase9`: **BUILD SUCCESSFUL**, 151 source + 22 test source,
  toàn bộ 21 runner PASS. `dist/LANMonitoringServer.jar` có kích thước **1,134,496 bytes**,
  SHA-256 `6D68E2C91FECCF6CFF79A3CDADA6E54B43EEA4E30DBECE707A22D5D870436215`;
  JAR tại thời điểm hotfix xác nhận marker `phase9-wifi-fingerprint-3`; artifact hiện hành nằm ở mục 28.

## 27. Multi-AP negotiation diagnostics hotfix (2026-10-28)

- Xác định trường hợp probe local `OK` nhưng Server `APs=0`: probe không đi qua scheduler/transport;
  policy mặc định không quảng bá `WIFI_FINGERPRINT_V1` trên plain TCP.
- Không hạ policy TLS. `AUTH_OK` nay trả thêm lý do capability fingerprint được quảng bá hoặc bị giữ lại;
  Client ghi transport, capability, opt-in và lý do scheduler blocked. Server ghi rõ capability decision,
  `WIFI_SCAN` nhận thành công và từ chối do TLS/timestamp.
- Message wire đúng là `WIFI_SCAN`, không phải `WIRELESS_SCAN`.
- `WirelessTransportIntegrationTest` khóa log gửi/nhận privacy-safe và lý do plain-TCP lab override.
- Package smoke dùng application classes từ JAR cho `WifiFingerprintTest`,
  `WifiFingerprintPanelTest`, `DistanceManagementPanelTest` và `WirelessTransportIntegrationTest`:
  **PASS**. Entry point `--server --client` trả đúng exit code 2.

### Gate chưa kiểm chứng

- Chưa chạy interactive desktop/HiDPI với ba Client vật lý.
- Chưa field-validate Radio Map/KNN bằng ground truth để báo MAE/median/p95/dropout.
- Chưa chạy Linux active scan trên adapter thật hoặc Ansible trên managed host Windows/Linux thật.
- Windows native regression vẫn kiểm ABI/fixture; runtime collection 6 AP là evidence phiên trước,
  không được coi là accuracy validation. Wi-Fi FTM/direct-ranging provider vẫn chưa có.

## 28. TLS deployment fix cho Wi-Fi Fingerprinting (2026-10-28)

### Nguyên nhân runtime đã xác nhận

- Workspace/runtime không có `server-keystore.p12`, `client-truststore.p12` hay certificate công khai.
- `LANMONITORING_TLS_KEY_STORE`, `LANMONITORING_TLS_TRUST_STORE`, hai biến password và
  `LANMONITORING_JAVA_OPTS` đều không được đặt. Launcher `.bat` compatibility vì vậy tạo socket TCP thường.
- `TlsSupport` hoạt động đúng: khi không có material TLS, Server dùng plain TCP; `Session.secure()` trả false và
  `fingerprintCapabilityAllowed(false)` không quảng bá `WIFI_FINGERPRINT_V1` theo policy fail-closed mặc định.
- Probe 19 AP chỉ chạy collector local, không chứng minh scheduler/transport. Vì capability không có, scheduler
  không gọi scanner định kỳ và Server tracker/GUI không thể nhận `WIFI_SCAN`, nên `APs=0/NOT_AVAILABLE`.

### Sửa cấu hình và packaging

- Thêm `setup-tls-windows.ps1`: nhận DNS/IP SAN rõ ràng, tạo EC P-256 PKCS12 bằng JDK `keytool`, xuất public
  certificate/truststore, không ghi password vào command line/file và giới hạn ACL của Server private keystore.
- Thêm `run-server-tls.ps1`/`run-client-tls.ps1`: hỏi password ẩn, kiểm tra file trước khi chạy và chỉ truyền
  secret bằng environment của process Java. Client launcher không nhận/cần Server private key.
- `build.xml` đóng gói đủ 7 launcher/setup script. Launcher TCP cũ được giữ để tương thích các chức năng cũ;
  Wi-Fi Fingerprinting phải dùng TLS launcher hoặc cấu hình TLS tương đương.
- `.gitignore` loại trừ PKCS12/PFX/JKS và `config/tls`; tài liệu Windows ghi rõ SAN phải khớp đúng hostname/IP
  được nhập tại Client và chỉ phân phối `client-truststore.p12`.

### Xác minh

- TLS setup script smoke PASS với certificate tạm `DNS=localhost, IP=127.0.0.1`; certificate, truststore và ACL
  được tạo/xác minh rồi xóa. Không tạo certificate triển khai thật vì chưa có DNS/IP Server do người vận hành chọn.
- `FileTransferIntegrationTest` dùng TLS/certificate thật và nay kiểm thêm Server quảng bá
  `WIFI_FINGERPRINT_V1` cùng Client negotiation `WIFI_FINGERPRINT_V1=true`. Negative tests certificate lạ và
  SAN sai vẫn PASS; không có trust-all hay plaintext override trong đường TLS này.
- `release-phase9` và target `test`: BUILD SUCCESSFUL; 151 source, 22 test source, toàn bộ 21 runner PASS.
- Artifact lịch sử của mục 28: marker `phase9-wifi-fingerprint-4`, JAR 1,136,686 bytes, SHA-256
  `AE483A5F24846A7D7E8636CD534EFB57068C81F4E7046CFAE39D3C5B4B520123`; manifest đúng Main-Class,
  đủ 7 dependency, 7 script và 21 tài liệu.

### Lệnh Windows lịch sử (đã được thay bởi stable identity tại mục 29)

```powershell
# Server: thay bằng DNS/IP mà Client thực sự dùng để kết nối
.\dist\scripts\setup-tls-windows.ps1 -ServerDnsName SERVER-PC -ServerIpAddress 192.168.1.10
.\dist\scripts\run-server-tls.ps1

# Chép chỉ client-truststore.p12 sang cùng thư mục %LOCALAPPDATA%\LANMonitoringServer\tls trên Client
.\dist\scripts\run-client-tls.ps1
```

Client phải kết nối bằng đúng `SERVER-PC` hoặc `192.168.1.10` trong SAN. Log phải có `transport=TLS`,
`WIFI_FINGERPRINT_V1=true`, scheduler started, `WIFI_SCAN sent`; Server phải log capability advertised và
`WIFI_SCAN authenticated`. `Scan Status=OK/APs>=3` chỉ xác nhận scan đã tới tracker; position cần thêm ít nhất
ba survey coordinate hợp lệ trong cùng reference frame.

## 29. Stable TLS Server identity tách khỏi IPv4 động (2026-10-28)

### Thiết kế

- IPv4 nhập trong GUI Client chỉ còn là TCP endpoint. Khi cấu hình `lanmonitoring.tls.serverIdentity`, Client
  mở raw socket tới IPv4/port đó rồi dùng `SSLSocketFactory.createSocket(raw, stableIdentity, port, true)`.
- Layered TLS socket dùng stable DNS identity làm peer host và SNI; `HTTPS` endpoint identification tiếp tục
  xác minh certificate SAN. Identity sai, certificate lạ hoặc chain không tin cậy đều fail closed.
- Identity mặc định của Windows TLS scripts là `lanmonitoring-server.local`. Tên này không cần DNS resolve vì
  TCP đã kết nối tới IPv4 do người dùng nhập. Certificate mặc định chỉ có DNS SAN này, không có IP SAN.
- Nếu không cấu hình stable identity, behavior cũ vẫn được giữ: TLS xác minh hostname/IP được nhập trực tiếp.
  Plain TCP compatibility cũng giữ nguyên, nhưng fingerprint capability vẫn yêu cầu TLS theo policy hiện hành.

### UI và vận hành

- Server GUI không còn phụ thuộc duy nhất vào `InetAddress.getLocalHost()`. Nó quét IPv4 active mỗi 3 giây,
  ưu tiên private IPv4 trên adapter không virtual, cập nhật ô Server IP/tooltip và refresh ngay trước Start.
- Workflow mới: tạo certificate/truststore một lần bằng `setup-tls-windows.ps1`; mỗi lần đổi LAN/Wi-Fi chỉ xem
  IPv4 mới trên Server, nhập IPv4/port đó tại Client và dùng `run-client-tls.ps1`. Không phát hành lại material.
- Có thể chọn identity khác bằng cách truyền cùng `-ServerIdentity` cho setup và Client launcher.

### Regression và artifact

- `FileTransferIntegrationTest` tạo certificate chỉ có SAN `lanmonitoring-server.test`, kết nối TCP tới
  `127.0.0.1`, xác nhận TLS identity log, `WIFI_FINGERPRINT_V1=true`, disconnect/reconnect và các chức năng
  file/telemetry cũ. Negative case với `wrong-server-identity.test` bị handshake từ chối trước authentication.
- Setup-script smoke xác nhận certificate mặc định có DNS SAN ổn định và không chứa IP SAN.
- `release-phase9` và target `test`: BUILD SUCCESSFUL; toàn bộ 21 runner PASS. Package smoke từ JAR cho
  TLS stable identity và wireless transport PASS; 7 dependency JAR hợp lệ.
- Marker lịch sử của mục 29 `phase9-wifi-fingerprint-5`; JAR **1,143,599 bytes**, SHA-256
  `F9DE9C5B93965DAEFC50B9EEE47D78A959DAE3034A5FEA615055684CF0787264`; package có 7 script.

## 30. Wi-Fi Radio Map Survey row-selection fix (2026-10-28)

### Nguyên nhân và sửa lỗi

- `WifiFingerprintPanel.setData()` rebuild bảng Client bằng `clientsModel.setRowCount(0)` ở mỗi realtime publish.
  Swing xóa `ListSelectionModel` trong thao tác này, nên click/keyboard selection vừa xuất hiện đã biến mất ở
  refresh kế tiếp; nhìn ngoài giống JTable không nhận input.
- Panel nay lưu selection bằng stable `clientId` trước refresh, rebuild model, tìm model row mới, đổi sang view
  row sau sorter rồi khôi phục selection/scroll. Reorder, sort và ONLINE→OFFLINE không làm mất lựa chọn; nếu
  Client không còn trong dataset thì selection được clear và Add bị disable.
- Cấu hình JTable được chốt rõ: `SINGLE_SELECTION`, row selection bật, cell/column selection tắt. Thứ tự gọi
  Swing API tránh `setCellSelectionEnabled(false)` vô tình tắt lại row selection.
- Dòng chọn dùng nền `DashboardColors.PRIMARY` và chữ trắng. `Add Survey Sample` chỉ bật khi có Client được
  chọn và callback lấy stable Client ID thay vì phụ thuộc view row/index sau sort hoặc refresh.
- Không thay đổi KNN, TLS, protocol, tracker hay dữ liệu/persistence Radio Map.

### Regression và artifact

- `WifiFingerprintPanelTest` kiểm: cấu hình selection, JTable selection, keyboard next-row, highlight, Add callback
  đúng Client ID, refresh/reorder/disconnect giữ selection, sort mapping đúng và Client biến mất thì clear/disable.
- `release-phase9` và target `test`: BUILD SUCCESSFUL; toàn bộ 21 runner PASS. Package smoke chạy test panel
  bằng application classes trong JAR: PASS.
- Marker hiện hành `phase9-wifi-fingerprint-6`; JAR **1,146,519 bytes**, SHA-256
  `BBD864D1CADF05F4117600EBCDA965C79FA65A5E557A5A83F0A0559358DC38AA`; 7 dependency giữ nguyên.

## 31. RAM alert và thông báo mẫu ADMIN (2026-10-29)

- `AlertEngine` theo dõi RAM theo stable Client ID: `>90%` liên tục 60 giây, reminder 60 giây, recovery `<=90%` liên tục 60 giây; reconnect không tạo state mới.
- Automatic RAM alert dùng `ALERT/ALERT_ACK`, được đánh dấu bắt buộc và độc lập với quyền `Admin alerts`. Client chỉ chấp nhận bypass này cho metric RAM; popup mới thay popup cũ để không chồng cửa sổ.
- `Alerts → Send Notification` có 7 mẫu cố định, multi-select/all ONLINE, không có free-form command. CPU/RAM/GPU/Disk lấy `MetricReading` mới nhất; metric không khả dụng được ghi rõ và không sinh số giả.
- Thông báo thủ công chỉ ADMIN được gửi, tuân theo `Admin alerts`, persist cùng alert history, có delivery/ACK theo Client và chặn thao tác lặp cùng template/Client trong 5 giây.
- Regression mới: `AdminNotificationIntegrationTest`, `AdminNotificationPanelTest`; `AlertEngineTest` mở rộng cho duration/reminder/recovery/Client isolation và persistence nội dung mẫu.
- `release-phase9` và `ant test`: **BUILD SUCCESSFUL**; clean compile 153 source + 25 test source, toàn bộ 24 runner PASS.
- Artifact: `dist/LANMonitoringServer.jar`, **1,203,428 bytes**, SHA-256 `52E75D963B8AE0214E20AF2EF0FC17F112657545FF502B9494AC6139AB4E39EC`.

