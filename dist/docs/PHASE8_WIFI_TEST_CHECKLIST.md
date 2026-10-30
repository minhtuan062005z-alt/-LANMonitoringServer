# Phase 8 Real Wi-Fi Validation Checklist

This checklist separates automated software verification from physical measurements. Do not mark accuracy PASS using parser fixtures or synthetic RSSI.

## Lab preparation

- [ ] Use an interactive desktop, one Server and at least one Client on a managed Wi-Fi network.
- [ ] Record OS, Wi-Fi adapter/driver, AP model, frequency band/channel, and whether Server is wired or wireless.
- [ ] Measure **Client-to-AP** distance with a tape/laser tool. Do not substitute Client-to-Server distance.
- [ ] Obtain permission before showing SSID/BSSID. Leave identifiers hidden unless the demo needs them.
- [ ] On Linux, verify `iw dev` and `iw dev <adapter> link` work without sudo. Do not enable SSH, disable firewall, or scan repeatedly.
- [ ] On Windows, verify `netsh wlan show interfaces`; record that Signal is Link Quality % unless a separate trustworthy API supplies dBm.

## Functional desktop test

- [ ] Run `ant release-phase8`, then launch the new JAR with `dist/lib` intact.
- [ ] Connect Client and confirm monitoring, alerts, file transfer and Ansible tabs remain responsive.
- [ ] Enable **Wi-Fi Research > Enable Wi-Fi measurement** on Client.
- [ ] Confirm Network tab updates about every 2 seconds without freezing either GUI.
- [ ] Confirm RTT says latency/not physical distance.
- [ ] Confirm hidden SSID/BSSID stay hidden while signal status still updates.
- [ ] On Windows output containing `Rssi`/`RSSI`, confirm the direct dBm value appears independently from Signal %; distance must remain unavailable until matching AP calibration exists.
- [ ] Confirm Windows quality-only samples show RSSI/distance as UNSUPPORTED, not a fabricated dBm/meters value.
- [ ] On Linux with real RSSI, confirm Signal chart updates and Estimated Distance remains unavailable before calibration.
- [ ] Disable measurement and confirm new Wi-Fi points stop while heartbeat/telemetry continue.
- [ ] Disconnect/reconnect and confirm only one sampling task resumes when enabled.

## Calibration and measurement table

Use at least four unobstructed calibration positions when possible. Enter each `distanceMeters,rssiDbm` pair in **Calibrate RSSI**.

| Position | Measured Client-AP m | Raw RSSI dBm | Smoothed RSSI dBm | Estimated m | Absolute error m | Walls/obstacles | Notes |
|---|---:|---:|---:|---:|---:|---|---|
| 1 | | | | | | | |
| 2 | | | | | | | |
| 3 | | | | | | | |
| 4 | | | | | | | |
| Validation A | | | | | | | |
| Validation B | | | | | | | |

- [ ] Calibration rejects identical distances or an implausible fitted path-loss exponent.
- [ ] Result is labelled Estimated Client-to-AP Distance with uncertainty/confidence.
- [ ] Move behind a wall and record multipath/obstacle error; do not silently merge this with open-space results.
- [ ] Roam to another BSSID/AP and confirm old smoothing/calibration is not applied to the new target.
- [ ] Leave telemetry absent long enough and confirm STALE/NOT_AVAILABLE rather than a retained “live” distance.

## Wi-Fi RTT/FTM capability

- [ ] Record adapter/AP/driver capability separately.
- [ ] Do not mark ranging PASS merely because `iw phy` contains `FTM initiator`.
- [ ] Mark FTM ranging PASS only if a real provider starts a standards-based ranging exchange and returns independently validated distance.

## Result classification

- Automated Phase 1-8 regression: PASS only from actual `ant test-phase8` output.
- Desktop GUI: PASS only after visual interaction above.
- Windows/Linux collector runtime: PASS separately per real OS/device.
- Physical accuracy: report MAE/error distribution and environment; never state “actual distance” for the model output.
