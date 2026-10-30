# Thiết kế khoảng cách Server–Client trong LAN

## 1. Kết luận kiến trúc

Trong giới hạn hiện tại—một Server, nhiều Client Windows/Linux, một AP thông thường hoặc Ethernet, không thêm phần cứng và không được cấu hình AP—hệ thống **không thể tự động xác định khoảng cách vật lý Server–Client một cách tổng quát và đáng tin cậy**.

Hệ thống hiện đo được network RTT giữa Server và Client, đồng thời có thể quan sát RSSI của liên kết Client–AP. Hai đại lượng này không phải phép đo khoảng cách Server–Client. Nếu không có ranging trực tiếp hoặc vị trí của hai đầu trong cùng hệ tọa độ, kết quả bắt buộc là `NOT_AVAILABLE`.

Giải pháp đề xuất cho đồ án:

1. Giữ network latency, Client–AP estimate và Server–Client distance thành ba model/UI field độc lập.
2. Ưu tiên provider ranging trực tiếp, nhưng chỉ kích hoạt sau khi xác minh phần cứng, driver, API và đúng hai đầu đo.
3. Cho phép quản trị viên nhập vị trí hoặc khoảng cách đo thủ công với nhãn `MANUAL`, timestamp và người nhập.
4. Khi không có nguồn hợp lệ, hiển thị `NOT_AVAILABLE — no direct ranging or common coordinates` thay vì suy diễn từ ping/RSSI.

## 2. Trạng thái module Wireless hiện tại

Luồng hiện có:

```text
Windows netsh / Linux iw
        │
        ▼
WirelessSignalSample (Client quan sát liên kết với AP)
        │ WIRELESS_SIGNAL_V1, Client ID đã xác thực
        ▼
WirelessTracker (ordering, EMA, calibration Client+AP)
        │
        ▼
WirelessDistanceEstimate
target = ACCESS_POINT
method = RSSI_LOG_DISTANCE
        │
        ▼
Server Network tab: Estimated Client-to-AP Distance
```

Các đặc tính đã xác minh từ mã:

- Windows gọi `netsh wlan show interfaces`, đọc adapter, trạng thái, SSID/BSSID, Signal % và RSSI dBm nếu driver cung cấp trường riêng `Rssi`/`RSSI`.
- Linux gọi `iw dev` và `iw dev <adapter> link`, đọc BSSID và RSSI dBm của AP đang association.
- Collector `WIRELESS_SIGNAL_V1` không active scan. Module fingerprint opt-in riêng dùng active
  multi-AP scan có rate limit (`WIFI_SCAN`) và không thay đổi semantics của collector này.
- SSID/BSSID bị ẩn mặc định; SHA-256 AP target được giữ để gắn calibration mà không truyền địa chỉ AP thô.
- RSSI được làm mượt bằng EMA alpha `0.30`; calibration log-distance được scope theo Client ID + AP target.
- `WifiRttProvider` mới là extension point; implementation đi kèm trả `UNSUPPORTED`.
- Application `PING/PONG` tạo `NetworkLatencySample`; giá trị này không tham gia estimator.

Module hiện tại vì vậy chỉ có cơ sở cho **Estimated Client-to-AP Distance**, không phải Server-to-Client distance.

## 3. Nguồn dữ liệu khả dụng

| Nguồn | Quan sát trực tiếp | Hai đầu của phép đo | Có suy ra Server–Client không? |
|---|---|---|---|
| Application PING/PONG | RTT ms qua toàn bộ network/software path | Server ↔ Client | Không |
| Windows `netsh wlan show interfaces` RSSI | Công suất tín hiệu nhận của association hiện tại | Client ↔ AP | Không |
| Windows Signal % | Link Quality do OS/driver biểu diễn | Client ↔ AP | Không; không phải dBm |
| Linux `iw ... link` signal | RSSI dBm của association | Client ↔ AP | Không |
| BSSID/AP target hash | Định danh AP phục vụ calibration | Client ↔ AP | Không cung cấp tọa độ |
| IP, subnet, gateway, interface | Topology logic và loại kết nối | Network path | Không |
| TCP throughput/telemetry | Chất lượng và tải kết nối | Network path | Không |
| Wi-Fi RTT/FTM capability flag | Khả năng tiềm năng của adapter | Chưa có phiên ranging | Không, cho tới khi ranging thật thành công |
| Vị trí/tọa độ nhập tay | Vị trí do quản trị viên cung cấp | Server và Client | Có thể tính nếu cùng hệ tọa độ |
| Khoảng cách đo tay | Phép đo ngoại vi do người dùng nhập | Server ↔ Client | Có, nhưng không tự động/realtime |
| Multi-AP fingerprint + Radio Map | Tọa độ Client ước lượng bằng weighted KNN | Client trong reference frame đã survey | Có thể tính position-based khi estimate `OK` còn hạn và Server có tọa độ cùng frame |

