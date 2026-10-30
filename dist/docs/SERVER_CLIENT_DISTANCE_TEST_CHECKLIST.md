# Server-Client Distance Desktop Test Checklist

Checklist này dành cho kiểm thử tương tác trên Windows với một Server và tối thiểu ba Client thật. Automated/headless test không thay thế kết quả desktop ở đây.

## 1. Chuẩn bị

- [ ] Chạy `ant release-phase9` và dùng đúng launcher trong `dist/scripts`.
- [ ] Log Server/Client hiển thị release `phase9-wifi-fingerprint-6`.
- [ ] Chuẩn bị sơ đồ phòng có hệ tọa độ theo mét và reference point duy nhất, ví dụ `LAB-A-2026`.
- [ ] Đo vị trí Server và ba Client bằng thước hoặc sơ đồ đã được xác minh.
- [ ] Ghi Client ID ổn định của từng máy; không dùng IP làm identity.
- [ ] Không dùng RSSI, Signal %, Client-to-AP estimate hoặc ping để điền tọa độ/khoảng cách.

## 2. Khởi động một Server và ba Client

1. Chạy `dist\scripts\run-server.bat`.
2. Chạy `dist\scripts\run-client.bat` trên Client A, B và C.
3. Chờ cả ba Card xuất hiện và xác nhận Online.
4. Mở từng Card, kiểm tra Client ID/hostname/IP đúng máy.

- [ ] Ba Client Online, không trùng Card/Client ID.
- [ ] Monitoring CPU/RAM/Disk và RTT tiếp tục cập nhật.
- [ ] Không có khoảng cách tự sinh trước khi cấu hình; hiển thị `NOT_AVAILABLE`.

## 3. Nhập vị trí thật

1. Mở Card A > `Server-Client Distance`.
2. Chọn `Set Server Position`; nhập reference point và X/Y thật của Server.
3. Chọn `Edit Client Position`; nhập X/Y thật của Client A trong cùng reference point.
4. Lặp lại vị trí Client B và C. Không nhập vị trí ước đoán chỉ để làm đẹp sơ đồ.
5. Nếu dùng Z, phải nhập Z cho cả Server và Client tương ứng; nếu một đầu thiếu Z, xác minh hệ thống dùng 2D.

- [ ] Card hiển thị distance và nguồn `Position-Based`.
- [ ] Công thức khớp `sqrt((xc-xs)^2 + (yc-ys)^2)` trong sai số làm tròn hiển thị.
- [ ] Timestamp không thay đổi chỉ vì heartbeat/telemetry.
- [ ] Reference point không khớp làm distance `NOT_AVAILABLE` và Client không được vẽ trong map của Server.

## 4. Bảng và sơ đồ nhiều Client

1. Nhấn `Distance Map` trên Dashboard.
2. Kiểm tra bảng có Client ID, hostname, IP, distance, method, data status và last updated.
3. Nhấn `Open Room Map` từ Card B.
4. Kiểm tra đúng dòng/điểm Client B được chọn.
5. Dùng `Zoom +`, `Zoom -`, con lăn chuột và `Fit to View`.

- [ ] Server được vẽ tại tọa độ đã cấu hình.
- [ ] A/B/C được vẽ đúng vị trí tương đối từ tọa độ thật.
- [ ] Có đường Server–Client và nhãn khoảng cách `(coords)`.
- [ ] Client manual-only, không có tọa độ, chỉ xuất hiện trong bảng và không bị đặt giả lên sơ đồ.
- [ ] Online/Offline chỉ đổi màu trạng thái kết nối; không đổi `Data Status` của vị trí.
- [ ] Resize/zoom/fit không treo EDT hoặc làm sai bảng.

## 5. Manual measurement

1. Dùng thước/laser đo trực tiếp Server–Client C.
2. Chọn `Enter Measured Distance`; nhập số mét và error nếu có.

- [ ] Bảng/Card hiển thị `MANUAL_MEASUREMENT`/Manual.
- [ ] Giá trị không tự thay đổi theo thời gian và không mang nhãn realtime.
- [ ] Manual distance không tạo một tọa độ giả trên sơ đồ.
- [ ] Giá trị âm, `NaN`, `Infinity` hoặc chuỗi sai bị từ chối.

## 6. Di chuyển, Offline và reconnect

1. Đo và di chuyển Client A tới vị trí khác.
2. Chỉnh X/Y Client A bằng `Edit Client Position`.
3. Xác minh bảng, Card và map cập nhật khoảng cách/điểm mới.
4. Ngắt Client B khỏi mạng.
5. Xác minh Card B Offline nhưng vị trí/distance quản trị vẫn còn.
6. Kết nối lại Client B bằng cùng identity.

- [ ] Không tạo Card mới khi reconnect.
- [ ] Khoảng cách vẫn gắn đúng Client ID.
- [ ] Client khác không nhận nhầm tọa độ của Client B.

## 7. Restart và persistence

1. Ghi lại vị trí/khoảng cách của A/B/C.
2. Dừng Server đúng cách rồi khởi động lại.
3. Kết nối lại ba Client.
4. Mở bảng/map và đối chiếu dữ liệu.

- [ ] Server position, Client positions và manual distance được khôi phục.
- [ ] File `%USERPROFILE%\.lanmonitoringserver\server-client-distances-v1.json` tồn tại và có quyền filesystem phù hợp.
- [ ] Dữ liệu không bị gán chéo khi IP thay đổi.
- [ ] `Clear Distance` chỉ xóa Client đang chọn.
- [ ] `Clear Server Position` làm coordinate-derived distance unavailable nhưng không dùng RSSI/RTT làm fallback.

## 8. Regression Giai đoạn 7–9 và chức năng cũ

- [ ] Monitoring/System Information/Performance vẫn cập nhật.
- [ ] Alert vượt ngưỡng, ACK và Admin Acknowledge/Resolve vẫn đúng semantics.
- [ ] File Transfer TLS, permission denial và checksum vẫn hoạt động.
- [ ] Ansible Management vẫn dùng explicit managed-host allowlist; không coi mọi Client Online là Ansible-managed.
- [ ] Wireless RSSI/Signal/Client-to-AP estimate vẫn nằm trong tab Network và không thay thế Server–Client distance.
- [ ] Server–Client automatic distance vẫn `NOT_AVAILABLE` khi thiếu manual/common coordinates/direct provider.

## 9. Biên bản kết quả

| Hạng mục | PASS/FAIL/NOT RUN | Bằng chứng/Ghi chú |
|---|---|---|
| 1 Server + 3 Client | | |
| Position formula A/B/C | | |
| Table columns/status | | |
| Room map/zoom/fit | | |
| Card opens selected map | | |
| Client move update | | |
| Offline/reconnect | | |
| Restart/persistence | | |
| Manual measurement | | |
| Monitoring/Alerts/File Transfer | | |
| Ansible controlled host | | |
| Wireless separation | | |

Không đánh dấu GUI desktop PASS nếu chưa quan sát trực tiếp từng bước trên interactive desktop.
