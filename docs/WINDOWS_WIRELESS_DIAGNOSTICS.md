# Windows Wireless Monitoring Diagnostics

Tài liệu này dùng cho trường hợp Server có RTT nhưng tab Network không có Wi-Fi signal. RTT chỉ chứng minh modern Client có thể trả PING/PONG; Wi-Fi còn phụ thuộc capability, toggle, collector, transport và panel đang mở đúng Client.

## Chạy đúng artifact

Đóng toàn bộ cửa sổ Server/Client cũ. Từ thư mục gốc dự án, build một lần:

```powershell
& 'D:\NetBeans\NetBeans-22\netbeans\extide\ant\bin\ant.bat' release-phase9
```

Mở hai cửa sổ PowerShell mới. Chạy Server:

```powershell
.\dist\scripts\run-server.bat
```

Chạy Client:

```powershell
.\dist\scripts\run-client.bat
```

Không chạy một Client JAR cũ hoặc class `frmClient` từ output Compile-on-Save cũ. Console và log GUI của cả hai phía phải chứa:

```text
release=phase9-wifi-fingerprint-6, protocol=modern-json-v1
```

Khi chạy từ distribution, `source` phải là `LANMonitoringServer.jar`. Khi chạy từ NetBeans, `source=classes` là bình thường nhưng phải có đúng release marker trên.

Với TLS launcher hiện hành, Client log phải có hai giá trị tách biệt:

```text
TLS identity verified: TCP endpoint=192.168.x.y:5000, certificate identity=lanmonitoring-server.local
```

`TCP endpoint` được phép đổi khi Server chuyển LAN/Wi-Fi. `certificate identity` phải giữ nguyên và phải khớp
DNS SAN trong certificate; không thêm IP mới vào certificate và không tắt endpoint identification.

## Bật phép đo

Trước tiên, trên Client chọn **Wi-Fi Research > Test Wi-Fi collector now**. Probe này chỉ đọc một mẫu cục bộ, không bật scheduler, không gửi SSID/BSSID và phải log tương đương:

```text
Wi-Fi collector probe: status=OK, network=WIFI, RSSI=-38.0 dBm, quality=100.0%, AP target=available (privacy hash)
```

Sau đó chọn **Wi-Fi Research > Enable Wi-Fi measurement**. Hoặc bật ngay từ lúc khởi động Client:

```powershell
$env:LANMONITORING_JAVA_OPTS='-Dlanmonitoring.wifi.enabled=true'
.\dist\scripts\run-client.bat
```

SSID/BSSID mặc định vẫn bị ẩn. Không cần bật chia sẻ identifier để đọc RSSI hoặc hiệu chuẩn vì AP target hash được giữ lại.

## Chuỗi log mong đợi

Client phải lần lượt có các dòng tương đương:

```text
Protocol negotiation: modern JSON v1; WIRELESS_SIGNAL_V1=true; Wi-Fi measurement enabled=true.
Wi-Fi signal measurement started every 2000 ms using Windows netsh wlan
WIRELESS_SIGNAL sent #1: status=OK, network=WIFI, RSSI=-38.0 dBm, quality=100.0%, identifiers=hidden.
```

Server phải có:

```text
Protocol detected: modern framed JSON v1 from ...
Authenticated modern client ...; client release=phase9-wifi-fingerprint-6; WIRELESS_SIGNAL_V1 advertised.
WIRELESS_SIGNAL authenticated and stored #1 ... RSSI=-38.0 dBm, history=1.
```

Mở Card của đúng Client và tab **Network**. Server log thêm:

```text
Network UI loaded 1 wireless observation(s) for ...
```

Tab phải hiển thị `Network Type: WIFI`, RSSI, Link Quality, Last Updated và biểu đồ signal. `Estimated Distance` tiếp tục là `UNSUPPORTED/NOT_AVAILABLE` cho tới khi nhập calibration Client-to-AP hợp lệ.

## Hiệu chuẩn Client-to-AP

Nút **Calibrate RSSI...** chỉ được bật khi Server có mẫu hiện tại với `status=OK`, RSSI dBm thật, target `ACCESS_POINT` và AP target hash hợp lệ. Nếu nút còn tắt, không nhập dữ liệu; quay lại chuỗi log ở trên.

Tại tối thiểu hai khoảng cách Client-to-AP khác nhau, đo khoảng cách thật và chờ RSSI ổn định. Nên dùng ít nhất bốn điểm. Nhập từng dòng:

```text
distanceMeters,rssiDbm
```

Không dùng các con số minh họa, Signal %, RTT hoặc khoảng cách Server-to-Client. Calibration được lưu theo cặp Client ID + AP privacy hash tại `%USERPROFILE%\.lanmonitoringserver\wireless-calibrations-v1.json` và tự khôi phục khi Server restart. File không chứa SSID/BSSID. Khi Client chuyển AP/BSSID hoặc một máy khác dùng cùng AP, calibration cũ không được áp dụng chéo.

## Khoanh vùng lỗi bằng log

| Log cuối cùng quan sát được | Kết luận cần kiểm tra |
|---|---|
| `Legacy protocol connection` hoặc Client ID bắt đầu `legacy:` | Đang chạy Client cũ/khác artifact; đóng tiến trình cũ và chạy `dist/scripts/run-client.bat`. |
| Không có release marker mới | Đang chạy JAR/classes cũ hoặc shortcut trỏ sai đường dẫn. |
| `WIRELESS_SIGNAL_V1=false` | Server cũ không quảng bá capability; thay cả Server và Client bằng cùng bản `dist`. |
| `enabled=false` | Bật menu Wi-Fi Research hoặc JVM property nêu trên. |
| Scheduler started nhưng `status=UNSUPPORTED` | Đọc chi tiết collector; chạy `netsh wlan show interfaces` bằng cùng tài khoản Windows. Không chuyển Signal % thành RSSI. |
| Probe có RSSI nhưng không có `AP target=available` | Output không cung cấp BSSID hợp lệ; Signal vẫn hiển thị nhưng calibration phải tiếp tục bị khóa. |
| Client có `sent #1`, Server không có `stored #1` | Thu thập log kết nối/protocol hai phía; kiểm tra reconnect hoặc lỗi validation ngay sau lần gửi. |
| Server có `stored #1`, UI báo loaded 0 | Đóng/mở lại đúng Card Client và đối chiếu Client ID; thu thập Server log. |
| Server có `stored #1` và `loaded 1` nhưng panel trống | Lỗi UI cần báo lại kèm log, ảnh màn hình và release marker. |

## Dữ liệu cần gửi khi báo lỗi

1. Release marker và `source` của Server/Client.
2. Các dòng log từ `Protocol detected/negotiation` tới `sent/stored/UI loaded`.
3. Output `netsh wlan show interfaces`, nhưng che SSID, BSSID, GUID và physical address; giữ lại State, Description, Signal và RSSI.
4. Ảnh tab Network và Client ID hiển thị trên Card.
5. Cách bật Wi-Fi measurement và thời điểm bật trước hay sau khi kết nối.

Không cần gửi Client secret, TLS password, private key, SSID hoặc BSSID. Không chạy Administrator chỉ để làm cho test PASS; `netsh` phải được kiểm tra trong cùng security context với tiến trình Java.
