# Khảo sát tính khả thi Wi-Fi RTT/FTM cho LANMonitoringServer

> Ngày khảo sát: 2026-10-27 (Asia/Saigon)  
> Phạm vi: chỉ khảo sát kiến trúc, phần cứng, driver và API; không triển khai provider, không tạo dữ liệu FTM giả.  
> Thiết bị được nêu: Realtek RTL8852BE Wi-Fi 6, Qualcomm QCA61x4A 802.11ac, Windows 11 và Client Linux tiềm năng.

## 1. Kết luận điều hành

Với bằng chứng hiện có, **chưa thể tự động đo khoảng cách vật lý Server–Client bằng Wi-Fi FTM trên cấu hình Windows hiện tại**.

- Trên máy Windows được khảo sát, `netsh wlan show wirelesscapabilities` báo trực tiếp `FTM as Initiator : Not Supported` cho interface dùng firmware/driver `rtwlane`. Vì vậy Realtek RTL8852BE trên **đúng tổ hợp máy + firmware + driver Windows hiện tại** không thể làm FTM initiator qua khả năng mà Windows nhận diện.
- Kết quả này không chứng minh silicon RTL8852BE vĩnh viễn không có FTM; nó chứng minh đường chức năng đang cài đặt không cung cấp FTM initiator. Không tìm thấy tài liệu chính thức của Realtek xác nhận initiator/responder cho đúng adapter này.
- Qualcomm QCA61x4A chưa được kiểm tra trực tiếp trên máy chứa adapter đó và không có tài liệu chính thức đủ cụ thể được xác minh trong khảo sát này. Trạng thái phải là `UNKNOWN`, không phải `SUPPORTED`.
- Windows có hợp đồng FTM trong tầng driver WDI (`OID_WDI_TASK_REQUEST_FTM`), nhưng đây là giao tiếp host/driver, không phải API ranging công khai dành cho ứng dụng desktop. Danh mục Native Wi-Fi và `Windows.Devices.WiFi.WiFiAdapter` không có lời gọi FTM chuẩn cho ứng dụng. Vì vậy ngay cả khi driver báo initiator hỗ trợ, Java vẫn cần một API công khai hoặc SDK/vendor device service có tài liệu mới có thể sử dụng hợp lệ.
- Chưa có bằng chứng adapter Windows nào trong phạm vi khảo sát có thể làm FTM responder. Wi-Fi Direct GO, Action Frame hoặc Wi-Fi 6 không đồng nghĩa với FTM responder. Máy RTL8852BE hiện tại còn báo `Soft AP : Not supported`.
- Linux có giao diện kernel thực tế cho peer measurement/FTM qua `nl80211` và `cfg80211`, nên **có thể làm prototype có điều kiện**. Điều kiện bắt buộc là phần cứng, firmware và driver cụ thể phải quảng bá và triển khai initiator; phía đích phải thực sự làm responder, thường trong AP/BSS mode. Chưa có Client Linux thật nào được kiểm tra trong khảo sát này.

Quyết định hiện tại:

| Trường hợp | Kết luận |
|---|---|
| RTL8852BE + driver Windows hiện tại làm initiator | **NO-GO** — Windows báo `Not Supported` |
| RTL8852BE làm responder trên Windows hiện tại | **UNKNOWN/NO-GO để triển khai** — không có capability/API responder được xác minh; Soft AP không hỗ trợ |
| QCA61x4A trên Windows | **UNKNOWN** — phải kiểm tra đúng máy/driver; vẫn còn rào cản API ứng dụng |
| Adapter Linux tương thích làm initiator | **CONDITIONAL** — chỉ khi `nl80211` PMSR được driver/firmware hỗ trợ và phép đo thật thành công |
| Laptop Linux làm responder | **CONDITIONAL, không mặc định** — cần AP mode + FTM responder trong driver/firmware/userspace |
| Server–Client tự động với phần cứng đã biết | **NOT_AVAILABLE** cho đến khi vượt qua toàn bộ capability gate và field test |

