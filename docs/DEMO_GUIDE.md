# Hướng dẫn demo đồ án

## Chuẩn bị

1. Build bằng `release-phase9`; không dùng JAR cũ.
2. Chuẩn bị Server, hai Windows Client và một Linux Client nếu có.
3. Dùng TLS cho phần File Transfer/permission.
4. Chuẩn bị file TXT, PNG và binary nhỏ, không phải executable.
5. Chỉ bật Ansible cho host trong allowlist và credential Vault đã kiểm tra.
6. Ghi rõ nếu demo trên cùng máy/VM thay vì ba thiết bị vật lý.

## Kịch bản trình bày đề xuất

1. Khởi động Server và ba Client; chỉ ra Client ID ổn định, Online/Offline và reconnect.
2. Mở từng Card: Overview, System Information, Performance và Network.
3. Quan sát CPU/RAM/Disk/Network; giải thích `UNSUPPORTED/STALE` thay vì tạo số giả.
4. Dùng workload an toàn thông thường để vượt threshold trong lab hoặc trình bày automated alert test; không gây tải nguy hiểm.
5. Phân biệt Client `ALERT_ACK` với Admin Acknowledge; sau đó Resolve.
6. Cấp quyền nhận file cho đúng Client, gửi TXT/PNG/binary qua TLS và xem SHA-256/progress từng Client.
7. Đăng nhập bằng VIEWER/OPERATOR cấu hình riêng để minh họa service-layer RBAC nếu môi trường demo cho phép.
8. Generate nội dung Ansible, chọn allowlisted hosts, Deploy và chạy lại để trình bày idempotency; nếu không có Control Node thật, công bố rõ đây chưa phải real-host demo.
9. Bật Wi-Fi measurement: chỉ ra Signal %, RSSI dBm nếu OS hỗ trợ, RTT riêng biệt và calibration Client–AP. Không gọi kết quả là Actual Distance.
10. Dừng Server hoặc ngắt một Client, xác minh Offline/reconnect và lịch sử alert sau restart.

## Tiêu chí demo thành công

- UI không treo trong các thao tác network dài.
- Telemetry/heartbeat tiếp tục khi truyền file.
- Cảnh báo/file/message chỉ đến đúng Client.
- Lỗi quyền, TLS, offline hoặc unsupported có thông báo rõ.
- Không xuất hiện secret/private key/password trong UI, log hoặc package.
