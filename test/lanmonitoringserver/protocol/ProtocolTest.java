package lanmonitoringserver.protocol;

import java.util.Map;

public final class ProtocolTest {
    public static void main(String[] args) throws Exception {
        roundTripUnicodeAndDelimiters();
        rejectMalformedJson();
        rejectUnsupportedVersion();
        validatePayloadTypesAndRanges();
        fileChunkRoundTripAndIntegerValidation();
        System.out.println("ProtocolTest: PASS");
    }

    private static void roundTripUnicodeAndDelimiters() throws Exception {
        String content = "Xin chào | LAN\nDữ liệu UTF-8: đồ án 🖥";
        ProtocolMessage original = ProtocolMessage.create(MessageType.MESSAGE, "client-01",
                Map.of("content", content, "enabled", true, "count", 3));
        ProtocolMessage decoded = ProtocolMessage.fromJson(original.toJson());
        check(decoded.type() == MessageType.MESSAGE, "message type");
        check(decoded.requestId().equals(original.requestId()), "request id");
        check(decoded.clientId().equals(original.clientId()), "client id");
        check(content.equals(decoded.payload().get("content")), "unicode content");
    }

    private static void rejectMalformedJson() {
        expectFailure(() -> ProtocolMessage.fromJson("{not-json}"), "malformed JSON");
    }

    private static void rejectUnsupportedVersion() throws Exception {
        String json = "{\"version\":99,\"type\":\"PING\",\"requestId\":\"r\","
                + "\"clientId\":\"c\",\"timestamp\":1,\"payload\":{}}";
        expectFailure(() -> ProtocolMessage.fromJson(json), "unsupported version");
    }

    private static void validatePayloadTypesAndRanges() {
        ProtocolMessage heartbeat = ProtocolMessage.create(MessageType.HEARTBEAT, "client",
                Map.of("cpuPercent", 101.0));
        expectFailure(() -> heartbeat.requireDouble("cpuPercent", 0, 100), "range validation");
        expectFailure(() -> heartbeat.requireString("cpuPercent", 20), "type validation");
    }

    private static void fileChunkRoundTripAndIntegerValidation() throws Exception {
        String data = java.util.Base64.getEncoder().encodeToString(new byte[]{0, 1, 2, (byte) 255});
        ProtocolMessage chunk = ProtocolMessage.create(MessageType.FILE_CHUNK, "client",
                Map.of("transferId", "transfer", "offset", 4L, "data", data));
        ProtocolMessage decoded = ProtocolMessage.fromJson(chunk.toJson());
        check(decoded.type() == MessageType.FILE_CHUNK, "file chunk message type");
        check(decoded.requireLong("offset", 0, 10) == 4, "integer offset");
        check(data.equals(decoded.requireString("data", 100)), "binary-safe Base64 payload");
        ProtocolMessage fractional = ProtocolMessage.create(MessageType.FILE_CHUNK, "client",
                Map.of("offset", 1.5));
        expectFailure(() -> fractional.requireLong("offset", 0, 10), "fractional integer rejected");
    }

    private static void expectFailure(CheckedRunnable action, String description) {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + description);
        } catch (ProtocolException expected) {
            // expected
        } catch (Exception exception) {
            throw new AssertionError("Wrong exception for " + description, exception);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Failed assertion: " + description);
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }
}
