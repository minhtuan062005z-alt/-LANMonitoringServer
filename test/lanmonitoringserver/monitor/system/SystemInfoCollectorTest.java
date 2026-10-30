package lanmonitoringserver.monitor.system;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import lanmonitoringserver.model.SystemInformation;
import lanmonitoringserver.protocol.SystemInfoMapper;
import lanmonitoringserver.util.CommandResult;
import lanmonitoringserver.util.CommandRunner;

public final class SystemInfoCollectorTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--sleep-helper".equals(args[0])) {
            Thread.sleep(5_000);
            return;
        }
        testWindowsCimParser();
        testLinuxParsers();
        testMissingCommandIsSafe();
        testCommandTimeout();
        testMapperRoundTrip();
        testCollectorOnCurrentOperatingSystem();
        System.out.println("SystemInfoCollectorTest: PASS");
    }

    private static void testWindowsCimParser() throws Exception {
        String json = """
                {"system":{"Manufacturer":"Example Corp","Model":"Workstation X",\
                "OSName":"Windows Test","OSVersion":"10.0","Architecture":"64-bit",\
                "TotalMemory":17179869184,"FreeMemoryKB":4194304},\
                "bios":{"Name":"UEFI","Version":"1.2.3"},\
                "cpu":{"Name":"Example CPU","Manufacturer":"GenuineVendor",\
                "Cores":8,"Logical":16,"MaxClockMHz":4200},\
                "gpus":[{"Name":"Example GPU","Vendor":"Example",\
                "Driver":"31.0","VRAM":4294967296}],\
                "storage":[{"Device":"C:","Label":"System",\
                "FileSystem":"NTFS","Size":1000000,"Free":400000}]}""";
        SystemInfoBuilder builder = JavaSystemProbe.baseline("test");
        WindowsCimParser.apply(json, builder);
        SystemInformation info = builder.build();
        check("Example Corp".equals(info.system().manufacturer()), "Windows manufacturer parser");
        check(info.cpu().physicalCores() == 8, "Windows CPU core parser");
        check(info.gpus().size() == 1, "Windows GPU parser");
        check(info.storage().get(0).usedBytes() == 600000, "Windows disk usage parser");

        SystemInfoBuilder missing = JavaSystemProbe.baseline("test-missing");
        WindowsCimParser.apply("{\"system\":{},\"bios\":{},\"cpu\":{},\"gpus\":[],\"storage\":[]}", missing);
        check(missing.build().system().hostname() != null, "missing CIM fields retain Java fallback");
    }

    private static void testLinuxParsers() {
        var osRelease = LinuxParsers.parseOsRelease("NAME=\"Example Linux\"\nVERSION_ID=\"24.04\"\n");
        check("Example Linux".equals(osRelease.get("NAME")), "Linux os-release parser");

        String cpuInfo = """
                processor : 0
                vendor_id : GenuineVendor
                model name : Example CPU
                physical id : 0
                core id : 0
                cpu cores : 2
                cpu MHz : 3200.000

                processor : 1
                vendor_id : GenuineVendor
                model name : Example CPU
                physical id : 0
                core id : 1
                cpu cores : 2
                cpu MHz : 3100.000
                """;
        SystemInfoBuilder builder = JavaSystemProbe.baseline("linux-parser-test");
        LinuxParsers.parseCpuInfo(cpuInfo, builder);
        check(builder.logicalProcessors == 2 && builder.physicalCores == 2, "Linux CPU topology parser");
        String lsblk = "NAME=\"sda\" MODEL=\"Example SSD\" TYPE=\"disk\" SIZE=\"1000000\" "
                + "ROTA=\"0\" FSTYPE=\"\" MOUNTPOINT=\"\" PKNAME=\"\"\n";
        LinuxParsers.parseLsblk(lsblk, builder);
        check("SSD / non-rotational".equals(builder.storage.get(0).mediaType()), "Linux lsblk media parser");
        LinuxParsers.parseLspci("\"00:02.0\" \"VGA compatible controller\" "
                + "\"Intel Corporation\" \"UHD Graphics 770\"\n", builder);
        check(!builder.gpus.isEmpty() && "Integrated".equals(builder.gpus.get(0).category()),
                "Linux lspci GPU parser");
    }

    private static void testMissingCommandIsSafe() {
        CommandResult result = new CommandRunner().run(
                java.util.List.of("lanmonitoring-command-that-does-not-exist"),
                Duration.ofSeconds(1), StandardCharsets.UTF_8);
        check(!result.successful() && !result.timedOut(), "missing optional tool is handled safely");
    }

    private static void testCommandTimeout() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "java.exe" : "java";
        String javaExecutable = java.nio.file.Path.of(
                System.getProperty("java.home"), "bin", executable).toString();
        CommandResult result = new CommandRunner().run(java.util.List.of(javaExecutable, "-cp",
                System.getProperty("java.class.path"), SystemInfoCollectorTest.class.getName(),
                "--sleep-helper"), Duration.ofMillis(100), StandardCharsets.UTF_8);
        check(result.timedOut(), "long-running OS command is terminated by timeout");
    }

    private static void testMapperRoundTrip() throws Exception {
        SystemInformation original = new GenericSystemInfoCollector().collect();
        SystemInformation restored = SystemInfoMapper.fromJson(SystemInfoMapper.toJson(original));
        check(original.equals(restored), "structured SYSTEM_INFO JSON round-trip");
    }

    private static void testCollectorOnCurrentOperatingSystem() {
        String os = System.getProperty("os.name", "");
        SystemInfoCollector collector = SystemInfoCollectorFactory.create();
        SystemInformation info = collector.collect();
        check(info.system().hostname() != null && !info.system().hostname().isBlank(), "real hostname");
        check(info.system().osName() != null && !info.system().osName().isBlank(), "real OS name");
        check(info.system().javaVersion() != null, "real Java version");
        check(info.memory().totalBytes() != null && info.memory().totalBytes() > 0, "real total RAM");
        check(info.cpu().logicalProcessors() != null && info.cpu().logicalProcessors() > 0,
                "real logical processor count");
        if (os.toLowerCase(Locale.ROOT).contains("windows")) {
            check(collector instanceof WindowsSystemInfoCollector, "Windows collector auto-detection");
            String cimFailure = info.unavailableReasons().get("windows.cim");
            if (cimFailure == null) {
                check(info.cpu().model() != null, "Windows CIM CPU model");
                check(info.system().manufacturer() != null, "Windows CIM manufacturer");
                check(info.system().firmwareVersion() != null, "Windows CIM BIOS/UEFI version");
                check(!info.gpus().isEmpty(), "Windows CIM GPU inventory");
            } else {
                check(!cimFailure.isBlank(), "Windows CIM failure has a diagnostic reason");
                check(info.unavailableReasons().containsKey("cpu.model"),
                        "Windows fallback explains missing CPU model");
                System.out.println("Windows CIM unavailable; Java fallback verified: " + cimFailure);
            }
            check(!info.storage().isEmpty(), "Windows storage inventory or Java fallback");
            check(!info.network().isEmpty(), "Windows network inventory");
            System.out.println("Windows collector verified: " + info.collector());
        } else if (os.toLowerCase(Locale.ROOT).contains("linux")) {
            check(collector instanceof LinuxSystemInfoCollector, "Linux collector auto-detection");
            System.out.println("Linux collector verified in this environment: " + info.collector());
        } else {
            check(collector instanceof GenericSystemInfoCollector, "generic collector fallback");
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }
}
