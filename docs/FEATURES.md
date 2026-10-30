# Danh sách tính năng và trạng thái

| Chức năng | Trạng thái | Mức xác minh |
|---|---|---|
| TCP framing, authentication, reconnect | Hoàn thành | Automated loopback integration |
| Dashboard nhiều Client | Hoàn thành | Headless Swing + 3 local Clients |
| System Information Windows/Linux | Hoàn thành có fallback | Parser tests; Windows CIM bị policy chặn; Linux runtime chưa chạy |
| Realtime CPU/RAM/Disk/Network/GPU | Hoàn thành | Windows runtime + automated tests; Linux runtime chưa chạy |
| Alert engine/delivery/persistence | Hoàn thành | Automated lifecycle/network/persistence |
| File Transfer nhiều Client | Hoàn thành | TLS integration, TXT/PNG/binary, negative paths |
| RBAC/Client permissions/audit | Hoàn thành | Service-layer security tests |
| Ansible orchestration/UI/playbooks | Hoàn thành source/integration | Controlled child process; chưa deploy host thật |
| Wi-Fi monitoring | Hoàn thành theo capability | Windows fixtures/user observation; Linux hardware chưa chạy |
| Estimated Client–AP Distance | Hoàn thành dạng nghiên cứu | Model/calibration tests; chưa đo accuracy thực địa |
| Multi-AP Wi-Fi Fingerprinting/Radio Map | Hoàn thành source/regression | Weighted KNN, persistence, stale/disconnect/reconnect và headless GUI; chưa field-validate accuracy |
| Estimated Server–Client Distance từ fingerprint | Hoàn thành dạng nghiên cứu | Chỉ `OK` còn hạn; `STALE/UNSUPPORTED/NOT_AVAILABLE` không tạo realtime metres |
| Wi-Fi RTT/FTM provider | Chưa hỗ trợ | Extension interface trả `UNSUPPORTED` |
| Resume file giữa session | Chưa hỗ trợ | Retry tạo transfer mới |
| mTLS/multi-user login | Chưa hỗ trợ | Server cert + HMAC Client identity hiện tại |

“Hoàn thành” không thay thế checklist desktop/đa thiết bị khi cột xác minh chưa có thiết bị thật.
