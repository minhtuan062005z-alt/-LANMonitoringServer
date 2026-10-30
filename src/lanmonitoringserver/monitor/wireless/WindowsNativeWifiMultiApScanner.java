package lanmonitoringserver.monitor.wireless;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lanmonitoringserver.model.AccessPointSignal;
import lanmonitoringserver.model.MeasurementStatus;
import lanmonitoringserver.model.WifiScanSnapshot;
import lanmonitoringserver.model.WirelessSignalSample;

/** Uses the documented Native Wi-Fi BSS API so Windows RSSI remains real dBm. */
public final class WindowsNativeWifiMultiApScanner implements MultiAccessPointScanner {
    private static final int WLAN_CLIENT_VERSION_LONGHORN = 2;
    private static final int DOT11_BSS_TYPE_ANY = 3;
    private static final int ERROR_ACCESS_DENIED = 5;
    private final WlanApi api;

    public WindowsNativeWifiMultiApScanner() { this(WlanApi.INSTANCE); }
    WindowsNativeWifiMultiApScanner(WlanApi api) { this.api = api; }

    @Override public WifiScanSnapshot scan() {
        long now = System.currentTimeMillis();
        PointerByReference handleReference = new PointerByReference();
        IntByReference negotiated = new IntByReference();
        int open = api.WlanOpenHandle(WLAN_CLIENT_VERSION_LONGHORN, Pointer.NULL,
                negotiated, handleReference);
        if (open != 0) return unavailable(now, "Native Wi-Fi open failed with Windows error " + open);
        Pointer handle = handleReference.getValue();
        PointerByReference interfacesReference = new PointerByReference();
        try {
            int enumerated = api.WlanEnumInterfaces(handle, Pointer.NULL, interfacesReference);
            if (enumerated != 0) return unavailable(now, windowsError("enumerate interfaces", enumerated));
            Pointer list = interfacesReference.getValue();
            if (list == null) return unavailable(now, "Native Wi-Fi returned no interface list");
            try {
                int count = list.getInt(0);
                int offset = 8;
                int entrySize = new WlanInterfaceInfo().size();
                LinkedHashMap<String, AccessPointSignal> signals = new LinkedHashMap<>();
                StringBuilder adapters = new StringBuilder();
                for (int index = 0; index < count; index++) {
                    WlanInterfaceInfo info = new WlanInterfaceInfo(list.share(offset + (long) index * entrySize));
                    info.read();
                    if (adapters.length() > 0) adapters.append(", ");
                    adapters.append(Native.toString(info.description));
                    int scan = api.WlanScan(handle, info.interfaceGuid, Pointer.NULL,
                            Pointer.NULL, Pointer.NULL);
                    if (scan == ERROR_ACCESS_DENIED) return unavailable(now,
                            "Windows denied Wi-Fi scan/BSSID access; enable Location consent for the app");
                    if (scan != 0) continue;
                    try { Thread.sleep(1200); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return unavailable(now, "Windows Wi-Fi scan was cancelled");
                    }
                    collectBss(handle, info.interfaceGuid, signals);
                }
                List<AccessPointSignal> values = new ArrayList<>(signals.values());
                return new WifiScanSnapshot(now, interval(), values.isEmpty()
                        ? MeasurementStatus.UNSUPPORTED : MeasurementStatus.OK,
                        adapters.toString(), values, scannerName(), values.isEmpty()
                                ? "Native Wi-Fi returned no BSS RSSI values"
                                : "Native WLAN_BSS_ENTRY.lRssi values; identifiers are hashed and SSIDs omitted");
            } finally { api.WlanFreeMemory(list); }
        } finally { api.WlanCloseHandle(handle, Pointer.NULL); }
    }

