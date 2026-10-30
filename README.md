# LANMonitoringServer – Giai đoạn 1-9

Đồ án Java Swing giám sát nhiều máy tính trong mạng LAN qua TCP/IP. Giai đoạn 1–5 cung cấp protocol, Dashboard, System Information, telemetry và Resource Alerting. Giai đoạn 6 bổ sung truyền file Server → nhiều Client qua TLS, RBAC, quyền theo Client và audit log. Giai đoạn 7 bổ sung điều phối Ansible có kiểm soát để đồng bộ file văn bản trên allowlist Windows/Linux. Giai đoạn 8 bổ sung nghiên cứu tín hiệu Wi-Fi và ước lượng khoảng cách Client–AP có hiệu chuẩn. Giai đoạn 9 hoàn thiện code review, bounded resources, RBAC alert, release packaging và bộ tài liệu bàn giao.

## Môi trường và phụ thuộc

- Java 17 LTS trở lên. Project trước đây khai báo Java 23 nhưng không sử dụng tính năng riêng của Java 23; cấu hình được chuyển về Java 17 để dùng bản LTS và phù hợp môi trường kiểm thử.
- NetBeans Java Application và Apache Ant (`build.xml`, `nbproject/`).
- OSHI 7.7.2 thu thập CPU, RAM, Disk và Network đa nền tảng; JNA 5.19.1 là runtime dependency của OSHI.
- JFreeChart 1.5.6 hiển thị biểu đồ Swing; SLF4J 2.0.20 dùng NOP binding để không phát cảnh báo logger. Các JAR nằm trong `lib/` và được NetBeans/Ant đóng gói vào `dist/lib`.
- JSON codec và test runner tự chứa trong source, không cần JUnit.
- Main class: `lanmonitoringserver.LANMonitoringServer`.

## Kiến trúc trước và sau

Trước Giai đoạn 1, `frmServer` và `frmClient` đồng thời quản lý Swing, socket, thread, chuỗi protocol và model. Kết nối client được mở ngay trên Event Dispatch Thread; nhiều luồng có thể ghi chung `PrintWriter`; protocol phân tách bằng ký tự `|`; `LANMonitoringServer.main` không khởi chạy giao diện.

Sau Giai đoạn 1, các lớp được chia theo tầng:

```text
lanmonitoringserver/
├── frmServer, frmClient              # View/controller Swing, giữ nguyên .form
├── model/                           # ClientSnapshot, trạng thái, system stats
├── protocol/                        # ProtocolMessage, JSON, type, validation
├── network/                         # Length-prefixed framed transport
├── service/                         # Server/client lifecycle và nghiệp vụ
├── monitor/                         # Realtime monitor + Windows/Linux static inventory
├── security/                        # Identity, credential registry, HMAC challenge
├── ui/                              # Dashboard, ClientCard, ClientDetailPanel
└── util/                            # Validation và thread factory
```

Swing chỉ cập nhật component trên EDT. Accept, connect, read, heartbeat, PING/PONG và reconnect chạy trên executor có tên. Việc ghi frame được khóa theo connection để các thông điệp không xen byte.

## Dashboard Giai đoạn 2

- Mỗi `clientId` ổn định được ánh xạ tới duy nhất một `ClientCard`; heartbeat chỉ cập nhật Card hiện có, không dựng lại Dashboard.
- Card tự wrap trong `JScrollPane`, hỗ trợ tìm theo ID/hostname/IP/OS, lọc trạng thái và chọn nhiều client.
- Update từ network được gộp trong cửa sổ 250 ms trước khi chạy trên Swing EDT, giảm giật lag khi có nhiều heartbeat.
- Màu trạng thái: Online xanh, Offline xám, Warning cam, Critical đỏ. Ngưỡng Warning là tài nguyên từ 75% hoặc RTT từ 500 ms; Critical là tài nguyên từ 90% hoặc RTT từ 1000 ms.
- Card hiển thị ID, hostname, IP, OS, CPU, RAM, GPU/Disk nếu có, RTT và Last Seen. CPU/RAM/Disk là dữ liệu thật từ JDK/OS; GPU hiển thị `Không có dữ liệu` khi chưa có bộ thu thập.
- Nhấn Card mở `ClientDetailPanel` với 6 tab. Nút **File Transfer** trên Dashboard mở giao diện truyền file tập trung; tab Alerts dẫn tới cửa sổ quản lý cảnh báo. Wi-Fi distance vẫn chưa triển khai.
- Client Offline vẫn được giữ trong lịch sử. Reconnect cùng ID cập nhật Card cũ thay vì tạo Card trùng.

## System Information Collector – Giai đoạn 3

`SystemInfoCollectorFactory` tự chọn `WindowsSystemInfoCollector`, `LinuxSystemInfoCollector` hoặc Java fallback theo `os.name`. Dữ liệu tĩnh được tách khỏi CPU/RAM/Disk Usage realtime, cache trong tiến trình Client và chỉ thu thập lại khi người dùng bấm Refresh.

