# Checklist kiểm thử thực địa Wi-Fi Fingerprinting trên Windows

Baseline: `phase9-wifi-fingerprint-6` — 1 Server, từ 1 đến nhiều Windows Client vật lý.

Checklist này chỉ ghi nhận kết quả quan sát từ phần cứng thật. Không dùng fixture, RSSI giả, tọa độ
ví dụ hoặc sample trong repository để tuyên bố accuracy.

## 1. Defect/rủi ro phải biết trước khi test

- **QA-FP-01 — một scan có thể bị dùng lại:** backend kiểm scan `OK`, còn TTL và có ít nhất 3 AP,
  nhưng chưa yêu cầu Client còn Online và chưa đánh dấu một `WifiScanSnapshot.timestamp` là đã dùng
  cho survey. GUI vì vậy có thể lưu recent scan của Client vừa Offline hoặc cùng một vector RSSI
  nhiều lần tại các tọa độ khác nhau. Không làm vậy: chỉ survey Client Online; mỗi giá trị `Updated`
  chỉ được dùng cho một lần `Add Survey Sample`; sau khi di chuyển phải chờ timestamp mới.
- Một lần bấm Add lưu snapshot tức thời, chưa average/outlier-rejection. Phải lấy nhiều mẫu thật,
  ở nhiều thời điểm/hướng đặt máy; tối thiểu 3 tọa độ khác nhau chỉ là điều kiện chạy KNN, không phải
  điều kiện đủ để đạt accuracy.
- Radio Map scope theo stable Client ID. Mỗi Client/adapter phải khảo sát riêng; không suy diễn rằng
  Radio Map của máy A hợp lệ cho máy B.

## 2. Phiếu môi trường

| Mục | Giá trị thực tế |
|---|---|
| Ngày/giờ, người test | |
| JAR path, SHA-256 | |
| Release marker trong log | |
| Server hostname/IP, Windows/JDK | |
| Client ID / hostname từng Client | |
| Model Wi-Fi adapter + driver từng Client | |
| AP model, band/channel, vị trí AP | |
| Sơ đồ phòng/reference frame | |
| Vị trí Server đo thật `(x,y,z)` | |
| Fingerprint interval | |
| Điều kiện người/tường/cửa/nhiễu | |

## 3. Chuẩn bị và khởi chạy

- [ ] Dùng JAR `phase9-wifi-fingerprint-6`; Server và mọi Client dùng cùng bản release.
- [ ] Tạo PKCS12 Server và Client truststore; SAN khớp đúng hostname/IP Client dùng để kết nối.
- [ ] Chạy Server bằng role ADMIN và TLS theo README; log có `TLS 1.3 transport enabled`.
- [ ] Chạy từng Client với truststore. Không chạy nhiều instance chung một Windows profile vì có thể
      dùng chung Client ID; ghi lại stable Client ID hiển thị trên GUI.
- [ ] Trên Server nhập port rồi bấm **Khởi động**.
- [ ] Trên Client nhập IPv4 hiện tại của Server và port. TLS phải xác minh stable identity riêng, không xác minh IPv4 động.
- [ ] Log Client có `release=phase9-wifi-fingerprint-6`, `WIFI_FINGERPRINT_V1=true` và trạng thái
      authenticated/connected. Nếu capability là `false`, dừng: session chưa đáp ứng TLS mặc định.
- [ ] Windows Location consent/WLAN service cho phép Native Wi-Fi scan; không chạy elevated chỉ để
      che lỗi quyền và không tắt firewall/certificate validation.

## 4. Probe và bật Multi-AP Scanner trên từng Client

Thực hiện đúng menu **Wi-Fi Research**:

- [ ] Chọn **Test multi-AP scanner now**; log báo `status=OK`, provider Windows Native Wi-Fi và
      `APs with real RSSI >= 3`.
- [ ] Nếu `UNSUPPORTED`/Access Denied/no AP: ghi nguyên văn lỗi, kiểm Location consent/adapter/driver;
      không đổi Signal % thành RSSI và không tiếp tục tạo Radio Map.
- [ ] Tick **Enable multi-AP fingerprint scans**. Đây là scheduler fingerprint cần dùng.
- [ ] Không nhầm với **Enable Wi-Fi measurement** hoặc **Test Wi-Fi collector now**; hai mục đó chỉ
      phục vụ connected-AP/Client-to-AP Phase 8 và không tạo multi-AP Radio Map.