    private void collectBss(Pointer handle, Guid.GUID interfaceGuid,
            Map<String, AccessPointSignal> result) {
        PointerByReference bssReference = new PointerByReference();
        int status = api.WlanGetNetworkBssList(handle, interfaceGuid, Pointer.NULL,
                DOT11_BSS_TYPE_ANY, 0, Pointer.NULL, bssReference);
        if (status != 0 || bssReference.getValue() == null) return;
        Pointer list = bssReference.getValue();
        try {
            int count = list.getInt(4);
            int size = new WlanBssEntry().size();
            for (int index = 0; index < Math.min(count, 512); index++) {
                WlanBssEntry entry = new WlanBssEntry(list.share(8L + (long) index * size));
                entry.read();
                if (entry.rssi < -150 || entry.rssi > 0) continue;
                StringBuilder mac = new StringBuilder();
                for (int i = 0; i < entry.bssid.length; i++) {
                    if (i > 0) mac.append(':');
                    mac.append(String.format(java.util.Locale.ROOT, "%02x", entry.bssid[i] & 0xff));
                }
                String target = WirelessSignalSample.targetIdForBssid(mac.toString());
                Integer frequency = entry.centerFrequency == 0 ? null
                        : Math.toIntExact(entry.centerFrequency / 1000L);
                AccessPointSignal candidate = new AccessPointSignal(target, entry.rssi, frequency);
                result.merge(target, candidate, (oldValue, newValue) ->
                        newValue.rssiDbm() > oldValue.rssiDbm() ? newValue : oldValue);
            }
        } finally { api.WlanFreeMemory(list); }
    }

    private WifiScanSnapshot unavailable(long now, String detail) {
        return new WifiScanSnapshot(now, interval(), MeasurementStatus.UNSUPPORTED,
                "", List.of(), scannerName(), detail);
    }
    private static String windowsError(String operation, int code) {
        return code == ERROR_ACCESS_DENIED
                ? "Windows denied " + operation + "; Location consent may be required"
                : "Native Wi-Fi " + operation + " failed with Windows error " + code;
    }
    private static long interval() {
        return Math.max(5_000L, Math.min(300_000L,
                Long.getLong("lanmonitoring.fingerprint.interval.ms", 10_000L)));
    }
    @Override public String scannerName() { return "Windows Native Wi-Fi multi-AP scan"; }

    interface WlanApi extends Library {
        WlanApi INSTANCE = Native.load("wlanapi", WlanApi.class, W32APIOptions.DEFAULT_OPTIONS);
        int WlanOpenHandle(int clientVersion, Pointer reserved, IntByReference negotiatedVersion,
                PointerByReference clientHandle);
        int WlanCloseHandle(Pointer clientHandle, Pointer reserved);
        int WlanEnumInterfaces(Pointer clientHandle, Pointer reserved, PointerByReference interfaceList);
        int WlanScan(Pointer clientHandle, Guid.GUID interfaceGuid, Pointer ssid,
                Pointer ieData, Pointer reserved);
        int WlanGetNetworkBssList(Pointer clientHandle, Guid.GUID interfaceGuid, Pointer ssid,
                int bssType, int securityEnabled, Pointer reserved, PointerByReference bssList);
        void WlanFreeMemory(Pointer memory);
    }

    @Structure.FieldOrder({"interfaceGuid", "description", "state"})
    public static final class WlanInterfaceInfo extends Structure {
        public Guid.GUID interfaceGuid = new Guid.GUID();
        public char[] description = new char[256];
        public int state;
        public WlanInterfaceInfo() {}
        public WlanInterfaceInfo(Pointer pointer) { super(pointer); }
    }

    @Structure.FieldOrder({"length", "rates"})
    public static final class WlanRateSet extends Structure {
        public int length;
        public short[] rates = new short[126];
    }

    @Structure.FieldOrder({"ssidLength", "ssid"})
    public static final class Dot11Ssid extends Structure {
        public int ssidLength;
        public byte[] ssid = new byte[32];
    }

    @Structure.FieldOrder({"ssid", "phyId", "bssid", "bssType", "phyType", "rssi",
        "linkQuality", "inRegDomain", "beaconPeriod", "timestamp", "hostTimestamp",
        "capabilityInformation", "centerFrequency", "rateSet", "ieOffset", "ieSize"})
    public static final class WlanBssEntry extends Structure {
        public Dot11Ssid ssid = new Dot11Ssid();
        public int phyId;
        public byte[] bssid = new byte[6];
        public int bssType;
        public int phyType;
        public int rssi;
        public int linkQuality;
        public byte inRegDomain;
        public short beaconPeriod;
        public long timestamp;
        public long hostTimestamp;
        public short capabilityInformation;
        public int centerFrequency;
        public WlanRateSet rateSet = new WlanRateSet();
        public int ieOffset;
        public int ieSize;
        public WlanBssEntry() {}
        public WlanBssEntry(Pointer pointer) { super(pointer); }
    }
}