Windows collector:

- Dùng Java API cho hostname, memory, filesystem và network interfaces.
- Dùng PowerShell + CIM (`Win32_ComputerSystem`, `Win32_OperatingSystem`, `Win32_BIOS`, `Win32_Processor`, `Win32_VideoController`, `Win32_LogicalDisk`) cho manufacturer/model/BIOS/CPU/GPU/storage.
- PowerShell chạy không profile, non-interactive, UTF-8, timeout 12 giây và giới hạn output 2 MiB. Không yêu cầu Administrator.
- Không xuất nguyên báo cáo dxdiag; kết quả được parse thành Java model có cấu trúc.

Linux collector:

- Dùng Java API, `/etc/os-release`, `/proc/cpuinfo`, `/sys/class/dmi/id`, cpufreq sysfs.
- Dùng `lsblk` và `lspci` nếu có; thiếu lệnh được ghi thành lý do unavailable.
- Không cài package, không gọi `sudo`, không gọi `dmidecode` khi không có quyền và không giả lập DXDIAG.

Dự án không thêm OSHI trong Giai đoạn này để giữ Ant project không có dependency ngoài; collector ưu tiên Java API và chỉ dùng OS tool cho trường Java không cung cấp. Kiến trúc interface cho phép thêm OSHI adapter sau này mà không đổi giao thức/UI.

`SYSTEM_INFO` chứa model System, CPU, GPU, Memory, Storage, Network và bản đồ lý do các trường không khả dụng. Server lưu snapshot theo Client ID. `SYSTEM_INFO_REQUEST` kích hoạt Refresh. Server quảng bá capability `SYSTEM_INFO_V1` trong `AUTH_OK`; client chỉ gửi inventory khi server hỗ trợ, nhờ đó client mới vẫn giữ được các chức năng Giai đoạn 1-2 khi nối tới server cũ. Tab System Information hiển thị theo nhóm và hỗ trợ Export JSON/TXT UTF-8.

Tình trạng kiểm chứng:

- Windows: parser CIM và Java fallback đã PASS; môi trường kiểm tra hiện tại chặn `Get-CimInstance` bằng policy nên chưa xác minh inventory CIM đầy đủ trong phiên này.
- Linux: parser `/proc`, os-release, lsblk, lspci và fallback đã có unit test; chưa có máy Linux trong phiên này để chạy integration collector, do đó không tuyên bố hỗ trợ Linux đầy đủ.

## Performance Monitoring – Giai đoạn 4

- `MetricsCollector` được triển khai bằng `OshiMetricsCollector`. Client lấy mẫu mặc định mỗi 1 giây trên scheduler riêng, không chạy trên Swing EDT. Có thể cấu hình từ 500–10000 ms bằng JVM property `-Dlanmonitoring.telemetry.interval.ms=1000`.
- CPU gồm usage toàn hệ thống, lõi vật lý, luồng logic và tần số hiện tại nếu OS cung cấp. RAM gồm used/free/total và usage. Disk throughput lấy từ chênh lệch bộ đếm byte; Disk Active Time lấy từ chênh lệch transfer time; phần trăm dung lượng là trường riêng, không thay thế Active Time. Network lấy chênh lệch byte trên interface đang `UP` có lưu lượng lớn nhất.
- GPU dùng provider `nvidia-smi` có timeout 2 giây khi công cụ khả dụng. Utilization, VRAM và nhiệt độ là ba trường độc lập. Máy/hãng không hỗ trợ trả `UNSUPPORTED`, không dùng VRAM làm GPU utilization và không phát sinh số ngẫu nhiên.
- Sau xác thực, Server quảng bá capability `TELEMETRY_V1`. Gói `TELEMETRY` có timestamp, interval và status theo nhóm. Server kiểm tra kiểu/range, từ chối timestamp quá xa trong tương lai và lưu tối đa 120 mẫu cho từng Client ID.
- `PerformancePanel` dùng JFreeChart với các màn hình CPU, Memory, GPU, Disk và Network, cửa sổ thời gian 60 giây. Chỉ dataset đang hiển thị được cập nhật, tối đa 2 lần/giây. Nút Pause chỉ dừng render; nhận telemetry, ring buffer và heartbeat vẫn hoạt động.
- Scheduler telemetry được hủy khi disconnect và tạo đúng một instance mới sau khi phiên được xác thực lại. Heartbeat dùng executor khác nên việc gọi `nvidia-smi` không chặn Online/Offline hoặc PING/PONG.

Kiểm chứng nền tảng: Windows đã chạy collector thực tế với tải CPU và ghi file thật; GPU NVIDIA trên máy kiểm thử trả status `OK`. Linux dùng OSHI nhưng chưa có môi trường Linux trong phiên này để chạy integration test, vì vậy chưa tuyên bố đã kiểm chứng runtime Linux cho Giai đoạn 4.

## Baseline Stabilization – Giai đoạn 4.5

