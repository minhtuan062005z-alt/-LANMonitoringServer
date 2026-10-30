# Hướng dẫn cài đặt, build và chạy

## Yêu cầu chung

- JDK 17 LTS trở lên.
- Apache Ant 1.10.x hoặc NetBeans hỗ trợ Ant.
- Giữ `LANMonitoringServer.jar` và toàn bộ `lib/` cạnh nhau trong thư mục `dist`.

## Build release

```powershell
& 'D:\NetBeans\NetBeans-22\netbeans\extide\ant\bin\ant.bat' release-phase9
```

Hoặc nếu `ant` có trong `PATH`:

```text
ant release-phase9
```

Release target luôn clean, chạy test Giai đoạn 1–9 rồi mới package.

## Windows

Từ thư mục `dist`:

```powershell
.\scripts\run-server.bat
.\scripts\run-client.bat
```

Hoặc:

```powershell
java -jar LANMonitoringServer.jar --server
java -jar LANMonitoringServer.jar --client
```

## Linux

```bash
chmod +x scripts/run-server.sh scripts/run-client.sh
./scripts/run-server.sh
./scripts/run-client.sh
```

Linux Wi-Fi RSSI cần `iw`; System Information có thể sử dụng `lsblk`/`lspci`. Ứng dụng không tự cài package và không gọi `sudo`.

## Cấu hình theo môi trường

Launcher đọc `LANMONITORING_JAVA_OPTS`. Ví dụ:

```powershell
$env:LANMONITORING_JAVA_OPTS='-Dlanmonitoring.telemetry.interval.ms=1500 -Dlanmonitoring.wifi.enabled=true'
```

Xem `dist/config/runtime.example.env`. File ví dụ không chứa mật khẩu và không tự được load.

## TLS

Để demo File Transfer/permission, cấu hình PKCS12/truststore qua JVM property hoặc environment tương ứng:

- `lanmonitoring.tls.keyStore`
- `lanmonitoring.tls.keyStorePassword`
- `lanmonitoring.tls.trustStore`
- `lanmonitoring.tls.trustStorePassword`
- `lanmonitoring.tls.serverIdentity` (DNS identity cố định trong certificate SAN, tách khỏi IPv4 kết nối)

Certificate phải có SAN khớp `serverIdentity`. IPv4 người dùng nhập chỉ là địa chỉ TCP và có thể thay đổi.
Chỉ phân phối truststore/public certificate; không đưa Server private key hoặc mật khẩu vào Git/package.

### Windows TLS an toàn (khuyến nghị cho Wi-Fi Fingerprinting)

Trên máy Server, từ thư mục chứa distribution:

```powershell
.\scripts\setup-tls-windows.ps1
.\scripts\run-server-tls.ps1
```

Script tạo material mặc định tại `%LOCALAPPDATA%\LANMonitoringServer\tls`, gồm:

- `server-keystore.p12`: private key, chỉ được giữ trên Server và được giới hạn ACL;
- `server.cer`: certificate công khai để kiểm tra/phân phối;
- `client-truststore.p12`: chỉ chứa certificate công khai của Server.

Chép **chỉ** `client-truststore.p12` sang cùng thư mục TLS trên từng Client, sau đó chạy:

```powershell
.\scripts\run-client-tls.ps1
```

Hai launcher hỏi mật khẩu ẩn và chỉ đặt biến môi trường trong process Java con. Không đặt mật khẩu trong
`LANMONITORING_JAVA_OPTS`, shortcut, file `.cmd` hoặc repo. Certificate mặc định có SAN DNS cố định
`lanmonitoring-server.local`; tên này là identity để xác minh TLS và không cần DNS resolve. Client mở TCP tới
IPv4 nhập trong GUI, sau đó bọc socket bằng TLS với SNI/endpoint identity cố định. Certificate lạ hoặc identity
không khớp vẫn bị handshake từ chối. Có thể dùng identity riêng bằng cách truyền cùng `-ServerIdentity` cho
`setup-tls-windows.ps1` và `run-client-tls.ps1`.

Sau khi Server đổi LAN/Wi-Fi, không chạy lại setup. Mở Server, đọc ô **Server IP** đang tự refresh, rồi nhập
IPv4 mới và Port đó trên Client. Giữ nguyên keystore/truststore và chạy lại TLS launcher như bình thường.

## Ansible

Ansible không nằm trong JAR và không chạy trên Java Client. Xem [PHASE7_ANSIBLE_SETUP.md](PHASE7_ANSIBLE_SETUP.md) để cấu hình Linux/WSL2 Control Node, SSH, WinRM/PSRP, allowlist và Vault.
