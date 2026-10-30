package lanmonitoringserver.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ProtocolMessage {
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final MessageType type;
    private final String requestId;
    private final String clientId;
    private final long timestamp;
    private final Map<String, Object> payload;

    public ProtocolMessage(int version, MessageType type, String requestId, String clientId,
            long timestamp, Map<String, Object> payload) {
        this.version = version;
        this.type = type;
        this.requestId = requestId;
        this.clientId = clientId;
        this.timestamp = timestamp;
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static ProtocolMessage create(MessageType type, String clientId, Map<String, Object> payload) {
        return new ProtocolMessage(CURRENT_VERSION, type, UUID.randomUUID().toString(), clientId,
                System.currentTimeMillis(), payload == null ? Map.of() : payload);
    }

    public static ProtocolMessage reply(MessageType type, ProtocolMessage request,
            String clientId, Map<String, Object> payload) {
        return new ProtocolMessage(CURRENT_VERSION, type, request.requestId, clientId,
                System.currentTimeMillis(), payload == null ? Map.of() : payload);
    }

    public String toJson() throws ProtocolException {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("version", version);
        object.put("type", type.name());
        object.put("requestId", requestId);
        object.put("clientId", clientId);
        object.put("timestamp", timestamp);
        object.put("payload", payload);
        return JsonCodec.stringify(object);
    }

    @SuppressWarnings("unchecked")
    public static ProtocolMessage fromJson(String json) throws ProtocolException {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new ProtocolException("Protocol root must be an object");
        Object versionValue = raw.get("version");
        Object typeValue = raw.get("type");
        Object requestIdValue = raw.get("requestId");
        Object clientIdValue = raw.get("clientId");
        Object timestampValue = raw.get("timestamp");
        Object payloadValue = raw.get("payload");
        if (!(versionValue instanceof Number) || !(typeValue instanceof String)
                || !(requestIdValue instanceof String) || !(clientIdValue instanceof String)
                || !(timestampValue instanceof Number) || !(payloadValue instanceof Map<?, ?>)) {
            throw new ProtocolException("Missing or invalid protocol fields");
        }
        int version = ((Number) versionValue).intValue();
        if (version != CURRENT_VERSION) throw new ProtocolException("Unsupported protocol version: " + version);
        MessageType type;
        try {
            type = MessageType.valueOf((String) typeValue);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Unknown message type: " + typeValue, exception);
        }
        String requestId = (String) requestIdValue;
        String clientId = (String) clientIdValue;
        if (requestId.isBlank() || requestId.length() > 128 || clientId.length() > 128) {
            throw new ProtocolException("Invalid requestId or clientId length");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) payloadValue).entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new ProtocolException("Payload key must be a string");
            payload.put(key, entry.getValue());
        }
        return new ProtocolMessage(version, type, requestId, clientId,
                ((Number) timestampValue).longValue(), payload);
    }

    public String requireString(String key, int maxLength) throws ProtocolException {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new ProtocolException("Invalid payload field: " + key);
        }
        return text;
    }

    public double requireDouble(String key, double minimum, double maximum) throws ProtocolException {
        Object value = payload.get(key);
        if (!(value instanceof Number number)) throw new ProtocolException("Invalid payload field: " + key);
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw new ProtocolException("Payload field out of range: " + key);
        }
        return result;
    }

    public long requireLong(String key, long minimum, long maximum) throws ProtocolException {
        Object value = payload.get(key);
        if (!(value instanceof Number number)) throw new ProtocolException("Invalid payload field: " + key);
        double candidate = number.doubleValue();
        long result = number.longValue();
        if (!Double.isFinite(candidate) || candidate != result || result < minimum || result > maximum) {
            throw new ProtocolException("Payload field out of range: " + key);
        }
        return result;
    }

    public boolean requireBoolean(String key) throws ProtocolException {
        Object value = payload.get(key);
        if (!(value instanceof Boolean result)) throw new ProtocolException("Invalid payload field: " + key);
        return result;
    }

    public String optionalString(String key, int maxLength, String defaultValue) throws ProtocolException {
        Object value = payload.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) {
            throw new ProtocolException("Invalid payload field: " + key);
        }
        return text;
    }

    public Double optionalDouble(String key, double minimum, double maximum) throws ProtocolException {
        Object value = payload.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new ProtocolException("Invalid payload field: " + key);
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw new ProtocolException("Payload field out of range: " + key);
        }
        return result;
    }

    public int version() { return version; }
    public MessageType type() { return type; }
    public String requestId() { return requestId; }
    public String clientId() { return clientId; }
    public long timestamp() { return timestamp; }
    public Map<String, Object> payload() { return payload; }
}