- Dashboard metric có `status`, `timestamp` và `source` (`TELEMETRY`, `HEARTBEAT`, `LEGACY`). Telemetry còn hạn luôn được ưu tiên; heartbeat chỉ là fallback cho metric mà nó thực sự cung cấp.
- Heartbeat không còn xóa GPU telemetry. Khi GPU hoặc Disk Active telemetry quá hạn và không có nguồn thay thế hợp lệ, Card hiển thị `STALE` thay vì đổi sai thành giá trị khác.
- Disk Active Time, Disk Capacity Usage và Disk Read/Write throughput được giữ thành các trường độc lập. Card hiển thị riêng Disk Active và Disk Capacity; capacity không thay thế active time.
- `AlertThresholds.DEFAULT` chuẩn bị cấu hình độc lập CPU/GPU/Disk Active ở mức `>80%`. Đây chưa phải Alert Engine và không gửi cảnh báo.
- Test Windows CIM chấp nhận fallback có lý do khi môi trường chặn quyền WMI/CIM; parser fixture và các kiểm tra dữ liệu đầy đủ vẫn được giữ.

## Resource Alerting – Giai đoạn 5

- `AlertEngine` đánh giá độc lập CPU Utilization, GPU Utilization, Disk Active Time và Disk Capacity Usage. Metric `UNSUPPORTED`, `ERROR`, `STALE` không tạo alert.
- Rule mặc định: WARNING `>80%`, CRITICAL `>90%`, duy trì 5 giây, hysteresis 5%, cooldown 60 giây. Quản trị viên có thể thay đổi/tắt từng rule từ cửa sổ **Alerts** trên Dashboard.
- Alert có lifecycle `ACTIVE → ACKNOWLEDGED → RESOLVED`. Acknowledge của quản trị viên không đóng alert; Resolve đóng thủ công. Recovery dưới `warning - hysteresis` tự Resolve.
- Server gửi `ALERT` đúng Client ID; Client giới hạn popup theo alert/severity và trả `ALERT_ACK`. Server lưu riêng delivery state `PENDING/SENT/ACKNOWLEDGED/FAILED`.
- Popup alert phía Client là non-modal và tự đóng sau khoảng 15 giây; telemetry/network không chờ người dùng đóng popup.
- Server hiển thị Current Alerts, Alert History, lọc theo Client/resource/severity/time và đổi badge/màu Card khi có alert.
- Rule và lịch sử được lưu bằng JSON UTF-8 với atomic replace tại `%USERPROFILE%/.lanmonitoringserver/alerts-v1.json` (hoặc JVM property `-Dlanmonitoring.alert.store=<path>`). Ghi file chạy trên executor riêng.
- Nếu store hỏng hoặc không ghi được, Alert Engine vẫn chạy trong bộ nhớ nhưng persistence bị vô hiệu cho phiên Server hiện tại và ghi diagnostic vào log. Store lỗi khi load không bị ghi đè bằng trạng thái rỗng.

### RAM tự động và Send Notification

- RAM `>90%` liên tục 60 giây tạo popup tự động; nếu vẫn cao, Server nhắc lại sau mỗi 60 giây. RAM `<=90%` liên tục 60 giây đóng alert và reset chu kỳ. State gắn với Client ID nên độc lập giữa các máy và không nhân đôi khi reconnect.
- Cảnh báo RAM tự động là cảnh báo an toàn bắt buộc, không phụ thuộc quyền `Admin alerts`. Popup mới thay popup cảnh báo đang mở để không xếp chồng cửa sổ.
- Trong **Alerts → Send Notification**, ADMIN chọn một trong 7 mẫu cố định, chọn một/nhiều/tất cả Client ONLINE và nhấn **Send**. Không có ô nhập hoặc thực thi lệnh.
- Mẫu CPU/RAM/GPU/Disk dùng metric mới nhất còn hợp lệ của từng Client. Nếu không có metric, nội dung ghi rõ `không khả dụng` và không tạo số phần trăm giả.
- Thông báo thủ công tuân theo quyền `Admin alerts`, RBAC ADMIN, chống nhấn lặp trong 5 giây và hiển thị delivery/ACK theo từng Client.

## File Transfer và RBAC – Giai đoạn 6

