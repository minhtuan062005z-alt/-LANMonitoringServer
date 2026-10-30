# Wi-Fi Fingerprinting và Radio Map

Checklist thao tác Windows với 1 Server, nhiều Client, holdout ground truth và thống kê sai số:
[WIFI_FINGERPRINTING_FIELD_TEST_CHECKLIST.md](WIFI_FINGERPRINTING_FIELD_TEST_CHECKLIST.md).

## Mục đích

Module này ước lượng tọa độ của từng Client từ một vector RSSI của nhiều Access Point và Radio Map đã khảo sát. Server sau đó tính khoảng cách Euclidean từ vị trí Server đã cấu hình tới tọa độ ước lượng của Client.

Kết quả luôn có:

- `measurementMethod = POSITION_BASED_ESTIMATION`;
- `dataSource = WIFI_FINGERPRINT`;
- confidence, estimated error, timestamp và trạng thái;
- `NOT_AVAILABLE` nếu thiếu scan, thiếu Radio Map hoặc không đủ AP.
- `UNSUPPORTED` nếu OS/driver/quyền không cung cấp multi-AP RSSI.
- `STALE` khi TTL hết hoặc Client disconnect; tọa độ cuối có thể được giữ để xem lịch sử nhưng
  không được dùng làm khoảng cách realtime hay vẽ như vị trí hiện hành.

Đây không phải Wi-Fi RTT/FTM, không phải phép đo trực tiếp Server–Client và không dùng network RTT để tạo mét.

## Kiến trúc

```text
Client MultiAccessPointScanner
  Windows: Native Wi-Fi WLAN_BSS_ENTRY.lRssi
  Linux:   iw dev <interface> scan
       -> WifiScanSnapshot (hashed AP target + RSSI dBm + frequency)
       -> WIFI_SCAN / WIFI_FINGERPRINT_V1 qua authenticated TLS

Server
  RadioMapStore (atomic JSON, sample scoped by stable Client ID)
       -> WifiFingerprintEstimator (weighted KNN)
       -> EstimatedClientPosition
       -> ServerClientDistanceService
       -> Dashboard + Distance Table + Room Map
```

`WIRELESS_SIGNAL_V1` và ước lượng Client–AP hiện có không bị thay đổi. Multi-AP scan dùng message, scheduler và model riêng.

## Bảo mật và quyền riêng tư

- Feature mặc định tắt trên Client và chỉ chạy khi người dùng bật.
- Raw SSID không được thu thập vào snapshot.
- Raw BSSID chỉ tồn tại tạm thời trong collector để tạo SHA-256 target ID; protocol và Radio Map chỉ chứa hash.
- Server chỉ nhận `WIFI_SCAN` từ session đã xác thực đúng Client ID.
- Mặc định Server chỉ quảng bá `WIFI_FINGERPRINT_V1` trên TLS. Thuộc tính `lanmonitoring.fingerprint.requireTls=true` là mặc định.
- Radio Map chỉ ADMIN được thêm/xóa và thao tác được audit.
- Mỗi snapshot tối đa 128 AP; interval tối thiểu 5 giây.
- Không chạy scanner trên EDT, heartbeat hoặc telemetry executor.
- Radio Map được scope theo stable Client ID để tránh áp fingerprint của adapter này cho adapter khác.

Chỉ dùng `-Dlanmonitoring.fingerprint.requireTls=false` trong lab cô lập để chẩn đoán. Không dùng cấu hình này khi truyền dữ liệu vị trí trên mạng không tin cậy.

### Chẩn đoán scheduler/transport

- Probe `status=OK` chỉ xác nhận collector cục bộ; nó không xác nhận scheduler hoặc transport đang chạy.
- Client phải log `WIFI_FINGERPRINT_V1=true`, `multi-AP enabled=true`, sau đó `WIFI_SCAN sent #1`.
- Nếu kết nối là TCP thường với policy mặc định, Client log `WIFI_FINGERPRINT_V1=false` và lý do TLS;
  Server log capability bị giữ lại. Checkbox vẫn giữ trạng thái opt-in nhưng scheduler được ghi rõ là `blocked`.
- Tên message trên wire là `WIFI_SCAN` (không phải `WIRELESS_SCAN`). Server log số AP hash khi nhận và
  log rõ các trường hợp từ chối do TLS hoặc timestamp cũ.
- Cả Server và Client phải in `release=phase9-wifi-fingerprint-6`; nếu khác, thay JAR/shortcut cũ rồi khởi động lại.

## Thuật toán Java

Weighted KNN được tái triển khai bằng Java, không sao chép source Python:

1. Query và fingerprint là map `AP target hash -> RSSI dBm`.
2. Cần ít nhất 3 AP thật trong query.
3. Một neighbor chỉ hợp lệ khi có tối thiểu 3 AP giao nhau.
4. Feature thiếu dùng floor `-100 dBm`, không dùng giá trị RSSI giả dương.
5. RSSI distance là RMS trên hợp của các AP.
6. Chọn tối đa 3 neighbor gần nhất và dùng trọng số `1 / (distance + 1)^2`.
7. Tọa độ là trung bình có trọng số.
8. Estimated error là giá trị lớn hơn giữa neighbor spread và nửa độ phân giải trung bình của Radio Map.
9. Confidence dựa trên số AP giao nhau, RSSI distance và estimated error.

Một Client cần ít nhất 3 điểm khảo sát khác nhau trong cùng reference frame. Thực tế nên khảo sát grid 1–2 m, nhiều lần ở mỗi điểm, nhiều hướng đặt máy và ít nhất 4–6 AP ổn định.

## Cấu hình