### Ethernet

Ethernet không cung cấp RSSI. Link speed, cable status, RTT, switch port hoặc số hop không phải chiều dài cáp hay khoảng cách Euclid giữa hai máy. Khi không có tọa độ/manual measurement, Server–Client physical distance trên Ethernet phải là `NOT_AVAILABLE`.

### Dữ liệu vị trí

Ứng dụng hiện không thu GPS, indoor coordinates, floor plan position, bearing hoặc angle-of-arrival. IP geolocation không có ý nghĩa cho khoảng cách các máy trong cùng LAN. Không được suy ra vị trí từ hostname, địa chỉ IP hoặc thứ tự port của switch.

## 4. Phân biệt ba đại lượng bắt buộc

### 4.1 Network latency

`NetworkLatencySample` đo thời gian application message đi Server → Client → Server. Nó bao gồm queueing, Wi-Fi contention, AP/switch forwarding, TCP scheduling, JVM/OS scheduling và thời gian xử lý Client.

Không sử dụng:

```text
distance = RTT × speed_of_light / 2
```

Một RTT LAN 16 ms nếu diễn giải sai theo tốc độ ánh sáng sẽ tương ứng hàng nghìn kilomet, trong khi phần lớn thời gian là software/network latency. Sai số này lớn hơn nhiều bậc so với propagation time của vài mét.

UI đúng: `Network RTT: 16 ms (latency, not physical distance)`.

### 4.2 Client-to-AP distance

RSSI hiện tại phản ánh liên kết giữa radio của Client và AP. Sau calibration đúng Client/adapter/AP/môi trường, log-distance model có thể tạo **ước lượng Client–AP**:

```text
RSSI(d) = RSSI(d0) - 10 n log10(d / d0)
d = d0 × 10 ^ ((RSSI(d0) - RSSI(d)) / (10 n))
```

Trong đó:

- `d0`: khoảng cách tham chiếu, hiện là 1 m.
- `RSSI(d0)`: RSSI fitted tại khoảng cách tham chiếu.
- `n`: path-loss exponent fitted từ các điểm đo thật.
- RMSE: sai số calibration dùng để mô tả uncertainty, không phải bảo đảm thống kê tuyệt đối.

RSSI bị ảnh hưởng bởi tường, multipath, người di chuyển, orientation, band/channel, transmit power, antenna và driver. Kết quả chỉ mang nhãn `Estimated Client-to-AP Distance`.

### 4.3 Server-to-Client physical distance

Đây là khoảng cách hình học giữa vị trí vật lý Server và Client. Nó chỉ được công bố khi một trong các điều kiện sau đúng:

1. Có phép ranging trực tiếp, xác minh được hai đầu là Server và Client; hoặc
2. Có tọa độ Server và Client trong cùng reference frame, với provenance/uncertainty rõ ràng; hoặc
3. Quản trị viên nhập một khoảng cách đã đo thủ công, được gắn nhãn `MANUAL_MEASURED`.

Nếu không thỏa điều kiện, giá trị là `NOT_AVAILABLE`.

## 5. Vì sao một AP không đủ

Giả sử biết:

- `rS`: khoảng cách Server–AP.
- `rC`: khoảng cách Client–AP.
- `θ`: góc giữa hai hướng AP→Server và AP→Client.

Khoảng cách cần tìm là:

```text
dSC² = rS² + rC² - 2 rS rC cos(θ)
```

Một AP và hai range không cung cấp `θ`. Do đó chỉ có giới hạn tam giác:

```text
|rS - rC| ≤ dSC ≤ rS + rC
```