- [ ] Log báo multi-AP scan bắt đầu theo interval cấu hình; Server nhận `WIFI_SCAN` với hashed AP count.

## 5. Đặt Server Position

- [ ] Trên Server, click vào một **Client Card** (không bấm checkbox và không bấm Open Room Map).
- [ ] Trong cửa sổ **Chi tiết Client**, chọn tab **Server-Client Distance**.
- [ ] Bấm **Set Server Position**; nhập `Reference point / room map`, X, Y và Z tùy chọn từ phép đo
      thật. Dùng duy nhất một reference string chính xác cho toàn bộ survey, ví dụ `LAB-A-2026`.
- [ ] Không bấm **Clear Server Position** trong suốt bài test.

Server Position là dữ liệu dùng chung; chỉ cần đặt một lần. Không có Server Position thì KNN vẫn có
thể hiện Estimated Position, nhưng không thể tạo Server–Client Distance hoặc đường trên Room Map.

## 6. Thu thập Radio Map cho từng Client

- [ ] Trên Dashboard Server bấm **Distance Map**; chọn tab **Wi-Fi Radio Map Survey**.
- [ ] Trong bảng **Latest Client Scans / Position Estimates**, chọn đúng Client ID đang khảo sát.
- [ ] Đặt Client tại tọa độ đã đo, giữ nguyên vị trí/hướng; chờ `Scan Status=OK`, APs >= 3 và cột
      `Updated` đổi sang timestamp được thu tại vị trí đó.
- [ ] Ghi timestamp vào phiếu; xác nhận timestamp này chưa từng được dùng cho sample trước.
- [ ] Bấm **Add Survey Sample**; nhập đúng `Reference point / room`, `Measured X (m)`,
      `Measured Y (m)`, và `Measured Z (optional, m)`.
- [ ] Xác nhận sample mới xuất hiện trong bảng **Surveyed Radio Map**, đúng Client Scope/reference/APs.
- [ ] Chỉ sau khi đã lưu mới di chuyển tới điểm tiếp theo; chờ timestamp `Updated` mới trước lần Add kế.
- [ ] Có ít nhất 3 tọa độ khác nhau trong cùng reference và mỗi query/sample có ít nhất 3 AP hash
      giao nhau. Khuyến nghị grid 1–2 m, >= 6–10 điểm, nhiều sample thật mỗi điểm và nhiều orientation.
- [ ] Lặp toàn bộ survey riêng cho từng stable Client ID/adapter.

Không dùng các điểm validation/holdout dưới đây làm survey cho tới khi hoàn tất đánh giá sai số.

## 7. Điều kiện để distance và Room Map tự cập nhật

Tất cả điều kiện sau phải đồng thời đúng:

- [ ] Client Online qua session có `WIFI_FINGERPRINT_V1`; periodic multi-AP scan đang bật.
- [ ] Scan mới `OK`, còn TTL (`max(30 s, 3 × interval)`) và có >= 3 RSSI AP thật.
- [ ] Radio Map của đúng Client ID có >= 3 tọa độ khác nhau trong cùng reference; ít nhất 3 candidate
      neighbor cùng có >= 3 AP giao với query.
- [ ] Weighted KNN trả `Position Status=OK`; không phải `STALE`, `UNSUPPORTED` hay `NOT_AVAILABLE`.
- [ ] Server Position tồn tại và có reference trùng chính xác với reference của estimate KNN.
- [ ] Client không còn manual distance hoặc ADMIN Client Position override nếu mục tiêu là test
      fingerprint làm nguồn distance.

Khi đủ điều kiện:

- [ ] Radio Map Survey hiển thị Estimated Position, confidence/error và `Position Status=OK`.
- [ ] Client Card hiển thị distance với nguồn **Wi-Fi Fingerprint Estimate**.
- [ ] Card → tab **Server-Client Distance** hiển thị method `POSITION_BASED_ESTIMATION`, source
      `WIFI_FINGERPRINT`, status `OK`.
- [ ] Dashboard → **Distance Map** → **Distance & Room Map** hiển thị dòng Client và điểm/đường có
      nhãn `Wi-Fi fingerprint estimate`.

## 8. ADMIN/manual override

