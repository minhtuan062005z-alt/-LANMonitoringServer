# Phase 7 real-host validation checklist

Automated test không thay thế kiểm thử Control Node và managed host thật.

## Control Node

- [ ] Linux Control Node hoặc WSL2 có `ansible-playbook --version` và collection `ansible.windows`.
- [ ] `sync_linux.yml` và `sync_windows.yml` PASS `ansible-playbook --syntax-check`.
- [ ] Vault và vault-password nằm ngoài Git, chỉ tài khoản chạy Server đọc được.
- [ ] Host key SSH/certificate WinRM đã được xác minh; không dùng trust bypass hay unencrypted transport.

## Inventory và UI

- [ ] Tab **Ansible Management** chỉ hiển thị allowlist; Client Java Online không tự xuất hiện.
- [ ] Host `enabled=false` không thể deploy.
- [ ] VIEWER/OPERATOR/CLIENT bị service từ chối; chỉ ADMIN chạy job.
- [ ] Generate File giữ đúng UTF-8 và SHA-256 hiển thị khớp công cụ ngoài.
- [ ] UI vẫn phản hồi khi job chạy; log/progress cập nhật trên EDT; Cancel không treo cửa sổ.

## Linux và Windows thật

- [ ] Một Linux host nhận đúng file/path/content/checksum.
- [ ] Một Windows host nhận đúng file/path/content/checksum.
- [ ] Tổng cộng tối thiểu ba host chạy đồng thời nếu lab có đủ thiết bị.
- [ ] Deploy lần hai với nội dung không đổi trả `changed=false`.
- [ ] Sau khi đổi nội dung, deploy trả `changed=true` và SHA-256 mới.
- [ ] Verify-only không sửa file và trả đúng kết quả từng host.
- [ ] Host Ansible offline trả `UNREACHABLE`, không bị nhầm với Java Client Offline.
- [ ] Tài khoản không đủ quyền trả `FAILED`; các host khác vẫn có kết quả riêng.
- [ ] Timeout và Cancel kết thúc process/job, không ảnh hưởng heartbeat, telemetry, alert hoặc Java File Transfer.
- [ ] Không có file nào tự thực thi và không có thao tác ngoài `lan_sync_dest` đã cấu hình.

## Bằng chứng demo

Ghi OS/Ansible/JDK version, transport, alias host, destination, source/remote SHA-256, kết quả lần một/lần hai, ảnh UI và log đã redacted. Không lưu password/token/private key trong ảnh hoặc báo cáo.
