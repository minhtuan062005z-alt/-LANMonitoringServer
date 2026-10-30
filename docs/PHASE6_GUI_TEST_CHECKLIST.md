# Phase 6 desktop GUI checklist

Automated TLS/TCP tests đã PASS. Checklist này xác minh phần trực quan trên một Windows desktop với 1 Server + 3 Client; không đánh dấu PASS nếu chỉ chạy headless.

## Trạng thái validation tự động — 2026-10-27

- [x] `release-phase6` clean build: 89 source, 10 test source, toàn bộ test Giai đoạn 1–6 PASS.
- [x] JAR mới và 7 dependency `dist/lib` đã được tạo/kiểm tra; SHA-256 JAR: `A44454CE55887070CCDC8C2750C39547708C1032B3CE41364703A713F368FF54`.
- [x] TLS socket tests: certificate không được tin cậy và hostname/IP sai SAN đều bị từ chối.
- [x] Automated 1 Server + 3 Client qua TLS: TXT/PNG/binary, multi-client, SHA-256, Cancel, timeout, disconnect, permission và telemetry regression PASS.
- [x] Path traversal, symbolic-link receive root, size/free-space, duplicate name, checksum lỗi và no-auto-execute PASS.
- [x] Service/network RBAC cho ADMIN/OPERATOR/VIEWER/CLIENT, audit và permission persistence/rollback PASS.
- [ ] Desktop GUI tương tác thực tế: **CHƯA THỰC HIỆN** vì phiên công cụ không có interactive window station. Toàn bộ mục bên dưới vẫn là manual gate.

Kết quả tự động không thay thế việc quan sát dialog, progress repaint, file chooser, Cancel và Received Files trên desktop thật.

## Chuẩn bị

- [x] Chạy `ant release-phase6`; kiểm tra JAR mới và đủ `dist/lib` (đã thực hiện tự động ngày 2026-10-27).
- [ ] Tạo Server PKCS12 và Client truststore theo README; SAN phải chứa hostname/IP dùng để Client kết nối.
- [ ] Chạy Server với TLS và role `ADMIN`; chạy ba Client bằng ba Java Preferences profile riêng và cùng truststore.
- [ ] Start Server, kết nối ba Client và xác nhận log Server ghi `TLS 1.3 transport enabled`.

## Permission và RBAC

- [ ] Mở **File Transfer**, chọn từng Client và bật `Receive files`; Apply rồi Load lại phải giữ đúng giá trị.
- [ ] Tắt `Export system info`: Server không nhận inventory mới và Client không bị disconnect.
- [ ] Tắt `Admin alerts`: Server không gửi message/alert quản trị tới Client đó.
- [ ] Chạy Server role `VIEWER`: Send và Apply permissions bị khóa/từ chối.
- [ ] Chạy role `OPERATOR` với danh sách ID được cấp: chỉ Client trong scope nhận file; Client khác bị từ chối.
- [ ] Xác nhận Client không có control để tự nâng quyền và audit JSONL ghi các thao tác thành công/thất bại.

## Truyền file

- [ ] Chọn file text UTF-8 và một Client: progress, byte, tốc độ, `VERIFYING → SUCCESS`, path và checksum hiển thị đúng.
- [ ] Chọn PNG và ba Client: ba dòng progress/kết quả độc lập; cả ba file có SHA-256 giống source.
- [ ] Gửi file binary và mở bằng công cụ hash, không dựa vào extension.
- [ ] Gửi lại cùng tên: Client tạo `name (1).ext`, không overwrite file trước.
- [ ] Nhấn Cancel khi đang gửi file đủ lớn: trạng thái `CANCELLED`, không có file đích/`.part` còn lại.
- [ ] Disconnect một Client khi đang truyền: Client đó `FAILED`, Client khác vẫn tiếp tục và telemetry/heartbeat vẫn cập nhật.
- [ ] Chọn Client Offline hoặc tắt `Receive files`: kết quả bị từ chối rõ ràng, Client khác không bị ảnh hưởng.
- [ ] Thử file vượt `lanmonitoring.file.max.bytes`: Server từ chối trước khi gửi.

## Bảo mật và thư mục

- [ ] Chạy không cấu hình TLS: monitoring cũ có thể kết nối nhưng Send File báo `TLS required`.
- [ ] Dùng truststore sai hoặc hostname không khớp SAN: TLS connection thất bại, không có tùy chọn bỏ xác minh.
- [ ] Thư mục nhận nằm đúng `lanmonitoring.receive.dir`; file không thoát khỏi thư mục và không tự chạy.
- [ ] Không có private key Server trên Client; mật khẩu keystore không ghi vào source/audit log.

## Kết quả

Ghi Windows/JDK/Ant version, Client IDs, cấu hình role/scope, hash JAR, hash từng file, PASS/FAIL, ảnh UI và log liên quan. Chỉ chuyển Giai đoạn 7 sau khi các mục trọng yếu PASS.
