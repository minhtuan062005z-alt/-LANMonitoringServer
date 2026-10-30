package lanmonitoringserver.protocol;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON codec nhỏ, không phụ thuộc thư viện ngoài, dùng cho object protocol. */
public final class JsonCodec {
    private JsonCodec() {}

    public static String stringify(Object value) throws ProtocolException {
        StringBuilder result = new StringBuilder();
        appendValue(result, value);
        return result.toString();
    }

    public static Object parse(String json) throws ProtocolException {
        if (json == null) {
            throw new ProtocolException("JSON must not be null");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing data");
        }
        return value;
    }

    private static void appendValue(StringBuilder out, Object value) throws ProtocolException {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            appendString(out, text);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (!Double.isFinite(doubleValue)) {
                throw new ProtocolException("JSON number must be finite");
            }
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ProtocolException("JSON object keys must be strings");
                }
                if (!first) out.append(',');
                first = false;
                appendString(out, key);
                out.append(':');
                appendValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                appendValue(out, item);
            }
            out.append(']');
        } else {
            throw new ProtocolException("Unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input;
        }

        private Object parseValue() throws ProtocolException {
            skipWhitespace();
            if (atEnd()) throw error("Expected JSON value");
            return switch (input.charAt(position)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private List<Object> parseArray() throws ProtocolException {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return array;
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (consume(']')) return array;
                expect(',');
            }
        }

        private Map<String, Object> parseObject() throws ProtocolException {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return object;
            while (true) {
                skipWhitespace();
                if (atEnd() || input.charAt(position) != '"') throw error("Expected object key");
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) return object;
                expect(',');
            }
        }

        private String parseString() throws ProtocolException {
            expect('"');
            StringBuilder text = new StringBuilder();
            while (!atEnd()) {
                char character = input.charAt(position++);
                if (character == '"') return text.toString();
                if (character == '\\') {
                    if (atEnd()) throw error("Unterminated escape sequence");
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> text.append(escaped);
                        case 'b' -> text.append('\b');
                        case 'f' -> text.append('\f');
                        case 'n' -> text.append('\n');
                        case 'r' -> text.append('\r');
                        case 't' -> text.append('\t');
                        case 'u' -> text.append(parseUnicode());
                        default -> throw error("Invalid escape sequence");
                    }
                } else {
                    if (character < 0x20) throw error("Control character in string");
                    text.append(character);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicode() throws ProtocolException {
            if (position + 4 > input.length()) throw error("Incomplete unicode escape");
            try {
                char value = (char) Integer.parseInt(input.substring(position, position + 4), 16);
                position += 4;
                return value;
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private Object parseNumber() throws ProtocolException {
            int start = position;
            if (consume('-') && atEnd()) throw error("Invalid number");
            if (consume('0')) {
                // zero must not be followed by another integer digit
            } else {
                consumeDigits(true);
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                consumeDigits(true);
            }
            if (!atEnd() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (!atEnd() && (input.charAt(position) == '+' || input.charAt(position) == '-')) position++;
                consumeDigits(true);
            }
            if (start == position) throw error("Expected JSON value");
            String number = input.substring(start, position);
            try {
                return decimal ? Double.valueOf(number) : Long.valueOf(number);
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private void consumeDigits(boolean requireOne) throws ProtocolException {
            int start = position;
            while (!atEnd() && Character.isDigit(input.charAt(position))) position++;
            if (requireOne && start == position) throw error("Expected digit");
        }

        private Object parseLiteral(String literal, Object value) throws ProtocolException {
            if (!input.startsWith(literal, position)) throw error("Invalid literal");
            position += literal.length();
            return value;
        }

        private void expect(char expected) throws ProtocolException {
            skipWhitespace();
            if (atEnd() || input.charAt(position) != expected) throw error("Expected '" + expected + "'");
            position++;
        }

        private boolean consume(char expected) {
            if (!atEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(position))) position++;
        }

        private boolean atEnd() {
            return position >= input.length();
        }

        private ProtocolException error(String message) {
            return new ProtocolException(message + " at character " + position);
        }
    }
}
