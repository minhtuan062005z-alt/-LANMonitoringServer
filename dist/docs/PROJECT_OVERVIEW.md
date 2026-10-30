# Tổng quan đề tài LANMonitoringServer

LANMonitoringServer là ứng dụng Java Swing Client–Server phục vụ giám sát và quản trị tập trung các máy trong mạng LAN. Đồ án thể hiện ba nhóm kiến thức chính:

1. Lập trình mạng TCP đa Client với framing, xác thực, heartbeat và reconnect.
2. Giám sát tài nguyên, cảnh báo và quản trị file có phân quyền.
3. Tự động hóa đồng bộ cấu hình bằng Ansible cho Windows/Linux.

## Phạm vi phiên bản bàn giao

- Dashboard nhiều Client với Online/Offline, System Information và telemetry realtime.
- CPU, RAM, GPU, Disk Active Time, Disk Capacity, throughput disk/network và biểu đồ giới hạn lịch sử.
- Alert WARNING/CRITICAL với duration, hysteresis, cooldown, ACK giao nhận và Acknowledge/Resolve quản trị.
- File Transfer Server → nhiều Client qua TLS, streaming chunk, SHA-256, progress, RBAC và audit.
- Ansible orchestration có allowlist để đồng bộ `lan_monitoring_sync.txt` qua SSH/WinRM/PSRP phù hợp.
- Nghiên cứu Wi-Fi: RSSI/Link Quality, RTT mạng riêng biệt và Estimated Client-to-AP Distance sau calibration.
- Multi-AP Radio Map/weighted KNN để ước lượng tọa độ Client; chỉ estimate `OK` còn hạn mới được dùng
  cho Server–Client position-based distance. Dữ liệu stale chỉ còn lịch sử.

## Nguyên tắc an toàn

- Không tự thực thi file nhận được và không cung cấp remote shell.
- Không vượt quyền Windows/Linux, không tự bật SSH/WinRM hoặc tắt firewall.
- File Transfer và cập nhật permission fail-closed khi session không dùng TLS.
- Không hardcode secrets/private key; Ansible credentials phải ở Ansible Vault ngoài Git.
- Không chuyển Signal % hoặc TCP RTT thành khoảng cách vật lý.

## Công nghệ

- Java 17, Swing/AWT, Apache Ant/NetBeans.
- TCP length-prefixed UTF-8 JSON; TLS 1.3 khi được cấu hình.
- OSHI/JNA, JFreeChart và SLF4J NOP binding.
- Công cụ OS có timeout: PowerShell/CIM, `nvidia-smi`, `iw`, `netsh`, `lsblk`, `lspci`.
- Ansible chạy ở Linux Control Node hoặc WSL2, không cài agent Ansible trên Client.
