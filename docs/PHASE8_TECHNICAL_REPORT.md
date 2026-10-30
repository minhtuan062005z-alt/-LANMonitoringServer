# Phase 8 Technical Report: Wi-Fi Signal and Distance Estimation

## Measurement semantics

The implementation deliberately maintains three independent concepts:

1. `WirelessSignalSample`: an OS-observed radio/link property.
2. `NetworkLatencySample`: application PING/PONG round-trip latency.
3. `WirelessDistanceEstimate`: a model-derived value with target, method and confidence.

TCP/application RTT is not converted to meters. With the bundled collectors, RSSI describes the Client-to-Access-Point path, not Client-to-Server. Consequently, the UI only emits an **Estimated Client-to-AP Distance** after AP-specific calibration.

## Platform capability survey

### Windows

`netsh wlan show interfaces` is invoked without active scanning. Depending on Windows and the Wi-Fi driver, it may expose adapter, connection state, SSID, BSSID, Signal/Link Quality percentage and a separate `Rssi`/`RSSI` field. A finite RSSI value in the accepted dBm range is preserved directly. Link Quality remains an independent value and is never relabelled or numerically converted to dBm. If RSSI is missing, malformed or out of range, `rssiDbm` remains null and distance stays unavailable until a future valid RSSI and matching calibration exist.

The parser normalizes case, a possible BOM, Vietnamese diacritics and Unicode minus, tolerates optional `dBm`, handles multiple interfaces and prefers a connected interface with valid RSSI. Adapter Description is preferred over the logical interface name. OS command failures, timeout, missing adapter, disconnected state and access denial return an explicit unsupported observation rather than fabricated data. `-Dlanmonitoring.wifi.netsh.charset=<charset>` can override the JVM default when localized `netsh` output uses another code page; invalid charset names safely fall back.

Reference: [Microsoft netsh wlan command](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/netsh-wlan) and [Microsoft wireless troubleshooting fields](https://learn.microsoft.com/en-us/troubleshoot/windows-client/networking/wireless-network-connectivity-issues-troubleshooting).

### Linux

`iw dev` identifies the nl80211 interface and `iw dev <adapter> link` reads the current association. A connected AP MAC/BSSID and signal dBm are parsed when the driver reports them. No `scan` command is issued. `iw phy` is probed once for an FTM-initiator capability string, but capability alone is not treated as successful ranging.

Reference: [Linux Wireless `iw` documentation](https://wireless.docs.kernel.org/en/latest/en/users/documentation/iw.html) and [Linux cfg80211 signal units](https://docs.kernel.org/6.6/driver-api/80211/cfg80211.html).

### Wi-Fi RTT/FTM

`WifiRttProvider` provides an extension boundary, while the bundled implementation returns `UNSUPPORTED`. A future provider must have compatible adapter, driver/API and responder/AP support and perform a real FTM exchange. The project does not infer this from an arbitrary laptop model.

Reference: [Android Wi-Fi RTT overview](https://developer.android.com/develop/connectivity/wifi/wifi-rtt).

## Calibrated model

For reference distance `d0 = 1 m`, the estimator fits the model:

```text
RSSI(d) = RSSI(d0) - 10 n log10(d / d0)
d = d0 * 10 ^ ((RSSI(d0) - RSSI(d)) / (10 n))
```

Linear regression over administrator-entered real `distance,rssi` points estimates reference RSSI, path-loss exponent `n`, and RMSE. Equal-distance data and implausible `n` outside 0.5–8 are rejected. EMA (`alpha=0.30`) reduces jitter. Confidence never exceeds MEDIUM because environmental/model uncertainty remains material; the displayed uncertainty is derived from calibration RMSE, not claimed as a statistical guarantee.

The model choice follows the log-distance path-loss family described in [NISTIR 7761](https://nvlpubs.nist.gov/nistpubs/Legacy/IR/nistir7761.pdf), but the project's fitted parameters are environment-specific.

## Privacy and runtime behavior

- Collection is off by default and can be toggled on Client.
- Default period is 2 seconds; no active scans are triggered.
- SSID/BSSID sharing is off by default. The Server receives a SHA-256 AP target ID to bind calibration without needing the raw BSSID.
- A dedicated executor prevents OS command latency from blocking heartbeat or telemetry.
- Histories are bounded to 120 values. Old/out-of-order samples are ignored and the UI changes a timed-out distance to `NOT_AVAILABLE (STALE)`.
- Calibration is in-memory and scoped by AP target ID; it is not silently reused for another BSSID.

## Validation status and limitations

The 2026-10-27 Windows RSSI update was validated with `ant release-phase8`: clean compilation of 125 source files and 14 test files, all Phase 1–8 runners PASS, followed by packaging. Both Phase 8 tests were then run with application classes loaded from the new JAR and passed. The artifact is 875073 bytes with SHA-256 `21DE7F035F0A7D57205B4EA4C9A7728672785904734327300C80A013799B8D16`; its manifest, seven `dist/lib` dependencies and five allowlisted Ansible assets were verified.

The regression suite includes a user-provided Windows/Realtek RTL8852BE-shaped fixture with Signal 100%, RSSI -44 dBm and 866.7 Mbps rates. It verifies direct `-44` parsing, Signal-only fallback, invalid/missing RSSI, disconnected state, access denial, localized output, multiple interfaces and the existing calibrated Client-to-AP estimator path. Linux collector code and semantics are unchanged.

The user independently observed RSSI -44 dBm on a real Windows/Realtek RTL8852BE adapter. The Codex execution environment itself previously returned exit code 1 from `netsh wlan show interfaces`, so Java runtime capture on that adapter and physical distance accuracy were not executed in this session. No Linux Wi-Fi adapter, real FTM exchange, interactive GUI, wall/multipath experiment, or manually measured distance was available. Those items remain explicitly open in `PHASE8_WIFI_TEST_CHECKLIST.md`.
