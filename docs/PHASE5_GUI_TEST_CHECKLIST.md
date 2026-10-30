# Phase 5 GUI smoke-test checklist

Tài liệu này dành cho kiểm thử thủ công trên Windows với **1 Server và 3 Client**. Các bước dưới đây chưa được xem là PASS cho tới khi người kiểm thử điền kết quả trên máy có desktop. Không tạo tải CPU/GPU/Disk nguy hiểm; hãy hạ ngưỡng rule theo giá trị telemetry đang có.

## Kết quả tự động đã thực thi ngày 26/10/2026

- `ant release-phase5`: **PASS** bằng Ant 1.10.14/NetBeans 22/JDK 17.0.20.1.
- JAR/manifest/7 dependency trong `dist/lib`: **PASS**.
- Package-level test bằng class trong JAR: một Server + ba Client TCP, Dashboard headless, alert/restart/persistence: **PASS**.
- SHA-256 JAR: `2F3334A210A31E22540B5DB90FAEE4F21CE3B23B8A5B97893BC70C492E19AAF6`.
- Desktop interaction: **CHƯA KIỂM TRA**. Server JAR đã tạo process nhưng phiên công cụ không truy cập được interactive window station (`MainWindowHandle=0`, screen capture trả `The handle is invalid`), nên không đánh dấu các checkbox bên dưới.

## 1. Chuẩn bị release candidate

- [ ] Cài JDK 17+ và Apache Ant, hoặc mở project bằng NetBeans 22+ với JDK 17.
- [ ] Xác nhận 7 JAR trong `lib/`: JFreeChart, JNA/JNA Platform, OSHI common/core và SLF4J API/NOP.
- [ ] Chạy `ant release-phase5`. Target này thực hiện clean, toàn bộ test Giai đoạn 1–5, rồi mới tạo JAR.
- [ ] Xác nhận `dist/LANMonitoringServer.jar` vừa được tạo, không dùng file cũ ngày 13/10/2026.
- [ ] Xác nhận `dist/lib` chứa đủ 7 dependency và manifest có `Main-Class: lanmonitoringserver.LANMonitoringServer` cùng `Class-Path` trỏ tới `lib/...`.
- [ ] Chạy `java -jar dist/LANMonitoringServer.jar --server`; cửa sổ Server phải mở và không có `ClassNotFoundException`.

Nếu dùng NetBeans: **Clean and Build**, sau đó chạy `ant test` trong cửa sổ terminal/Ant hoặc chạy target `test-phase5`. Không xem thao tác Run trên class output là bằng chứng cho JAR phân phối.

## 2. Tạo ba identity Client độc lập

Ba Client trên cùng một tài khoản Windows sẽ dùng chung Java Preferences nếu chạy bình thường. Dùng ba thư mục preferences riêng để tránh cả ba phiên dùng cùng Client ID:

```powershell
java "-Djava.util.prefs.userRoot=$PWD\smoke\client1" -jar dist\LANMonitoringServer.jar --client
java "-Djava.util.prefs.userRoot=$PWD\smoke\client2" -jar dist\LANMonitoringServer.jar --client
java "-Djava.util.prefs.userRoot=$PWD\smoke\client3" -jar dist\LANMonitoringServer.jar --client
```

Có thể chạy trên ba máy Windows/Linux thật thay cho ba profile. Với Server, nên cô lập file kiểm thử bằng JVM option trước `-jar`:

```powershell
java "-Dlanmonitoring.alert.store=$PWD\smoke\alerts-v1.json" -jar dist\LANMonitoringServer.jar --server
```

- [ ] Start Server trên một cổng còn trống, ví dụ `5000`.
- [ ] Kết nối lần lượt ba Client; cả ba Card có Client ID khác nhau và trạng thái Online.
- [ ] Mở chi tiết từng Client; Overview, System Information và Performance tiếp tục cập nhật.
- [ ] Đợi ít nhất 15 giây để xác nhận heartbeat, telemetry, RTT và card không bị nhân đôi.

## 3. Current Alerts, popup và delivery ACK

Thực hiện lần lượt trên từng Client để biết alert được định tuyến đúng. Dùng **Disk Capacity Usage** vì có thể kích hoạt an toàn bằng cách đặt threshold thấp hơn giá trị đang hiển thị; không dùng Disk Capacity thay cho Disk Active Time.