- Server chọn file và một/nhiều/toàn bộ Client từ cửa sổ **File Transfer**. Mỗi Client có transfer ID, progress, số byte, tốc độ, status và kết quả riêng.
- File được đọc theo chunk 64 KiB và mã hóa Base64 trong frame JSON binary-safe; không đọc toàn bộ file vào RAM. Frame vẫn dùng write lock nên heartbeat/telemetry có thể xen giữa các chunk.
- Server tính SHA-256 theo stream trước khi gửi. Client ghi vào file `.part`, tính digest trong lúc nhận và chỉ atomic-move sang tên đích khi đủ byte và checksum đúng.
- Mặc định giới hạn 100 MiB, cấu hình bằng `-Dlanmonitoring.file.max.bytes=N`. Timeout nhận offer 15 giây và timeout checksum result 30 giây; có thể cấu hình lần lượt bằng `-Dlanmonitoring.file.offer.timeout.ms=N` và `-Dlanmonitoring.file.result.timeout.ms=N`. Có Cancel và cleanup khi mất kết nối; chưa hỗ trợ resume giữa phiên vì chưa có thiết kế xác thực offset an toàn.
- Thư mục nhận mặc định: `%USERPROFILE%/LANMonitoringServer/received-files`, cấu hình bằng `-Dlanmonitoring.receive.dir=<path>`. Tên tuyệt đối/path traversal/ký tự Windows không hợp lệ bị chặn. File trùng tên được đổi thành `name (N).ext`; không ghi đè và không tự thực thi.
- RBAC Server: `ADMIN` quản lý quyền + gửi file; `OPERATOR` chỉ gửi tới Client trong `-Dlanmonitoring.operator.clients=id1,id2`; `VIEWER` chỉ xem; `CLIENT` không có quyền quản trị. Role lấy từ `-Dlanmonitoring.server.role=...`, principal audit lấy từ tài khoản OS chạy Server.
- Quyền Client do Server sở hữu: nhận file, nhận thông báo quản trị, xuất System Information. Mặc định quyền nhận file là `false` (fail closed). Store nằm tại `~/.lanmonitoringserver/client-permissions-v1.json`.
- Audit JSON Lines mặc định tại `~/.lanmonitoringserver/audit.jsonl`; ghi principal, role, action, target, outcome và timestamp.
- Truyền file và cập nhật permission chỉ hoạt động trên TLS. Server tiếp tục cho phép plaintext đối với chức năng monitoring cũ để tương thích, nhưng tuyệt đối từ chối file transfer trên session plaintext.

### Cấu hình TLS cho demo LAN

Tạo certificate Server và truststore trên máy quản trị; phân phối **chỉ truststore/certificate công khai** cho Client, không sao chép private key của Server:

Trên Windows, dùng danh tính TLS cố định, độc lập với IPv4 hiện tại:

```powershell
.\dist\scripts\setup-tls-windows.ps1
.\dist\scripts\run-server-tls.ps1
# Trên mỗi Client, sau khi chép duy nhất client-truststore.p12 vào
# %LOCALAPPDATA%\LANMonitoringServer\tls:
.\dist\scripts\run-client-tls.ps1
```

Các script hỏi mật khẩu ẩn, không ghi mật khẩu vào repo/command line, và giữ `server-keystore.p12`
chỉ trên Server. Certificate mặc định xác thực identity ổn định `lanmonitoring-server.local`; Client vẫn nhập
IPv4 hiện tại trong GUI. TCP kết nối tới IPv4 đó nhưng TLS/SNI/hostname verification kiểm tra identity cố định,
vì vậy đổi LAN/Wi-Fi hoặc DHCP không cần tạo lại certificate/truststore.

Lệnh `keytool` thủ công tương đương:

```powershell
keytool -genkeypair -alias lan-server -keyalg RSA -keysize 3072 -storetype PKCS12 `
  -keystore server.p12 -storepass CHANGE_ME -keypass CHANGE_ME `
  -dname "CN=lanmonitoring-server.local" -validity 365 `
  -ext "SAN=dns:lanmonitoring-server.local"
keytool -exportcert -alias lan-server -keystore server.p12 -storepass CHANGE_ME `
  -file lan-server.cer
keytool -importcert -alias lan-server -storetype PKCS12 -keystore clients-trust.p12 `
  -storepass CHANGE_ME -file lan-server.cer -noprompt
```

Server:

```powershell
$env:LANMONITORING_TLS_KEY_STORE_PASSWORD = "CHANGE_ME"
$env:LANMONITORING_TLS_TRUST_STORE_PASSWORD = "CHANGE_ME"
java "-Dlanmonitoring.tls.keyStore=server.p12" `
  "-Dlanmonitoring.tls.trustStore=clients-trust.p12" `
  "-Dlanmonitoring.server.role=ADMIN" -jar dist/LANMonitoringServer.jar --server
```

Client:

```powershell
$env:LANMONITORING_TLS_TRUST_STORE_PASSWORD = "CHANGE_ME"
java "-Dlanmonitoring.tls.trustStore=clients-trust.p12" `
  "-Dlanmonitoring.tls.serverIdentity=lanmonitoring-server.local" `
  -jar dist/LANMonitoringServer.jar --client