Đây là một khoảng nghiệm, không phải một distance estimate duy nhất. Nếu Server nối Ethernet thì `rS` thường còn không tồn tại. Hai máy có cùng khoảng cách đến AP có thể ở cạnh nhau hoặc ở hai phía đối diện của AP.

Trong mặt phẳng 2D, một range tới một anchor chỉ đặt Client trên một đường tròn. Thông thường cần ít nhất ba anchor không thẳng hàng với range đáng tin cậy để trilateration; nhiều RSSI từ cùng một AP theo thời gian không tạo thêm anchor độc lập. Với ràng buộc không cấu hình router/AP và chỉ có một AP, phương án này không khả thi.

## 6. Khả năng đo trực tiếp

### Wi-Fi RTT/FTM

Chỉ coi là direct ranging khi provider thực hiện một phiên FTM thật và xác minh responder target. Việc adapter báo `FTM initiator` không chứng minh:

- AP là FTM responder.
- Driver/API desktop cho phép ứng dụng Java ranging.
- Server laptop có thể đóng vai responder.
- Range đang đo đúng Server thay vì AP.

Nếu Client range tới AP, kết quả vẫn là Client–AP. Để tạo Server–Client direct distance, Server phải là responder/peer đã xác thực hoặc cả hai máy phải có vị trí được giải từ nhiều anchor. Các điều kiện này không thể mặc định với laptop Windows/Linux và AP không được cấu hình.

### Các công nghệ khác

UWB, BLE direction finding/AoA, camera/depth hoặc thiết bị RTLS có thể cung cấp ranging/position tốt hơn nhưng nằm ngoài giới hạn phần cứng của yêu cầu. Không thiết kế bản hiện tại phụ thuộc vào chúng.

## 7. Kiến trúc đề xuất

Không thay đổi semantics của `WirelessDistanceEstimate`. Thêm một miền model độc lập trong giai đoạn triển khai sau:

```text
DistanceProvider
├── DirectWifiRttProvider       (chỉ khi real hardware/API verified)
├── CoordinateDistanceProvider (tọa độ cùng reference frame)
├── ManualDistanceProvider     (quản trị viên nhập phép đo)
└── UnavailableDistanceProvider
                 │
                 ▼
ServerClientDistance
                 │
                 ▼
Per-client Distance Registry / History
                 │
                 ▼
Dashboard Card + Client Detail > Distance
```

### Model đề xuất

`ServerClientDistance` nên chứa tối thiểu:

| Field | Ý nghĩa |
|---|---|
| `clientId` | Client đã xác thực |
| `target` | Luôn là `SERVER_CLIENT` |
| `status` | `OK`, `STALE`, `UNSUPPORTED`, `NOT_AVAILABLE`, `ERROR` |
| `method` | `DIRECT_WIFI_FTM`, `COMMON_COORDINATES`, `MANUAL_MEASURED`, `NONE` |
| `meters` | Null trừ khi có cơ sở hợp lệ |
| `uncertaintyMeters` | Null nếu không xác định được |
| `measuredAt` | Thời điểm đo/nhập nguồn |
| `receivedAt` | Thời điểm Server nhận/lưu |
| `validUntil` | Freshness policy theo method |
| `source` | Provider và platform |
| `confidence` | Không dùng HIGH nếu chưa có validation thực địa |
| `detail` | Mô tả target, giới hạn và provenance |

Không dùng chung field này với `NetworkLatencySample` hoặc `WirelessDistanceEstimate` để tránh UI/protocol vô tình đổi nhãn Client–AP thành Server–Client.

### Provider selection

Thứ tự lựa chọn:

1. Direct provider đang `SUPPORTED`, đã range thành công đúng peer và mẫu còn hạn.
2. Hai vị trí hợp lệ trong cùng coordinate system.
3. Khoảng cách đo thủ công còn hiệu lực theo chính sách quản trị.
4. `NOT_AVAILABLE`.

Không tự động fallback từ direct ranging sang RSSI hoặc RTT. Fallback phải đổi `method` và nhãn hiển thị rõ ràng.

### Protocol tương lai

Nếu triển khai, nên thêm message/capability riêng thay vì nhồi vào `WIRELESS_SIGNAL_V1`:

```text
DISTANCE_CAPABILITY_V1
POSITION_REPORT_V1
DIRECT_RANGE_RESULT_V1
```

Mọi report phải gắn authenticated Client ID, timestamp, provider, target và status. Server từ chối timestamp tương lai, out-of-order, target không đúng hoặc method không được phép. Manual data là dữ liệu Server-owned; Client không được tự khai báo khoảng cách quản trị có thẩm quyền.

## 8. Phương án fallback phù hợp

### 8.1 Nhập khoảng cách đo thủ công

Quản trị viên đo Server–Client bằng thước/laser hoặc lấy từ sơ đồ phòng, sau đó nhập:

- Client ID.
- Khoảng cách mét.
- Thời điểm đo.
- Ghi chú vị trí và phương pháp.
- Uncertainty nếu biết.

UI hiển thị `12.4 m — MANUAL, measured 14:30`, không gọi là realtime. Khi Client/máy bị di chuyển hoặc quá hạn, chuyển `STALE` cho tới khi đo lại.

### 8.2 Nhập tọa độ trong sơ đồ phòng

Quản trị viên định nghĩa một coordinate system cục bộ, ví dụ mét theo mặt bằng phòng:

```text
Server = (xS, yS, floor)
Client = (xC, yC, floor)
d = sqrt((xC-xS)² + (yC-yS)²)
```

Chỉ tính khi cùng floor/reference frame hoặc có mô hình 3D hợp lệ. Giá trị được ghi `COMMON_COORDINATES`, không phải radio ranging. Nếu vị trí Client không được cập nhật tự động, distance không phải realtime.

### 8.3 Vị trí logic thay vì mét

Khi không cần số mét, phương án thực tế hơn là cho quản trị viên gán `Room A`, `Lab 2`, `Floor 3` hoặc nhóm thiết bị. Đây là location metadata hữu ích cho quản lý nhưng không được đổi thành khoảng cách số.

### 8.4 Automatic unavailable

Các trường hợp sau trả `NOT_AVAILABLE`:

- Client chỉ có Ethernet và không có tọa độ/manual measurement.
- Chỉ có application RTT.
- Chỉ có Client–AP RSSI/range.
- Chỉ phát hiện capability FTM nhưng chưa range thành công.
- AP target thay đổi, mẫu stale hoặc calibration không khớp.
- Không xác định đúng target của phép đo.

## 9. Thiết kế giao diện Server

### Dashboard Card

Thêm hai dòng riêng, không thay thế nhau:

```text
Network RTT: 16 ms
Server–Client Distance: NOT_AVAILABLE
```

Nếu có nguồn hợp lệ:

```text
Server–Client Distance: 12.4 m ± 0.8 m (DIRECT_WIFI_FTM)
```

hoặc:

```text
Server–Client Distance: 12.4 m (MANUAL, stale after move)
```

Không dùng màu “healthy” cho distance nếu chưa định nghĩa nghiệp vụ cụ thể.

### Client Detail

Trong tab Network/Distance hiển thị:

- Network Type và interface.
- Network RTT, nhãn latency.
- Client–AP RSSI và Client–AP Estimated Distance hiện có.
- Server–Client Distance tách riêng.
- Measurement target.
- Method/provider.
- Direct/derived/manual badge.
- Timestamp, age và stale state.
- Uncertainty/confidence.
- Lý do `NOT_AVAILABLE`.

### Quản lý dữ liệu thủ công

ADMIN có thể:

- Nhập/sửa vị trí Server.
- Nhập vị trí hoặc khoảng cách của Client.
- Đặt thời gian hết hạn.
- Đánh dấu Client đã di chuyển.
- Xem audit history.

VIEWER chỉ xem; OPERATOR chỉ sửa khi được cấp scope rõ ràng. Validation phải chặn NaN/infinity, số âm, đơn vị mơ hồ và coordinate system không khớp.

## 10. Điều kiện để công bố khoảng cách

Một giá trị Server–Client được phép có `status=OK` chỉ khi tất cả điều kiện tương ứng được thỏa:

### Direct ranging

- Hai endpoint của phép đo được xác định rõ là Server và Client.
- Session/identity đã xác thực.
- Hardware, driver và API thực hiện ranging thật.
- Không chỉ dựa trên capability string.
- Timestamp còn hạn và target không thay đổi.
- Có quality/uncertainty từ provider hoặc validation thực địa.