## 2. Các đại lượng không được đánh đồng

### 2.1 Network RTT

RTT từ `PING/PONG`, TCP, ICMP hoặc Java Socket gồm thời gian chờ môi trường truyền, MAC contention, hàng đợi, router/AP, hệ điều hành, scheduler và ứng dụng. Không được dùng `c × RTT / 2` để suy ra khoảng cách. Project hiện dùng RTT đúng mục đích là chất lượng/kết nối mạng.

### 2.2 RSSI và khoảng cách Client–AP

RSSI của interface đang kết nối mô tả tín hiệu nhận từ AP/BSS. Mô hình log-distance sau hiệu chuẩn chỉ có thể tạo **ước lượng Client–AP**, chịu ảnh hưởng lớn bởi vật cản, multipath, anten và công suất phát. Nó không phải phép đo Client–Server và không được phép làm fallback cho direct ranging.

### 2.3 Wi-Fi FTM/RTT

FTM là trao đổi timing ở MAC/PHY giữa một **initiator** và một **responder**, dùng timestamp do thiết bị/driver cung cấp. IEEE 802.11az mở rộng định vị với mục tiêu tăng độ chính xác, khả năng mở rộng và bảo mật, nhưng việc một thiết bị là Wi-Fi 5/6/6E không chứng minh nó triển khai các vai trò ranging. [IEEE 802.11az-2022](https://standards.ieee.org/ieee/802.11az/7226/)

Ứng dụng chỉ được công bố số mét FTM khi kết quả đến từ phiên FTM thật, target được xác định là chính Server/Client cần đo, timestamp còn hạn và provider trả chất lượng/độ không chắc chắn hợp lệ.

## 3. Kiến trúc hiện tại của dự án

Project đã tách đúng ba miền dữ liệu:

- `NetworkLatencySample`: RTT mạng, không phải khoảng cách.
- `WirelessSignalSample` / `WirelessObservation`: SSID/BSSID opt-in, RSSI, signal quality và trạng thái wireless.
- `WirelessDistanceEstimate`: ước lượng wireless có target/method/status rõ ràng, hiện chỉ dùng hợp lệ cho Client–AP khi có calibration.

Điểm mở rộng `WifiRttProvider` hiện có:

```java
public interface WifiRttProvider {
    boolean supported();
    WirelessDistanceEstimate range(WirelessSignalSample signal);
    String providerName();
}
```

Provider mặc định `UnsupportedWifiRttProvider` luôn trả `UNSUPPORTED`; đây là hành vi đúng. `ServerClientDistance` là domain độc lập, ngăn `NOT_AVAILABLE`, `UNSUPPORTED` hoặc `ERROR` mang một giá trị mét. `ServerClientDistanceService` hiện chỉ trả manual measurement hoặc khoảng cách từ tọa độ chung; chưa có direct provider.

Nếu triển khai FTM sau này, không nên biến RSSI input của `WifiRttProvider` thành bằng chứng direct ranging. Nên tạo provider direct-ranging riêng có target peer và capability/result rõ ràng; xem mục 9.

## 4. Ba lớp capability bắt buộc phải phân biệt

Một adapter chỉ khả dụng cho ứng dụng khi cả ba lớp đều đạt:

1. **Chipset/radio capability**: silicon có timestamp/ranging và vai trò initiator/responder cần thiết.
2. **Firmware/driver/OS capability**: firmware bật chức năng; driver quảng bá và thực thi đúng giao diện hệ điều hành.
3. **Application access**: hệ điều hành hoặc vendor cung cấp API được hỗ trợ mà ứng dụng có thể gọi và nhận kết quả.

Thiếu bất kỳ lớp nào thì kết quả cho ứng dụng là `UNSUPPORTED` hoặc `NOT_AVAILABLE`. Tên “Wi-Fi 6”, hỗ trợ Action Frame, Wi-Fi Direct hay đọc được RSSI chỉ là các capability khác.

## 5. Kết quả adapter

### 5.1 Realtek RTL8852BE trên Windows 11 hiện tại

Lệnh đã thực thi thật ngày 2026-10-27:

```powershell
netsh wlan show wirelesscapabilities
```

Các dòng liên quan:

```text
Interface name: Wi-Fi
WDI Version (Windows) : 0.1.1.13
WDI Version (IHV)     : 0.1.1.12
WiFiCx Interface      : Not Supported
Firmware Version      : rtwlane
Station               : Supported
Soft AP               : Not supported
Wi-Fi Direct GO       : Supported
Action Frame          : Supported
FTM as Initiator      : Not Supported
```

Diễn giải:

- Initiator qua driver hiện tại: `UNSUPPORTED`.
- Responder: Windows không in capability responder tương ứng; không được suy ra từ Wi-Fi Direct GO hoặc Action Frame. Với Soft AP không hỗ trợ và không có API responder được xác minh, cấu hình này không phải responder khả dụng cho dự án.
- Chipset RTL8852BE tự thân: `UNKNOWN`, vì chưa có datasheet/vendor SDK chính thức xác nhận hai vai trò.
- Cập nhật driver có thể thay đổi capability, nhưng chỉ được đổi kết luận sau khi `netsh` báo hỗ trợ **và** có API ứng dụng thực tế chạy được.

### 5.2 Qualcomm QCA61x4A 802.11ac

Không có output từ đúng máy gắn QCA61x4A trong phiên khảo sát. Chuẩn 802.11ac không bảo đảm 802.11mc FTM. Một số hệ Linux có thể nhận adapter thuộc họ Qualcomm qua driver như `ath10k`, nhưng việc driver nhận phần cứng không chứng minh PMSR/FTM được firmware hỗ trợ.

Trạng thái hợp lệ:

| Lớp | Trạng thái |
|---|---|
| Chipset | `UNKNOWN` |
| Firmware/driver Windows | `UNKNOWN` |
| FTM initiator Windows | `UNKNOWN`, cần `netsh wlan show wirelesscapabilities` trên đúng máy |
| FTM responder Windows | `UNKNOWN`, chưa có API/capability xác minh |
| Linux driver/firmware | `UNKNOWN`, cần boot Linux và kiểm tra đúng wiphy |
| API ứng dụng Windows | Không có API FTM chuẩn công khai được tìm thấy |

Không được ghi “QCA61x4A hỗ trợ FTM” dựa trên tên Qualcomm, 802.11ac hoặc tài liệu ranging chung của hãng.

## 6. Windows: API nào tồn tại và ứng dụng gọi được gì

### 6.1 WDI có FTM nhưng là hợp đồng driver

Microsoft mô tả `OID_WDI_TASK_REQUEST_FTM` là task gửi tới lower edge để initiator thực hiện FTM với danh sách BSS target; kết quả đến qua `NDIS_STATUS_WDI_INDICATION_REQUEST_FTM_COMPLETE`. WDI 1.1.8/Windows 10 1903 bổ sung capability, RTT, accuracy và variance cho đường này. [OID_WDI_TASK_REQUEST_FTM](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/oid-wdi-task-request-ftm), [WDI change history](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/wdi-doc-change-history), [WDI station capabilities](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/wdi-tlv-station-capabilities)

Đây là tài liệu Windows driver và header `Dot11wdi.h`, không phải một hàm Win32 để Java/JNA gọi trực tiếp. Không thiết kế ứng dụng bằng cách tự phát raw OID vào driver nếu Microsoft/IHV không công bố đó là interface được hỗ trợ.

WDI đang ở maintenance mode; Microsoft khuyến nghị WiFiCx cho driver mới trên Windows 11. WiFiCx cũng là framework phát triển driver, không tự động trở thành API ranging cho ứng dụng.

### 6.2 Native Wi-Fi và Windows.Devices.WiFi

Danh mục Native Wi-Fi công khai cung cấp enumerate, scan, BSS list, connect/disconnect, profile và device-service plumbing; không có hàm FTM request/result chuẩn. `Windows.Devices.WiFi.WiFiAdapter` tương tự cung cấp enumerate, scan, network report và connect/disconnect, không có ranging. [Native Wi-Fi functions](https://learn.microsoft.com/en-us/windows/win32/nativewifi/native-wifi-functions), [WiFiAdapter](https://learn.microsoft.com/en-us/uwp/api/windows.devices.wifi.wifiadapter)

`WlanDeviceServiceCommand` chỉ là kênh OEM/IHV dùng GUID/opcode/buffer riêng. Nó chỉ khả thi nếu Realtek/Qualcomm cung cấp device-service contract chính thức cho FTM và driver đã cài triển khai contract đó; chưa có bằng chứng như vậy. Hàm còn yêu cầu Administrator hoặc UMDF driver. Không được đoán opcode hay reverse-engineer buffer để biến nó thành provider. [WlanDeviceServiceCommand](https://learn.microsoft.com/en-us/windows/win32/api/wlanapi/nf-wlanapi-wlandeviceservicecommand)

Ngoài ra, các API Wi-Fi/BSSID mới có yêu cầu consent vị trí; từ chối quyền phải dẫn tới `NOT_AVAILABLE`, không được vô hiệu hóa kiểm soát của Windows. [Windows Wi-Fi access/location changes](https://learn.microsoft.com/en-us/windows/win32/nativewifi/wi-fi-access-location-changes)

### 6.3 Kết luận Windows

Một Windows provider chỉ được coi là khả thi khi đồng thời có:

- adapter/driver báo `FTM as Initiator: Supported`;
- responder thật xuất hiện với capability FTM;
- Microsoft hoặc vendor cung cấp API/SDK ứng dụng được hỗ trợ;
- code native nhỏ có thể khởi tạo phiên, nhận result/accuracy/target và vượt test thật.

Hiện chưa thỏa điều kiện thứ nhất trên RTL8852BE, chưa xác minh QCA61x4A, và chưa có API chuẩn ở điều kiện thứ ba.

## 7. Linux: khả thi có điều kiện

Linux có đường giao tiếp ứng dụng–kernel rõ ràng hơn. `cfg80211` là API cấu hình giữa userspace và driver, được đưa ra userspace qua `nl80211`. Driver ops có `start_pmsr`/`abort_pmsr` cho peer measurement (ví dụ FTM) và `get_ftm_responder_stats`. [Linux cfg80211](https://docs.kernel.org/driver-api/80211/cfg80211.html)

Đặc tả `nl80211` liệt kê `peer-measurement-start`, `peer-measurement-result`, `peer-measurement-complete` và `get-ftm-responder-stats`, tức có một API kernel thực sự để xây native helper. [nl80211 specification](https://www.kernel.org/doc/html/next/networking/netlink_spec/nl80211.html)

Tuy nhiên, sự tồn tại của API kernel không bảo đảm một adapter cụ thể triển khai nó. Cần kiểm tra:

```bash
iw dev
iw phy phy0 info
ethtool -i wlan0
lspci -nnk
uname -a
```

Cần lưu nguyên output, driver, firmware và kernel. Chuỗi capability của `iw` thay đổi theo phiên bản, nên kết luận phải dựa cả vào supported commands/features và một PMSR thật, không chỉ tìm một từ khóa.

### Responder trên laptop Linux

Kernel có trường beacon `ftm_responder` để bật responder và ghi rõ phần probe response template là AP mode. Điều này cho thấy responder có thể tồn tại ở BSS/AP mode, nhưng chỉ khi driver/firmware triển khai, adapter cho phép AP mode/channel cần dùng và userspace cấu hình đúng. [cfg80211 FTM responder](https://docs.kernel.org/driver-api/80211/cfg80211.html#c.cfg80211_beacon_data)

Vì vậy laptop Linux **có thể** làm responder trong một số cấu hình, nhưng không phải mặc định. Một STA bình thường cùng kết nối router không tự trở thành responder. Có thể cần hostapd/driver phù hợp và việc chuyển hoặc tạo virtual AP có thể ảnh hưởng kết nối LAN hiện tại.

## 8. Laptop có thể range trực tiếp tới laptop khác không?

Chỉ khi một laptop thực sự làm initiator và laptop kia thực sự quảng bá BSS/peer responder mà driver/API hỗ trợ. Hai laptop cùng ở chế độ station kết nối một router không tự động tạo phép đo FTM trực tiếp cho nhau.

- Wi-Fi Direct GO chỉ nói thiết bị có thể giữ vai trò group owner; không phải bằng chứng responder FTM.
- Action Frame support là capability chung, không phải timestamp engine/ranging API.
- Nếu initiator chỉ range tới router/AP thì kết quả là Server–AP hoặc Client–AP, không phải Server–Client.
- Không được lấy hiệu hai khoảng cách tới AP để suy ra khoảng cách hai laptop; thiếu góc/hình học và sai số làm bài toán không xác định.

Trong cấu hình Windows được kiểm tra, mô hình laptop–laptop không khả thi vì RTL endpoint không làm initiator và chưa có responder/API ứng dụng. Trên Linux, đây chỉ là nghiên cứu có điều kiện nếu một endpoint dựng được FTM-capable AP/responder và endpoint kia thực hiện được PMSR.

## 9. Kiến trúc tích hợp đề xuất nếu capability gate đạt

Không sửa code trong giai đoạn khảo sát. Kiến trúc tương lai nên thêm miền direct-ranging riêng:

```text
Java Server/Client
    -> DirectRangingService (schedule, timeout, rate limit, identity binding)
        -> DirectRangingProvider
            -> WindowsVendorFtmProvider (chỉ khi có SDK/API được hỗ trợ)
            -> LinuxNl80211FtmProvider (JNI/JNA hoặc helper libnl hẹp)
        -> DirectRangeResult
            -> protocol DIRECT_RANGE_RESULT_V1 qua session TLS đã xác thực
            -> ServerClientDistance DIRECT_MEASUREMENT
```

Interface khái niệm:

```java
interface DirectRangingProvider {
    DirectRangingCapability capability();
    DirectRangeResult range(DirectPeer target, Duration timeout);
    void cancel(String requestId);
}
```

`DirectRangeResult` tối thiểu cần:

- request ID và stable authenticated Client ID;
- local/remote role;
- peer radio identifier hoặc pseudonymous binding;
- method (`IEEE_80211MC_FTM` hoặc `IEEE_80211AZ_RANGING`);
- distance mét chỉ khi status `OK`;
- raw driver status, measurement count, uncertainty/accuracy/variance nếu API trả;
- measurement timestamp, receive timestamp, age/stale limit;
- bằng chứng target là đúng peer Server–Client, không phải AP trung gian.

### Native bridge

- **Windows**: chỉ dùng DLL/JNA/JNI khi Microsoft/IHV cung cấp contract hỗ trợ. Không gọi raw/internal OID và không giả mạo driver.
- **Linux**: ưu tiên helper nhỏ dùng `nl80211`/libnl, input có schema và allowlist; giao tiếp local pipe/stdin-stdout structured; timeout/cancel; không ghép shell command và không chạy quyền root toàn bộ Java process. Chỉ cấp capability tối thiểu nếu kernel yêu cầu.
- Provider chạy ngoài EDT, heartbeat và telemetry executors.
- Nhiều Client phải được xếp lịch/rate-limit để tránh channel contention; một lỗi ranging không được làm mất monitoring.

### Identity binding

MAC/BSSID có thể randomize và không phải stable Client ID. Khi onboarding, quản trị viên phải xác nhận mapping radio target ↔ Client ID qua session TLS/xác thực hiện có. Kết quả tự báo cáo từ Client không đủ để chứng minh Server đã range tới đúng Client; cần challenge/request ID và target evidence từ provider initiator. Không log raw MAC khi không cần; lưu token băm/scope theo consent.

## 10. Capability gates trước khi viết provider

Chỉ chuyển từ `NOT_AVAILABLE` sang triển khai khi vượt lần lượt:

1. **Exact hardware**: ghi PCI/USB ID, adapter revision.
2. **Exact software**: OS/kernel, driver và firmware version.
3. **Role advertisement**: initiator/responder được hệ điều hành báo hỗ trợ.
4. **Public application path**: API/SDK có tài liệu, không phải driver-internal interface.
5. **Real responder**: peer đích quảng bá responder và chính là endpoint cần đo.
6. **Successful session**: nhận FTM result thật với target, timestamp và quality.
7. **Ground-truth validation**: sai số/dropout đạt tiêu chí đã định trước.
8. **Integration safety**: không làm nghẽn TCP/TLS telemetry, alert, file transfer hoặc Ansible.

Nếu bất kỳ gate nào thất bại, model phải trả `UNSUPPORTED`, `NOT_AVAILABLE`, `STALE` hoặc `ERROR`, không mang `distanceMeters`.

## 11. Kế hoạch kiểm thử phần cứng thật

### 11.1 Inventory

Trên từng Windows endpoint:

```powershell
netsh wlan show interfaces
netsh wlan show drivers
netsh wlan show wirelesscapabilities
Get-NetAdapter -Physical
Get-NetAdapterAdvancedProperty -Name "Wi-Fi"
```

Không cần Administrator cho `netsh` thông thường; nếu CIM/NetAdapter bị policy chặn, lưu lỗi quyền và vẫn giữ output `netsh`. Ghi model adapter, hardware ID trong Device Manager, driver provider/version/date và firmware string.

Trên Linux dùng các lệnh ở mục 7 và lưu full output. Không cài hoặc thay driver trong bước discovery.

### 11.2 Ma trận vai trò

Thử riêng từng cặp nếu capability cho phép:

| Initiator | Responder | Điều kiện |
|---|---|---|
| RTL Windows | QCA Windows | Chỉ khi cả API initiator và responder thật tồn tại |
| QCA Windows | RTL Windows | Tương tự; không đảo vai trò bằng suy đoán |
| Linux adapter | FTM-capable AP | Xác minh PMSR Client–AP trước |
| Linux adapter A | Linux laptop B | B phải chạy BSS/AP FTM responder thật |

Không lấy phép đo tới router rồi gắn nhãn laptop–laptop.

### 11.3 Accuracy protocol

1. Đo ground truth bằng thước tại 1, 3, 5 và 10 m; ghi điểm tham chiếu vật lý trên hai máy.
2. Mỗi khoảng cách thu tối thiểu 30 phiên hợp lệ; không thay result rớt bằng số giả.
3. Tách LOS và NLOS/tường; ghi band, channel, bandwidth và orientation.
4. Báo median error, MAE, p95 absolute error, bias, dropout/timeout rate và sample age.
5. Thử sai target, peer offline, stale, driver restart, sleep/reconnect và nhiều Client lần lượt.
6. So sánh ảnh hưởng tới telemetry/heartbeat; không chạy tất cả ranging đồng thời nếu driver giới hạn target.

Chỉ ghi `DIRECT_MEASUREMENT` khi target binding đúng và result đạt tiêu chí. Capability string đơn thuần không đủ.

## 12. Phương án thay thế trung thực

Nếu FTM không vượt gate:

- Giữ `ServerClientDistance` manual measurement và tọa độ phòng chung như hiện tại; nhãn rõ `MANUAL_MEASUREMENT` hoặc `POSITION_BASED_ESTIMATION`, không gọi realtime.
- Giữ RSSI/calibrated Client–AP estimate trong tab Wireless riêng; không chuyển thành Server–Client.
- Giữ network RTT như chỉ số latency/chất lượng mạng.
- Ethernet hoặc Wi-Fi không có direct provider phải hiển thị `NOT_AVAILABLE`.
- Nếu phạm vi/phần cứng tương lai thay đổi, dùng một AP/responder được nhà sản xuất xác nhận 802.11mc/az hoặc thiết bị có API ranging công khai. Đây là yêu cầu phần cứng/API mới, không phải tính năng có thể khắc phục chỉ bằng Java.

## 13. Tiêu chí ra quyết định cho dự án

### Trên phần cứng hiện tại

- Không triển khai Windows FTM provider cho RTL8852BE hiện tại.
- Chạy discovery trên máy QCA61x4A; kết quả `Supported` của `netsh` mới chỉ vượt gate driver, chưa vượt gate API/responder.
- Nếu có Linux, chỉ làm proof-of-capability sau khi `iw`/driver cho thấy PMSR và có responder thật.
- Dashboard tiếp tục trả `NOT_AVAILABLE` cho automatic Server–Client distance.

### Khi nào có thể mở giai đoạn prototype

Chỉ mở khi có ít nhất một cặp initiator/responder thật và một API userspace được hỗ trợ. Ưu tiên Linux `nl80211` vì có contract userspace/kernel công khai. Windows chỉ mở khi có API Microsoft/vendor rõ ràng; không dùng WDI OID nội bộ như API ứng dụng.

## 14. Nguồn kỹ thuật chính

- [Microsoft — OID_WDI_TASK_REQUEST_FTM](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/oid-wdi-task-request-ftm)
- [Microsoft — WDI documentation change history](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/wdi-doc-change-history)
- [Microsoft — WDI station capabilities](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/wdi-tlv-station-capabilities)
- [Microsoft — Native Wi-Fi functions](https://learn.microsoft.com/en-us/windows/win32/nativewifi/native-wifi-functions)
- [Microsoft — Windows.Devices.WiFi.WiFiAdapter](https://learn.microsoft.com/en-us/uwp/api/windows.devices.wifi.wifiadapter)
- [Microsoft — WlanDeviceServiceCommand](https://learn.microsoft.com/en-us/windows/win32/api/wlanapi/nf-wlanapi-wlandeviceservicecommand)
- [Linux kernel — cfg80211 subsystem](https://docs.kernel.org/driver-api/80211/cfg80211.html)
- [Linux kernel — nl80211 netlink specification](https://www.kernel.org/doc/html/next/networking/netlink_spec/nl80211.html)
- [IEEE — IEEE 802.11az-2022](https://standards.ieee.org/ieee/802.11az/7226/)

## 15. Giới hạn của khảo sát

- Chỉ RTL8852BE trên máy Windows hiện tại được probe trực tiếp; chưa có quyền truy cập máy QCA61x4A.
- Không có adapter Linux thật, responder FTM hoặc AP có capability đã xác minh để chạy phiên ranging.
- Không thực hiện phép đo khoảng cách thật và không có số liệu accuracy.
- Không tìm thấy tài liệu vendor chính thức đủ cụ thể xác nhận FTM initiator/responder cho hai model adapter được nêu.
- Tài liệu này không thay đổi source, protocol, UI, build hay artifact hiện có.

Do đó kết luận khoa học cuối cùng cho bản hiện tại là: **tự động đo khoảng cách vật lý Java Server–Client bằng Wi-Fi FTM chưa khả thi trên cấu hình đã xác minh; Linux là hướng prototype có điều kiện, còn fallback manual/common-coordinate hiện có là phương án trung thực.**
