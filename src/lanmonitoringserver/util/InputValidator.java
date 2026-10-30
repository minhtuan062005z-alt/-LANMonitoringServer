package lanmonitoringserver.util;

import lanmonitoringserver.protocol.ProtocolException;

public final class InputValidator {
    public static final int MAX_HOSTNAME_LENGTH = 255;
    public static final int MAX_IP_LENGTH = 64;
    public static final int MAX_MESSAGE_LENGTH = 64 * 1024;

    private InputValidator() {}

    public static int port(String text) {
        int port;
        try {
            port = Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port phải là số nguyên từ 1 đến 65535.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port phải nằm trong khoảng 1-65535.");
        }
        return port;
    }

    public static String message(String text) {
        String message = text == null ? "" : text.trim();
        if (message.isEmpty()) throw new IllegalArgumentException("Nội dung thông điệp không được trống.");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Thông điệp vượt quá 64 KiB ký tự.");
        }
        return message;
    }

    public static void protocolClientId(String clientId) throws ProtocolException {
        if (clientId == null || clientId.isBlank() || clientId.length() > 128
                || !clientId.matches("[A-Za-z0-9._:@#-]+")) {
            throw new ProtocolException("Invalid clientId");
        }
    }
}
