# Phase 9 Multi-device and Desktop Checklist

Môi trường mục tiêu: 1 Java Server, 2 Windows Client, 1 Linux Client. Ghi rõ physical/VM và phiên bản OS/JDK/adapter.

## Environment

| Node | Physical/VM | OS | JDK | Network | Result |
|---|---|---|---|---|---|
| Server | | | | | NOT RUN |
| Windows Client 1 | | | | | NOT RUN |
| Windows Client 2 | | | | | NOT RUN |
| Linux Client | | | | | NOT RUN |

## Integration

- [ ] Ba Client authenticate và Online đồng thời.
- [ ] Kill/restart một Client; cùng Client ID reconnect vào Card cũ.
- [ ] Rút mạng/tắt VM đột ngột; Server chuyển Offline đúng hạn và không treo.
- [ ] Restart Server; Client tự reconnect và alert history/rules load lại.
- [ ] System Information đúng trên từng OS; trường thiếu ghi reason.
- [ ] Telemetry/Performance chạy tối thiểu 15 phút, chart giữ cửa sổ/bộ nhớ bounded.
- [ ] Alert WARNING/CRITICAL, delivery ACK, Admin Acknowledge/Resolve đúng Client.
- [ ] Gửi TXT, PNG, binary tới ba Client; checksum/path/progress đúng.
- [ ] Thử offline, permission denied, size limit, Cancel và mất kết nối giữa transfer.
- [ ] VIEWER không mutate alert/permission/file/Ansible; OPERATOR chỉ thao tác đúng scope.
- [ ] Ansible deploy Linux + Windows thật, chạy lần hai `changed=false`, verify SHA-256.
- [ ] Wi-Fi Windows/Linux theo capability; không đổi Signal % thành RSSI.

## GUI/UX

- [ ] Thao tác mọi tab/dialog trên desktop thật; không đánh dấu PASS từ headless test.
- [ ] Resize, HiDPI 125%/150%, mở/đóng detail/alert/file dialog nhiều lần.
- [ ] Start/Stop Server, Connect/Disconnect Client không khóa EDT.
- [ ] Popup alert non-modal, tự đóng và không spam.
- [ ] Unsupported/STALE/offline/TLS/permission error hiển thị dễ hiểu.

## Performance observation

Ghi Idle CPU/RAM, telemetry interval, RTT trung bình/p95, throughput file và thời gian test. Không dùng một lần đo trên loopback để tuyên bố hiệu năng LAN thực tế.

| Metric | Idle | During telemetry | During 3-client file transfer |
|---|---:|---:|---:|
| Server CPU % | | | |
| Server heap MB | | | |
| Client CPU % | | | |
| Client heap MB | | | |
| RTT avg/p95 ms | | | |
| File throughput MB/s | | | |

Kết quả checklist này hiện là **NOT RUN** cho đến khi người kiểm thử điền bằng quan sát thực tế.
