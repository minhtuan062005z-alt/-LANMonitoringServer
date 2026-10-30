# Kiến trúc hệ thống và sơ đồ

## Thành phần

```mermaid
flowchart LR
    subgraph ServerHost[Java Server]
        UI[Server Swing UI]
        SVC[LanServerService]
        AE[Alert Engine + Store]
        FT[File Transfer + RBAC + Audit]
        AS[AnsibleService / Runner]
        RM[RadioMapStore + Weighted KNN]
    end
    subgraph Clients[LAN Clients]
        W1[Windows Client 1]
        W2[Windows Client 2]
        L1[Linux Client]
    end
    CN[Linux/WSL2 Ansible Control Node]
    AP[Wi-Fi Access Point]

    UI --> SVC
    SVC --> AE
    SVC --> FT
    SVC --> RM
    UI --> AS --> CN
    SVC <-->|Authenticated TCP / optional TLS monitoring| W1
    SVC <-->|Authenticated TCP / optional TLS monitoring| W2
    SVC <-->|Authenticated TCP / optional TLS monitoring| L1
    CN -->|SSH| L1
    CN -->|WinRM/PSRP or SSH| W1
    CN -->|WinRM/PSRP or SSH| W2
    W1 -. RSSI Client-AP .-> AP
    W2 -. RSSI Client-AP .-> AP
    L1 -. RSSI Client-AP .-> AP
    W1 -. Hashed multi-AP scan .-> RM
    W2 -. Hashed multi-AP scan .-> RM
    L1 -. Hashed multi-AP scan .-> RM
```

Java Client online không tự động trở thành Ansible managed host. Ansible dùng allowlist/credential riêng. RSSI trong sơ đồ là Client–AP, không phải Server–Client.

## CONNECT, authentication và heartbeat

```mermaid
sequenceDiagram
    participant C as Java Client
    participant S as Java Server
    C->>S: CONNECT(clientId, hostname, clientNonce)
    alt identity chưa đăng ký
        S-->>C: ERROR NOT_REGISTERED
        C->>S: REGISTER(clientId, random secret)
        S-->>C: REGISTERED
        C->>S: CONNECT
    end
    S-->>C: CHALLENGE(serverNonce)
    C->>S: AUTH(HMAC proof)
    S-->>C: AUTH_OK(capabilities)
    loop mỗi 3 giây
        C->>S: HEARTBEAT(CPU/RAM fallback)
        S-->>C: PING(requestId)
        C->>S: PONG(requestId)
    end
    loop telemetry interval
        C->>S: TELEMETRY(timestamp/status/metrics)
    end
```

## File Transfer

```mermaid
sequenceDiagram
    participant A as ADMIN/OPERATOR
    participant S as Server
    participant C as Authorized TLS Client
    A->>S: Select file + Clients
    S->>S: RBAC, permission, TLS, size, SHA-256
    S->>C: FILE_OFFER(metadata)
    C->>C: path/space/permission validation
    C-->>S: FILE_ACCEPT or FILE_REJECT
    loop chunks tối đa 64 KiB
        S->>C: FILE_CHUNK(offset, Base64 data)
        C->>C: stream to .part + digest
    end
    S->>C: FILE_COMPLETE
    C->>C: byte count + SHA-256 + atomic rename
    C-->>S: FILE_RESULT(SUCCESS/FAILED/CANCELLED)
```

## Resource Alerting

```mermaid
sequenceDiagram
    participant C as Client
    participant E as Server AlertEngine
    participant U as Server Admin
    C->>E: TELEMETRY
    E->>E: status/freshness + duration + thresholds
    E->>E: hysteresis + cooldown + lifecycle
    E-->>C: ALERT đúng clientId
    C-->>E: ALERT_ACK (delivery only)
    U->>E: Admin Acknowledge or Resolve
    E->>E: Persist rule/history
```

`ALERT_ACK` không đồng nghĩa Admin Acknowledge.

## Ansible synchronization

```mermaid
sequenceDiagram
    participant U as ADMIN UI
    participant J as AnsibleService
    participant R as AnsibleRunner
    participant H as Allowlisted hosts
    U->>J: Generate UTF-8 source
    J->>J: Safe workspace + SHA-256
    U->>J: Deploy/Verify selected hosts
    J->>J: RBAC + allowlist + encrypted vault checks
    J->>R: ProcessBuilder(argument list)
    R->>H: copy/win_copy through SSH or WinRM
    H-->>R: stat/win_stat + SHA-256
    R-->>J: per-host marker + recap
    J-->>U: SUCCESS/FAILED/UNREACHABLE
```

## Threading model

- Swing components are mutated on the EDT.
- Accept/read/connect/reconnect and file transfer use named daemon executors.
- Heartbeat, telemetry, Wi-Fi sampling and system inventory use separate executors so an OS probe does not block connectivity.
- Connected-AP monitoring and opt-in multi-AP fingerprint scanning use separate schedulers. A Wi-Fi
  fingerprint is realtime-eligible only while status is `OK`, its TTL is valid and the Client is connected.
- TLS deployment separates the mutable TCP endpoint (current LAN IPv4 entered in the Client) from a stable
  certificate DNS identity. The Client connects the raw socket to the IPv4, then layers TLS using the stable
  identity for SNI and endpoint verification; no DNS lookup or hostname-verification bypass is used.
- `FramedConnection` serializes writes per socket, preventing interleaved frames.
- Dashboard coalesces Client updates every 250 ms; charts and histories have fixed bounds.
- Shutdown cancels schedules, closes sockets/streams and terminates executors. Final service close is idempotent.
