# Thiết kế giao thức truyền thông

## Framing

Mỗi framed message gồm:

```text
4-byte signed big-endian length N
N bytes UTF-8 JSON
```

`N` phải trong `1..1 MiB`. JSON được parse bằng codec có giới hạn độ sâu/kích thước của ứng dụng; model mapper tiếp tục kiểm tra type, enum, timestamp và range.

## Envelope

Các trường chính của `ProtocolMessage`:

- `version`: phiên bản giao thức.
- `type`: `MessageType`.
- `requestId`: correlation ID.
- `clientId`: identity mục tiêu/nguồn.
- `timestamp`: thời điểm tạo.
- `payload`: object theo từng message.

## Nhóm message

| Nhóm | Message |
|---|---|
| Identity | `REGISTER`, `REGISTERED`, `CONNECT`, `CHALLENGE`, `AUTH`, `AUTH_OK` |
| Liveness | `HEARTBEAT`, `PING`, `PONG` |
| Connection control | `SERVER_DISCONNECT`, `ERROR/CLIENT_BLOCKED` |
| Monitoring | `SYSTEM_INFO_REQUEST`, `SYSTEM_INFO`, `TELEMETRY`, `WIRELESS_SIGNAL`, `WIFI_SCAN` |
| Alert | `ALERT`, `ALERT_ACK` |
| Administration | `MESSAGE`, `ACK`, `PERMISSIONS_UPDATE` |
| File | `FILE_OFFER`, `FILE_ACCEPT`, `FILE_REJECT`, `FILE_CHUNK`, `FILE_COMPLETE`, `FILE_RESULT`, `FILE_CANCEL` |
| Error | `ERROR` |

## Authentication and authorization

- Client identity uses a random local secret and HMAC challenge-response with fresh nonces.
- Session only accepts payload whose `clientId` matches authenticated identity.
- `SERVER_DISCONNECT` is sent before the Server closes an authenticated session. The stable
  `clientId` is then rejected at `CONNECT` with `ERROR/CLIENT_BLOCKED` until an administrator
  removes that identity from the in-memory reconnect block list. A successful reconnect still
  requires the normal challenge/HMAC exchange and `AUTH_OK`.
- Server console role is enforced in service layer, not only by hiding Swing buttons.
- Permission update, all file messages and `WIFI_SCAN` require authenticated TLS by default.
- TLS performs normal certificate-chain and hostname verification; no trust-all implementation exists.
- Khi cấu hình `lanmonitoring.tls.serverIdentity`, Client kết nối TCP tới IPv4 do người dùng nhập nhưng tạo
  layered `SSLSocket` với peer host/SNI là DNS identity cố định. HTTPS endpoint identification kiểm SAN theo
  identity này; IPv4 đổi không làm thay đổi danh tính certificate. Sai trust chain hoặc sai identity vẫn fail closed.

Monitoring can still run in plaintext compatibility mode if TLS is not configured. This is a documented deployment limitation, not equivalent to secure production transport.

## Telemetry semantics

- Mỗi metric có status/freshness; sample cũ hoặc out-of-order không ghi đè sample mới.
- Disk Active Time, Disk Capacity Usage và throughput là các trường độc lập.
- `WIRELESS_SIGNAL` giữ RSSI dBm và Link Quality % độc lập.
- `WIFI_SCAN` chứa tối đa 128 `{targetId SHA-256, rssiDbm, frequencyMhz}`; không chứa raw SSID/BSSID.
- Server chỉ quảng bá capability `WIFI_FINGERPRINT_V1` khi session có TLS, trừ override lab có chủ ý.
- Chỉ estimate `OK` còn TTL mới được dùng để tính distance. Disconnect hoặc TTL expiry chuyển
  fingerprint estimate thành `STALE`; reconnect phải gửi scan mới trước khi trở lại `OK`.
- `OK`, `STALE`, `UNSUPPORTED`, `NOT_AVAILABLE` và `ERROR` không được gộp thành một trạng thái chung.
- Network RTT không được dùng làm physical distance.

## File payload

File được stream theo chunk tối đa 64 KiB và Base64 trong JSON frame. Base64 là transport encoding, không phải mã hóa bảo mật; TLS cung cấp confidentiality/integrity trên đường truyền. Client ghi `.part`, xác minh byte count/SHA-256 rồi mới rename.
