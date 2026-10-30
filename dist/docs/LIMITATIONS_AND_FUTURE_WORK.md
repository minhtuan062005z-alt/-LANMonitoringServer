# Giới hạn và hướng phát triển

## Giới hạn đã biết

- TLS được hỗ trợ nhưng monitoring compatibility mode vẫn plaintext khi chưa cấu hình. File Transfer/permission đã fail-closed.
- Authentication hiện là Server certificate + HMAC Client secret, chưa phải mTLS hoặc PKI lifecycle đầy đủ.
- Server dùng blocking socket với worker pool tối đa 32; phù hợp demo/lab, chưa benchmark quy mô lớn.
- Client/alert history được giữ để phục vụ UI; transfer/chart/log/PING đã bounded, nhưng alert persistence dài hạn chưa có retention/archival policy.
- File Transfer chưa resume giữa session và history transfer không persist qua Server restart.
- Windows CIM có thể bị policy từ chối; Linux runtime chưa được xác minh trong môi trường Codex.
- Ansible chưa chạy trên Windows/Linux managed host thật trong phiên công cụ; WSL service trước đó bị `E_ACCESSDENIED`.
- Wi-Fi RSSI liên kết đơn phản ánh Client–AP; calibration được persist theo Client/AP hash. Multi-AP
  Radio Map cũng persist theo stable Client ID, nhưng accuracy fingerprint thực địa chưa được đo và
  FTM/direct-ranging provider chưa có.
- Automated Swing tests là headless; chưa thay thế kiểm thử desktop/HiDPI/accessibility.
- Test runner dùng `main` + assertions, chưa xuất JUnit XML/CI dashboard.

## Hướng phát triển

1. Bắt buộc TLS cho toàn bộ protocol, bổ sung mTLS, revoke/rotation và secret storage của OS.
2. CI matrix Windows/Linux với Java 17, JUnit/report và artifact checksum signing.
3. Alert retention/archival, transfer-history persistence và structured operational logging.
4. Load/soak test, backpressure và cân nhắc NIO khi số Client mục tiêu vượt worker pool hiện tại.
5. Accessibility/i18n, theme thống nhất và desktop installer/jpackage.
6. Real-host Ansible lab với certificate/SSH host-key lifecycle.
7. Field-validation Wi-Fi fingerprint (MAE/median/p95/dropout) và provider FTM thật chỉ khi phần cứng/API/AP đáp ứng.