### Common coordinates

- Server và Client dùng cùng coordinate system, origin, unit và floor semantics.
- Provenance vị trí được lưu.
- Vị trí còn hiệu lực; Client chưa bị đánh dấu di chuyển.
- Công thức và uncertainty propagation được công bố.

### Manual measurement

- Có người nhập, thời gian đo và phương pháp.
- Giá trị được đánh dấu manual.
- Không quảng bá là realtime hoặc automatic.

Thiếu bất kỳ bằng chứng thiết yếu nào: `NOT_AVAILABLE` hoặc `STALE`, không giữ một số mét cũ như dữ liệu live.

## 11. Phương pháp kiểm chứng

### Direct provider nếu có trong tương lai

1. Ghi rõ adapter, driver, OS/API, responder và channel/band.
2. Đo ground truth bằng thước/laser ở nhiều khoảng cách.
3. Tách LOS và NLOS/tường/multipath.
4. Lặp nhiều mẫu ở mỗi vị trí.
5. Báo MAE, median, p95, bias, dropout rate và stale behavior.
6. Kiểm tra target binding: không nhận range của AP thay cho Server.
7. Kiểm tra nhiều Client đồng thời và ảnh hưởng telemetry.

### Coordinate/manual provider

- Unit test công thức 2D/3D và coordinate mismatch.
- Kiểm tra restart/persistence/audit/RBAC.
- Kiểm tra move/expiry chuyển `STALE`.
- Đối chiếu một tập điểm với ground truth độc lập.

### Negative tests bắt buộc

- RTT thay đổi nhưng distance không được tự sinh.
- Client–AP RSSI/range có nhưng Server–Client vẫn `NOT_AVAILABLE`.
- Một AP và thiếu bearing/coordinates không tạo distance.
- Ethernet-only trả `NOT_AVAILABLE` nếu không có manual/common coordinates.
- FTM capability không có ranging result trả `UNSUPPORTED`.
- Dữ liệu cũ, target sai hoặc identity sai bị từ chối.

## 12. Bảo mật và quyền riêng tư

- Tọa độ/phòng/khoảng cách có thể tiết lộ vị trí người dùng hoặc thiết bị; mặc định không thu thập.
- Raw SSID/BSSID tiếp tục opt-in; AP hash là pseudonymous, không phải anonymous tuyệt đối.
- Dữ liệu vị trí và manual measurement phải được mã hóa khi truyền, giới hạn RBAC và audit.
- Không log secret, raw identifier hoặc tọa độ nếu không cần chẩn đoán.
- Có retention và thao tác xóa dữ liệu vị trí riêng.
- Client không được tự nâng quyền hay tự sửa authoritative manual position.

## 13. Kế hoạch triển khai theo bước

Tài liệu này chưa triển khai các bước sau.

### Bước 1 — Semantic contract

- Tạo model `ServerClientDistance`, enum method/source/status.
- Khóa invariants: target Server–Client, value nullable, RTT/RSSI không được làm fallback.
- Viết negative tests trước.

### Bước 2 — Manual/common-coordinate baseline

- Store atomic, RBAC và audit.
- UI nhập Server position, Client position hoặc direct manual distance.
- Hiển thị method/timestamp/stale; không gọi manual value là realtime.

### Bước 3 — Capability discovery

- Bổ sung capability model riêng cho direct ranging.
- Probe Windows/Linux API an toàn, không yêu cầu elevation mặc định.
- Capability chỉ điều khiển `SUPPORTED/UNSUPPORTED`, chưa tạo mét.

### Bước 4 — Optional direct provider

- Chỉ triển khai nếu có hardware/API/responder thực tế.
- Provider chạy ngoài EDT/heartbeat executor, có timeout/cancel/rate limit.
- Bind target, validate timestamp và giữ raw evidence tối thiểu cần thiết.

### Bước 5 — Multi-Client UI

- Card hiển thị Server–Client distance hoặc lý do unavailable.
- Detail tách ba đại lượng và có history theo method.
- Filter theo available/stale/manual/direct mà không trộn với health alert.

### Bước 6 — Field validation