```text
-Dlanmonitoring.fingerprint.enabled=true
-Dlanmonitoring.fingerprint.interval.ms=10000
-Dlanmonitoring.fingerprint.requireTls=true
-Dlanmonitoring.fingerprint.store=C:\protected\wifi-radio-map-v1.json
```

Giới hạn interval: 5.000–300.000 ms; mặc định 10.000 ms. Store mặc định:

```text
~/.lanmonitoringserver/wifi-radio-map-v1.json
```

## Demo Windows

1. Tạo certificate có SAN đúng trên Server:

   ```powershell
   .\dist\scripts\setup-tls-windows.ps1
   .\dist\scripts\run-server-tls.ps1
   # Chép chỉ client-truststore.p12 sang Client rồi chạy:
   .\dist\scripts\run-client-tls.ps1
   ```

2. `run-server.bat`/`run-client.bat` là compatibility launcher; khi không có biến TLS chúng dùng plain TCP
   và Server sẽ không quảng bá `WIFI_FINGERPRINT_V1`.

3. Nếu Windows hỏi Location, cho phép quyền vị trí cho ứng dụng desktop. Native Wi-Fi có thể trả Access Denied nếu consent bị tắt.
4. Trên Client chọn `Wi-Fi Research > Test multi-AP scanner now`. Log phải báo `status=OK` và số `APs with real RSSI >= 3`.
5. Chọn `Enable multi-AP fingerprint scans`.
6. Trên Server đặt Server Position và reference point, ví dụ `LAB-A`.
7. Mở `Distance Map > Wi-Fi Radio Map Survey`.
8. Đặt Client tại một tọa độ đã đo bằng thước, chọn Client, bấm `Add Survey Sample`, nhập `LAB-A`, X, Y.
   Selection được giữ theo Client ID khi bảng refresh/sort hoặc Client chuyển Offline; nếu Client biến mất khỏi
   bảng thì selection bị xóa và nút Add tự tắt.
9. Mỗi timestamp `Updated` chỉ dùng cho một sample/tọa độ. Sau khi di chuyển phải chờ scan mới;
   lặp lại tại ít nhất ba tọa độ khác nhau và khuyến nghị nhiều điểm hơn.
10. Di chuyển Client tới vị trí khác và chờ scan tiếp theo. Bảng phải hiển thị estimated position,
    confidence/error và `Position Status=OK`. Card và Room Map chỉ hiển thị khoảng cách khi Server
    position và estimate `OK` còn hạn cùng reference frame.

Không nhập tọa độ ví dụ như dữ liệu thực. Mỗi Client/adapter phải có survey riêng.

## Demo Linux

1. Xác minh interface và khả năng scan:

   ```bash
   iw dev
   iw dev wlan0 scan
   ```

2. Nếu `Operation not permitted`, cấu hình quyền quản trị phù hợp cho đúng công cụ/helper. Ứng dụng không tự gọi `sudo` và không thay đổi firewall/driver.
3. Chạy Client với TLS rồi thực hiện survey giống Windows.
4. Nếu driver không cho active scan trong khi connected, UI phải giữ `NOT_AVAILABLE`; không chuyển Link Quality hoặc RSSI của một AP thành vị trí.

## Kiểm thử 1 Server và 3 Client

- Bật TLS và xác nhận cả ba Client đàm phán `WIFI_FINGERPRINT_V1=true`.
- Probe scanner từng Client; ghi adapter, OS, số AP, lỗi quyền nếu có.
- Khảo sát tối thiểu ba điểm cho từng stable Client ID. Không dùng sample của Client khác.
- Đặt ba Client ở ba vị trí đo bằng thước; ghi estimated coordinate, distance, confidence và error.
- Di chuyển một Client, kiểm tra cập nhật sau interval; hai Client còn lại không đổi dữ liệu.
- Tắt fingerprint scan trên một Client; sau TTL estimate phải thành `STALE`/không còn distance live.
- Ngắt kết nối một Client; fingerprint estimate phải chuyển ngay thành `STALE`, không còn metres/map
  realtime; tọa độ lịch sử vẫn được gắn nhãn `HISTORICAL`. Monitoring của Client khác tiếp tục bình thường.
- Reconnect cùng stable Client ID không tạo Card mới; chỉ scan mới hơn mới đưa estimate về `OK`.
- Khởi động lại Server; Radio Map phải được load lại. Client cần reconnect và gửi scan mới trước khi có estimate realtime.
- Kiểm tra Alerts, File Transfer, Ansible, Performance và `WIRELESS_SIGNAL_V1` vẫn hoạt động.

## Giới hạn

- Độ chính xác phụ thuộc môi trường, mật độ survey, multipath, thay đổi AP và khác biệt adapter.
- AP đổi BSSID hoặc bị di chuyển làm Radio Map xuống cấp.
- Một AP không xác định được tọa độ 2D; dưới 3 AP trả `NOT_AVAILABLE`.
- Windows cần Native Wi-Fi/Location consent; Linux có thể cần quyền scan do policy của distribution.
- Scan chủ động có thể tạo gián đoạn Wi-Fi ngắn; vì vậy scheduler tách riêng và mặc định 10 giây.
- Manual distance và ADMIN coordinates luôn có ưu tiên hơn fingerprint estimate; module không ghi đè chúng.
- ADMIN/manual data không tự chuyển stale khi Client Offline. Quy tắc disconnect-stale chỉ áp dụng
  cho estimate tự động có nguồn `WIFI_FINGERPRINT`.
- Chưa có kết quả độ chính xác thực địa cho phòng của người dùng. Cần báo MAE, median, p95 và dropout sau khi đo ground truth.