```

TLS dùng `TLSv1.3`, Client xác minh chain và hostname/SAN; không có trust-all. Client tiếp tục được xác thực với Server bằng challenge HMAC hiện tại, vì vậy TLS xác thực Server còn HMAC ràng buộc Client ID/secret.

TLS là khả năng được hỗ trợ nhưng không tự bật nếu thiếu cấu hình PKCS12/truststore. Chế độ mặc định vẫn cho monitoring qua TCP thuần để tương thích; mọi File Transfer và thay đổi permission đối với Client đang online đều fail-closed nếu session không phải TLS. Password phải đi qua environment/JVM property, không nằm trong source hoặc audit log.

## Ansible synchronization – Giai đoạn 7

- Tab **Ansible Management** cho nhập nội dung UTF-8, Generate File, chọn nhiều managed host, Deploy/Sync, Verify SHA-256 và Cancel.
- Inventory được sinh theo từng job từ allowlist JSON riêng; trạng thái Java Client Online không tự cấp quyền Ansible. Entry `enabled=false` bị service layer từ chối.
- Linux dùng `ansible.builtin.copy/stat`; Windows dùng `ansible.windows.win_copy/win_stat`. Hai playbook bundled là cố định và idempotent; Client không thể gửi playbook/command tùy ý.
- `AnsibleRunner` gọi `ansible-playbook` bằng danh sách argument của `ProcessBuilder`, không qua shell; stdout/stderr được drain đồng thời, có timeout/cancel và kết quả `SUCCESS/FAILED/UNREACHABLE` theo host.
- Source, inventory và job vars nằm trong workspace riêng; source tối đa 1 MiB. Destination bắt buộc là absolute path kết thúc bằng `lan_monitoring_sync.txt`.
- Chỉ role `ADMIN` được Generate/Deploy/Verify/Cancel. Mọi thao tác có audit; OPERATOR/VIEWER/CLIENT bị từ chối tại service layer.
- Credential phải ở file Ansible Vault ngoài repository và có password file được bảo vệ. Inventory chỉ chứa tham chiếu `vault_hosts`, không chứa password/private key.
- Hỗ trợ Control Node native Linux và chế độ WSL2 (`wsl.exe -- ansible-playbook`) với chuyển đổi drive path `/mnt/<drive>`.
- Không có sync nền tự động; nội dung chỉ triển khai khi quản trị viên nhấn Deploy.

Build xác minh ngày 2026-10-27: `release-phase7` **BUILD SUCCESSFUL**, clean-compile 102 source + 12 test source, toàn bộ test Giai đoạn 1–7 PASS. JAR mới 775255 byte, SHA-256 `E51A231EA3E92BFADC11C6018E6183D8EFA6DA1369F2172115A351A38722C00F`; `dist/ansible` chỉ chứa 5 automation asset đã allowlist và không chứa vault/password/runtime file. Package-level `AnsibleIntegrationTest` PASS với application classes từ JAR. Đây vẫn là controlled-process validation, không phải deploy Windows/Linux thật.

Hướng dẫn đầy đủ: `docs/PHASE7_ANSIBLE_SETUP.md`. Checklist thiết bị thật: `docs/PHASE7_TEST_CHECKLIST.md`.

### Phase 6 Final Validation (2026-10-27)

- `ant release-phase6` đã chạy clean build bằng JDK 17.0.20.1 và Ant 1.10.14: 89 source, 10 test source và toàn bộ runner Giai đoạn 1–6 PASS.
- TLS negative tests dùng socket/handshake thật đã xác nhận truststore không tin cậy và hostname/IP không khớp SAN đều bị từ chối. Không tắt certificate/hostname validation để làm test.
- File tests đã xác nhận TXT, PNG tới 3 Client, binary, SHA-256, Cancel, offer timeout, retry bằng transfer mới, disconnect, size/disk-space limit, path traversal, symbolic-link receive root, permission denial và không auto-execute.
- RBAC được kiểm tra tại service/network layer cho ADMIN, OPERATOR, VIEWER và CLIENT; permission persistence/reload, rollback khi save lỗi và audit JSONL cũng đã được chạy.
- JAR mới: `dist/LANMonitoringServer.jar`, 668629 byte, SHA-256 `A44454CE55887070CCDC8C2750C39547708C1032B3CE41364703A713F368FF54`; manifest và 7 file trong `dist/lib` đã được kiểm tra.
- Swing headless/static EDT tests PASS, nhưng GUI desktop tương tác 1 Server + 3 Client chưa được thực hiện trong môi trường công cụ hiện tại. Xem `docs/PHASE6_GUI_TEST_CHECKLIST.md`; không coi đây là GUI PASS hay release-ready hoàn chỉnh.

## Giao thức v1

Mỗi frame gồm `4-byte unsigned-compatible big-endian length` theo sau bởi JSON UTF-8. Giới hạn frame là 1 MiB. Object JSON bắt buộc có:

```json
{
  "version": 1,
  "type": "HEARTBEAT",
  "requestId": "UUID",
  "clientId": "UUID",
  "timestamp": 1793000000000,
  "payload": {
    "cpuPercent": 12.5,
    "ramPercent": 63.2
  }
}
```

Type hiện có bổ sung `PERMISSIONS_UPDATE`, `FILE_OFFER`, `FILE_ACCEPT`, `FILE_REJECT`, `FILE_CHUNK`, `FILE_COMPLETE`, `FILE_RESULT`, `FILE_CANCEL` bên cạnh các type monitoring/alert cũ.

Luồng kết nối mới:

1. Client gửi `CONNECT`. Nếu chưa đăng ký, server trả `ERROR/NOT_REGISTERED`.
2. Client gửi `REGISTER` một lần với secret ngẫu nhiên của máy. Hai phía lưu identity trong Java Preferences; không có token hardcode trong source.
3. Client gửi lại `CONNECT` với nonce. Server trả `CHALLENGE`; client chứng minh sở hữu secret bằng HMAC-SHA256 trong `AUTH`.
4. Sau `AUTH_OK`, server mới chấp nhận heartbeat, ACK và danh tính client.

Server tự nhận diện protocol cũ `CONNECT|...` để chuyển đổi có kiểm soát. Client cũ được gán ID cô lập dạng `legacy:...@remoteAddress`; nó không thể ghi đè identity v1. Nên nâng cấp tất cả client sang v1.

Server gửi `PING` định kỳ và tính RTT khi nhận `PONG`; cột Response không còn là giá trị giả. Client gửi heartbeat mỗi 3 giây, server chuyển Offline sau 10 giây không có hoạt động. Khi mất server, client reconnect với backoff 1, 2, 4, 8, 16 và tối đa 30 giây.

Server có thể chọn nhiều dòng trong bảng Client để gửi `SERVER_DISCONNECT` và chặn chính stable
Client ID đó. Client giữ ứng dụng chạy nhưng chỉ thử xin kết nối lại mỗi 5 giây; Server trả
`CLIENT_BLOCKED` cho tới khi quản trị viên chọn **Cho phép kết nối lại**. Việc gỡ chặn chỉ đặt trạng
thái chờ; `Online` chỉ xuất hiện sau khi socket TCP mới hoàn tất challenge/HMAC và nhận `AUTH_OK`.

## Bảo mật và TLS

Nền tảng hiện tại ngăn client không có credential nhận identity của client khác. Secret được sinh bằng `SecureRandom`, không hardcode. Các service nhận `javax.net.SocketFactory`/`ServerSocketFactory` qua constructor, do đó có thể cấu hình `SSLSocketFactory` và `SSLServerSocketFactory` mà không đổi protocol/service.

Giới hạn tương thích: nếu không cấu hình TLS, monitoring cũ vẫn chạy TCP thuần và lần đăng ký đầu tiên vẫn có rủi ro nghe lén/MITM. File transfer và thay đổi permission bị khóa trên plaintext. Khi triển khai thật phải chạy toàn bộ Server/Client bằng TLS và bảo vệ keystore bằng quyền filesystem/secret management phù hợp.

## Build và chạy

### NetBeans / Apache Ant

1. Mở thư mục project trong NetBeans, chọn JDK 17+.
2. Chọn **Clean and Build**.
3. Run project không tham số sẽ mở Server.
4. Để mở Client, tạo Run Configuration có Arguments là `--client`.
5. Chạy toàn bộ test Giai đoạn 1-7: `ant test` hoặc `ant test-phase7`.
6. Tạo release candidate sạch sau khi test PASS: `ant release-phase7`.

### Dòng lệnh

```powershell
ant test
ant release-phase7
java -jar dist/LANMonitoringServer.jar --server
java -jar dist/LANMonitoringServer.jar --client
```

Nếu chưa có Ant, có thể biên dịch kiểm tra bằng JDK:

```powershell
$sources = rg --files src -g '*.java'
$tests = rg --files test -g '*.java'
javac -encoding UTF-8 -source 17 -target 17 -cp "lib/*" -d build/classes $sources
javac -encoding UTF-8 -source 17 -target 17 -cp "lib/*;build/classes" -d build/test/classes $tests
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.protocol.ProtocolTest
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.service.NetworkIntegrationTest
java -ea "-Djava.awt.headless=true" -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.ui.DashboardTest
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.monitor.system.SystemInfoCollectorTest
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.service.ClientMetricTrackerTest
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.monitor.metrics.MetricsCollectorTest
java -ea "-Djava.awt.headless=true" -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.ui.PerformancePanelTest
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.service.AlertEngineTest
java -ea -cp "lib/*;build/classes;build/test/classes" lanmonitoringserver.service.FileTransferIntegrationTest
```

## Kiểm thử Giai đoạn 1

- `ProtocolTest`: round-trip JSON Unicode/ký tự phân tách, JSON lỗi, sai version, sai type/range payload.
- `NetworkIntegrationTest`: server loopback thật + 3 client đồng thời, REGISTER/AUTH, heartbeat, PING/PONG RTT, MESSAGE/ACK, Offline, kết nối lại cùng identity, từ chối mạo danh và client legacy.
- Test không dùng dữ liệu giả để thay cho network: tất cả luồng network dùng TCP socket thật trên loopback; CPU/RAM do `LocalSystemMonitor` đọc từ hệ điều hành.

Kết quả Final Validation ngày 2026-10-27: `release-phase6` đã **BUILD SUCCESSFUL** bằng Apache Ant 1.10.14/NetBeans 22/JDK 17.0.20.1. Toàn bộ mười test runner Giai đoạn 1–6 PASS. Hai runner Phase 6 tạo certificate/truststore tạm bằng JDK `keytool`, chạy một Server + ba Client TLS thật và kiểm tra cả positive/negative path được liệt kê phía trên. `DashboardTest` kiểm tra headless hai panel file transfer. JAR SHA-256: `A44454CE55887070CCDC8C2750C39547708C1032B3CE41364703A713F368FF54`. CIM vẫn bị policy từ chối nhưng Java fallback có diagnostic PASS. Desktop visual smoke vẫn cần thực hiện trên interactive desktop.

Kiểm thử desktop thủ công: [docs/PHASE6_GUI_TEST_CHECKLIST.md](docs/PHASE6_GUI_TEST_CHECKLIST.md).

`DashboardTest` của Giai đoạn 2 chạy headless và kiểm tra thêm/xóa/Card Offline-reconnect, không tạo Card trùng, tìm kiếm/lọc, chọn nhiều client, 75 client đồng thời, 6 tab chi tiết và việc enqueue update từ luồng network không chặn EDT.

## Lỗi đã khắc phục

- Client trùng `CLIENT01` ghi đè nhau; nay mỗi máy có identity/secret riêng.
- `main` rỗng; nay có thể khởi chạy Server/Client độc lập.
- Socket connect/read và shutdown lẫn trong Swing EDT; nay tách sang service/executor.
- Message có `|`, xuống dòng hoặc dữ liệu lớn làm hỏng protocol; nay dùng JSON UTF-8 và length prefix.
- Ghi socket từ heartbeat/UI có thể xen kẽ byte; nay mỗi connection có write lock.
- Response `15 ms` giả; nay là RTT PING/PONG thật.
- Client không reconnect; nay có exponential backoff và cho phép người dùng hủy.
- Ngắt kết nối bình thường không luôn chuyển Offline; nay lifecycle đóng socket có một đường xử lý nhất quán.
- Dữ liệu protocol không giới hạn/kiểm tra; nay có max frame, max message, range CPU/RAM, type/version validation.

Hai file `frmServer.form` và `frmClient.form` được giữ nguyên để tiếp tục chỉnh sửa giao diện bằng NetBeans GUI Builder.
# Phase 8 - Wi-Fi signal research and calibrated distance estimation

Phase 8 is implemented as an opt-in research feature. On Client, open **Wi-Fi Research > Enable Wi-Fi measurement**. The default interval is 2 seconds and can be changed with `-Dlanmonitoring.wifi.interval.ms=2000` (accepted range 1000-30000 ms). SSID/BSSID are hidden by default; an explicit demo-only opt-in `-Dlanmonitoring.wifi.share.identifiers=true` shares them with the authenticated Server. A SHA-256 AP target identifier is retained so calibration can be matched without displaying the BSSID.

- Windows uses `netsh wlan show interfaces`. Newer Windows/driver combinations may expose a separate `Rssi`/`RSSI` field; that direct dBm value is used when valid. Signal/Link Quality percent remains independent and is never converted to dBm. If localized output is decoded with a non-default code page, configure `-Dlanmonitoring.wifi.netsh.charset=<charset>` (for example `UTF-8`); an invalid charset name safely falls back to the JVM default.
- Linux uses `iw dev` and `iw dev <interface> link` (nl80211) without active scanning. If the driver exposes signal dBm, Server can smooth it with EMA and estimate **Client-to-Access-Point** distance after calibration.
- Network PING/PONG RTT is displayed only as latency. It is never converted to physical distance.
- The log-distance model must be calibrated for the current AP using at least two real measurements entered as `distanceMeters,rssiDbm`. The UI always labels its result **Estimated Distance**, includes model uncertainty/confidence, and never claims actual distance.
- `WifiRttProvider` is an extension point. Merely finding an FTM-initiator capability does not mean ranging is operational; the bundled desktop provider remains `UNSUPPORTED` until a real compatible hardware/API implementation exists.

Build and run all automated checks with:

```powershell
& 'D:\NetBeans\NetBeans-22\netbeans\extide\ant\bin\ant.bat' release-phase8
```

Real-world accuracy and desktop behavior must be recorded with [the Phase 8 checklist](docs/PHASE8_WIFI_TEST_CHECKLIST.md); parser/model tests are not a substitute for measurements on real Windows/Linux devices.

## Phase 9 final release

Build the verified distribution with `ant release-phase9`. Start outside the IDE using `dist/scripts/run-server.*` and `run-client.*`. Complete documentation, diagrams, installation, demo and executed test evidence are indexed in [docs/README.md](docs/README.md).

Final artifact: `dist/LANMonitoringServer.jar`, 1,134,496 bytes, SHA-256
`6D68E2C91FECCF6CFF79A3CDADA6E54B43EEA4E30DBECE707A22D5D870436215`. The same metadata and
executed-test evidence are recorded in [TEST_RESULTS.md](docs/TEST_RESULTS.md).

### Windows Wi-Fi diagnostics

The current release prints `release=phase9-wifi-fingerprint-6` and the active code source so an old JAR or stale NetBeans output can be identified. Client and Server logs expose modern protocol negotiation, `WIRELESS_SIGNAL_V1`, scheduler state, the first privacy-safe signal send/store, and the wireless history count loaded by the Network UI. The Client menu provides a one-shot privacy-safe collector probe. Calibration is enabled only for a current RSSI/AP target and is persisted by pseudonymous AP hash; SSID/BSSID remain hidden by default. See [Windows wireless diagnostics](docs/WINDOWS_WIRELESS_DIAGNOSTICS.md).

## Server-Client physical distance

The Server Dashboard now exposes a separate **Distance from Server** value for each stable Client ID. It never derives metres from network RTT, Wi-Fi Signal %, RSSI, or the existing Client-to-AP estimate.

- `MANUAL_MEASUREMENT`: an ADMIN enters a distance measured with a ruler/laser. It is labelled Manual and is not realtime.
- `POSITION_BASED_ESTIMATION`: an ADMIN enters Server and Client coordinates in metres in the same named room-map/reference frame. The Server computes Euclidean 2D distance, or 3D distance only when both Z coordinates are present.
- `DIRECT_MEASUREMENT`: reserved for a future verified direct-ranging provider. No such provider is enabled in this release.
- `NOT_AVAILABLE`: shown when there is no valid manual measurement or matching coordinate pair.

Open a Client Card and select **Server-Client Distance**. Use **Set Server Position** once for the room map, then **Edit Client Position** or **Enter Measured Distance**. **Clear Distance** deletes only that Client's stored position/manual measurement; **Clear Server Position** removes the shared reference position and makes coordinate-derived values unavailable until it is configured again. Location data is Server-owned, ADMIN-only at the service layer, and persisted atomically at `%USERPROFILE%\.lanmonitoringserver\server-client-distances-v1.json` (or `-Dlanmonitoring.distance.store=<path>`).

Use **Distance Map** on the Dashboard for the multi-Client table and 2D room map, or **Open Room Map** on a Client Card to open the same view with that Client selected. The table always lists every known Client. The map draws only current `OK` positions in the same reference frame; manual-only entries are never assigned synthetic coordinates. Line labels ending in `(coords)` are computed from configured ADMIN coordinates. ADMIN/manual records remain valid when a Client is Offline, while an automatic Wi-Fi fingerprint estimate becomes `STALE` on disconnect or TTL expiry and is not plotted or shown as realtime metres. Zoom buttons, mouse-wheel zoom and **Fit to View** are available.

Desktop verification with one Server and three real Clients is documented in [SERVER_CLIENT_DISTANCE_TEST_CHECKLIST.md](docs/SERVER_CLIENT_DISTANCE_TEST_CHECKLIST.md). The checklist remains NOT RUN until completed on an interactive desktop.

Build and run the current release:

```powershell
& 'D:\NetBeans\NetBeans-22\netbeans\extide\ant\bin\ant.bat' release-phase9
dist\scripts\run-server.bat
dist\scripts\run-client.bat
```

The release marker is `phase9-wifi-fingerprint-6`. Artifact size/hash are recorded after each clean release in [TEST_RESULTS.md](docs/TEST_RESULTS.md).

# Wi-Fi fingerprint indoor positioning

The optional Java multi-AP fingerprinting pipeline estimates a Client position from a Server-owned,
administrator-surveyed Radio Map. Windows uses documented Native Wi-Fi BSS RSSI values through JNA;
Linux uses a rate-limited `iw` scan. Only SHA-256 AP target IDs, real RSSI dBm, frequency and timestamp
are transported—never raw SSID/BSSID. The feature is opt-in, has a separate scheduler and requires TLS
by default.

Fingerprint results are labelled `POSITION_BASED_ESTIMATION` / `WIFI_FINGERPRINT`; they are not Wi-Fi
RTT/FTM or direct Server-to-Client measurements. Manual distance and ADMIN coordinates retain priority.
Only an `OK`, unexpired estimate may produce realtime metres. `STALE`, `UNSUPPORTED` and
`NOT_AVAILABLE` are displayed distinctly; a stale coordinate may remain visible only as historical
diagnostic data and is removed from the realtime room map. Disconnect marks a Wi-Fi estimate stale,
and reconnect requires a fresh scan before it returns to `OK`.
See [docs/WIFI_FINGERPRINTING_GUIDE.md](docs/WIFI_FINGERPRINTING_GUIDE.md) for survey, configuration,
privacy, Windows/Linux and multi-Client demo instructions.