- Test Windows/Linux thật, Ethernet, Wi-Fi, Client move và restart.
- So sánh ground truth, công bố error distribution.
- Chỉ bật automatic distance mặc định sau khi provider đạt tiêu chí validation được định nghĩa trước.

## 14. Quyết định đề xuất cho dự án hiện tại

Trong cấu hình phần cứng đã nêu, Server nên quản lý nhiều Client bằng:

- RTT realtime để đánh giá chất lượng kết nối.
- Client–AP RSSI và calibrated Client–AP estimate như tính năng nghiên cứu riêng.
- Server–Client distance bằng manual/common-coordinate fallback khi quản trị viên có dữ liệu.
- `NOT_AVAILABLE` cho automatic Server–Client distance trong mọi trường hợp còn lại.

Đây là phương án trung thực về mặt khoa học, vẫn thể hiện rõ kiến trúc mở cho direct ranging nếu thiết bị thật hỗ trợ về sau, và tránh biến dữ liệu mạng thành một con số khoảng cách không có cơ sở.

## 15. Trạng thái triển khai (2026-10-27)

Fallback khoa học trong tài liệu này đã được triển khai bằng domain độc lập `ServerClientDistance`:

- ADMIN có thể lưu vị trí Server và từng Client trong cùng reference frame, hoặc nhập khoảng cách đo thủ công.
- Store JSON atomic dùng stable Client ID và khôi phục sau restart.
- Dashboard Card và tab chi tiết phân biệt Manual, Position-Based, Direct và `NOT_AVAILABLE`.
- Giá trị manual/coordinate không được gắn nhãn realtime. `DIRECT_MEASUREMENT` mới là extension point, chưa có provider trong bản hiện tại.
- Wireless Client-to-AP, RSSI và network RTT giữ nguyên model/UI riêng, không tham gia phép tính Server-to-Client.

Automated regression đã xác minh công thức, validation, persistence, isolation, Offline/reconnect và headless EDT. Kiểm thử dialog trên desktop và đối chiếu bằng thước với thiết bị thật vẫn cần thực hiện thủ công.

## 16. Giao diện giám sát nhiều Client

`ServerClientDistanceManagementPanel` gồm bảng tất cả Client và `RoomMapPanel`:

- Bảng hiển thị Client ID, hostname, IP, distance, measurement method, data status và timestamp. Connection Online/Offline không được dùng thay cho data status.
- Sơ đồ lấy đúng `SpatialPosition` đã lưu. Server và Client chỉ được nối khi cùng reference point; Client manual-only hoặc thiếu tọa độ không được tự động sắp xếp lên bản đồ.
- Nhãn đường nối có hậu tố `(coords)` để phân biệt khoảng cách hình học từ tọa độ với manual measurement có thể đang được ưu tiên trong bảng.
- Với ADMIN/manual data, Online/Offline chỉ điều khiển màu điểm Client. Với nguồn tự động
  `WIFI_FINGERPRINT`, disconnect chuyển estimate thành `STALE`; tọa độ cuối được giữ làm lịch sử nhưng
  không được vẽ/tính như dữ liệu realtime. Zoom/fit không sửa tọa độ model.
- Dashboard có nút mở bảng/map chung; mỗi Card có nút mở cùng view và chọn đúng Client ID.

`DistanceManagementPanelTest` chạy headless trên EDT với ba Client có tọa độ, một Client không có vị trí, một Client Offline, thao tác move/update, zoom/fit, map paint và callback từ Card. Kiểm thử desktop trực quan vẫn phải thực hiện theo `SERVER_CLIENT_DISTANCE_TEST_CHECKLIST.md`.

## Tài liệu tham khảo kỹ thuật

- [Microsoft netsh wlan](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/netsh-wlan)
- [Linux Wireless iw documentation](https://wireless.docs.kernel.org/en/latest/en/users/documentation/iw.html)
- [Linux cfg80211 signal units](https://docs.kernel.org/6.6/driver-api/80211/cfg80211.html)
- [Android Wi-Fi RTT overview](https://developer.android.com/develop/connectivity/wifi/wifi-rtt)
- [NISTIR 7761 — path-loss models](https://nvlpubs.nist.gov/nistpubs/Legacy/IR/nistir7761.pdf)