Thứ tự ưu tiên thực tế: **Manual distance → ADMIN Client Position → Wi-Fi Fingerprint → unavailable**.

- [ ] Nếu đã dùng **Enter Measured Distance**, bảng/Card phải tiếp tục dùng Manual; fingerprint chỉ
      cập nhật trong tab survey, không thay giá trị manual.
- [ ] Nếu đã dùng **Edit Client Position**, distance/map phải dùng `ADMIN_COORDINATES`; fingerprint
      không ghi đè tọa độ này.
- [ ] Muốn chuyển Client sang kiểm thử fingerprint: Card → tab **Server-Client Distance** →
      **Clear Distance** và xác nhận. Thao tác này xóa cả manual distance và ADMIN Client Position của
      riêng Client, nhưng không xóa Server Position hoặc Radio Map.
- [ ] Không bấm **Clear Server Position**; nếu bấm, mọi distance dựa trên tọa độ sẽ unavailable.

## 9. Realtime holdout và đánh giá sai số

Tại mỗi điểm holdout chưa có trong Radio Map:

- [ ] Đo/ghi ground-truth X/Y(/Z) và khoảng cách Euclidean thật từ Server Position trong cùng frame.
- [ ] Đặt Client ổn định; chờ ít nhất một scan mới, ghi timestamp/AP count/status/confidence/error.
- [ ] Ghi Estimated X/Y(/Z) và Server–Client distance khi status `OK`.
- [ ] Tính `position error = sqrt((x_est-x_true)^2 + (y_est-y_true)^2)`; chỉ thêm Z nếu cả ground
      truth và estimate đều có Z hợp lệ.
- [ ] Tính `distance absolute error = abs(d_est-d_true)`.
- [ ] Lặp nhiều lần/điểm, nhiều orientation và điều kiện LOS/NLOS; không chỉ đo tại survey point.

| Client | Holdout | Time | True X,Y | Est. X,Y | Position error (m) | True distance (m) | Est. distance (m) | Abs. error (m) | Status/confidence/APs | Điều kiện |
|---|---|---|---|---|---:|---:|---:|---:|---|---|
| | | | | | | | | | | |

Tổng hợp riêng từng Client và toàn bộ lab: số lần thử, số `OK`, dropout rate, MAE, median, p95 và max.
Không tính `STALE/UNSUPPORTED/NOT_AVAILABLE` như error bằng 0; ghi chúng là dropout/status riêng.

## 10. Stale, disconnect/reconnect và persistence

- [ ] Bỏ tick **Enable multi-AP fingerprint scans**: sau TTL, Position/Distance thành `STALE`, không
      còn metres realtime và không còn điểm fingerprint trên Room Map; tọa độ cuối chỉ hiện `HISTORICAL`.
- [ ] Tick lại: scan có timestamp mới đưa estimate về `OK`; không dùng lại scan cũ.
- [ ] Bấm **NGẮT KẾT NỐI**: fingerprint distance chuyển ngay `STALE`; Client khác vẫn cập nhật.
- [ ] Bấm **KẾT NỐI** lại cùng profile: không tạo Card/Client ID mới; chỉ scan mới hơn mới phục hồi `OK`.
- [ ] Tắt/bật Server: Radio Map và Server Position được load lại; estimate chưa realtime cho tới khi
      Client reconnect/gửi scan mới. Không có sample bị gán sang Client ID khác.
- [ ] Chọn một sample và thử **Remove Selected Sample**; xác nhận đúng sample bị xóa, KNN recalculates,
      và các Client/ADMIN/manual record khác không bị thay đổi.

## 11. Kết luận bắt buộc

| Hạng mục | PASS / FAIL / NOT RUN | Evidence |
|---|---|---|
| TLS/capability/multi-AP scanner từng Client | | |
| Radio Map đúng Client ID/timestamp/reference | | |
| KNN realtime tại holdout | | |
| Distance/Room Map chỉ dùng status OK | | |
| Manual/ADMIN priority và Clear Distance | | |
| TTL stale | | |
| Disconnect/reconnect stable identity | | |
| Persistence/restart | | |
| MAE/median/p95/max/dropout | | |

Chỉ tuyên bố accuracy sau khi bảng holdout có ground truth đo thật và thống kê đầy đủ. Automated
regression/headless PASS không thay thế checklist này.