- [ ] Mở **Alerts** trên Server, chọn rule `DISK_CAPACITY`.
- [ ] Đặt Warning thấp hơn capacity hiện tại 1–2 điểm, Critical cao hơn giá trị hiện tại, Duration `5 s`, Hysteresis `5%`, Cooldown `60 s`, rồi Apply.
- [ ] Trước đủ 5 giây không có alert; sau khi vượt liên tục đủ thời gian xuất hiện đúng một WARNING.
- [ ] Chỉ Client gây cảnh báo có popup; hai Client còn lại không có popup.
- [ ] Popup là non-modal, tự đóng sau khoảng 15 giây; vẫn kéo cửa sổ, đổi tab và Disconnect được trong khi popup mở.
- [ ] Sample WARNING tiếp theo không sinh popup lặp.
- [ ] Cột Delivery chuyển tới `ACKNOWLEDGED`; cột Status vẫn là `ACTIVE`. Đây là `ALERT_ACK` giao nhận, không phải Admin Acknowledge.
- [ ] Card đúng Client có badge WARNING và số alert; Card khác không đổi do alert này.

## 4. Escalation, Acknowledge, Resolve và hysteresis

- [ ] Hạ Critical xuống dưới capacity hiện tại; cùng alert ID chuyển WARNING → CRITICAL và chỉ có thêm một popup CRITICAL.
- [ ] Telemetry CRITICAL tiếp theo không tạo alert ID mới và không spam popup.
- [ ] Card chuyển CRITICAL.
- [ ] Chọn alert rồi nhấn **Acknowledge**: Status thành `ACKNOWLEDGED`, alert vẫn nằm trong Current Alerts và Card vẫn có badge.
- [ ] Nhấn **Resolve**: alert rời Current Alerts, xuất hiện trong Alert History, Card bỏ badge nếu không còn alert mở.
- [ ] Trong thời gian cooldown, tiếp tục vượt ngưỡng không tạo alert mới.
- [ ] Sau cooldown + duration, một lifecycle mới có alert ID mới được phép xuất hiện.

Kiểm tra recovery tự động an toàn: khi alert đang mở, tăng Warning cao hơn giá trị hiện tại ít nhất `hysteresis + 1`; sample kế tiếp phải Resolve. Không làm đầy ổ đĩa và không chạy stress tool.

## 5. STALE/UNSUPPORTED và mất kết nối

- [ ] Khi một alert đang mở, Disconnect đúng Client. Alert không tự Resolve và vẫn còn trong Current Alerts.
- [ ] Sau khi reconnect cùng profile, Client giữ cùng ID; alert/history không bị nhân đôi.
- [ ] Với máy không hỗ trợ GPU hoặc Disk Active Time, metric hiển thị `N/A/UNSUPPORTED` và không tạo alert cho metric đó.
- [ ] Dừng Client đủ lâu để telemetry stale; alert đang mở không tự Resolve chỉ vì thiếu dữ liệu.

## 6. Bộ lọc và lịch sử sau restart

- [ ] Lọc Current Alerts/History theo Client ID hoặc hostname.
- [ ] Lọc theo từng resource, WARNING/CRITICAL, 24 HOURS và 7 DAYS; Current và History đều phản ánh bộ lọc.
- [ ] Đóng cửa sổ Alerts rồi mở lại; rule vừa Apply vẫn hiển thị đúng.
- [ ] Stop Server bình thường, khởi động lại với cùng `lanmonitoring.alert.store`.
- [ ] Alert History và rule được nạp lại; alert đang mở vẫn mở, delivery/admin status không bị trộn.
- [ ] File JSON tồn tại, đọc được, và không có file `.tmp` bị dùng thay cho file chính.

## 7. Persistence failure smoke test

Sao lưu file trước khi thử. Không sửa file dữ liệu thật đang cần giữ.

- [ ] Chạy Server với path chưa tồn tại nhưng parent ghi được: store được tạo sau thay đổi rule/alert.
- [ ] Chạy với một bản sao JSON cố ý sai cú pháp: log báo load failure và persistence bị vô hiệu cho phiên đó; file sai không bị ghi đè.
- [ ] Chạy với path không ghi được bằng tài khoản thường: UI/network vẫn hoạt động trong bộ nhớ, log báo save failure; không chạy Administrator chỉ để làm test PASS.

## 8. EDT và regression quan sát được

- [ ] Trong lúc 3 Client gửi telemetry, liên tục mở/đóng Alerts và Client Detail; UI không treo, không có exception EDT trong log/console.
- [ ] Biểu đồ Performance tiếp tục chạy khi alert xuất hiện và khi popup đang mở.
- [ ] Resize/HiDPI: bảng, filter, rule editor và nút Acknowledge/Resolve vẫn nhìn thấy và thao tác được.
- [ ] Stop/Start Server, Disconnect/Reconnect Client nhiều lần; không có socket/thread exception chưa xử lý.

## 9. Biên bản kết quả

Ghi: ngày/giờ, Windows/JDK/Ant/NetBeans version, commit hoặc trạng thái source, hostname/Client ID của ba Client, đường dẫn JAR, hash SHA-256 của JAR, từng mục PASS/FAIL và ảnh/log cho lỗi. Chỉ chuyển sang Giai đoạn 6 khi build release, JAR smoke và toàn bộ mục trọng yếu phía trên PASS.
