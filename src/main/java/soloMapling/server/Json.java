package soloMapling.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the bot systems' small fixed shapes: an OpenAI-compatible chat call's body and reply,
 * and the roster's activity state. The project carries no JSON library.
 * Values are {@code String}, {@code Number}, {@code Boolean}, {@code null}, {@code Map} or {@code List};
 * a {@link Raw} is written verbatim (a config fragment that is already JSON).
 */
public final class Json {

    /** A JSON fragment that is written as-is. */
    public record Raw(String json) {
    }

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Raw raw) {
            out.append(raw.json());
        } else if (value instanceof String s) {
            quote(s, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(list.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Parses a JSON document into the value types listed on the class. */
    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.pos != json.length()) {
            throw parser.error("trailing content");
        }
        return value;
    }

    /** Checks that a config fragment is one well-formed JSON value. */
    public static boolean isValid(String json) {
        try {
            parse(json);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("short unicode escape");
                        }
                        out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        private Number number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String text = s.substring(start, pos);
            if (text.isEmpty()) {
                throw error("unexpected character");
            }
            try {
                if (text.contains(".") || text.contains("e") || text.contains("E")) {
                    return Double.parseDouble(text);
                }
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw error("bad number " + text);
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("JSON " + what + " at " + pos);
        }
    }
}
