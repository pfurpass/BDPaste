package de.phillip.bdpaste.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, forgiving SNBT reader.
 *
 * <p>It understands everything BDEngine and vanilla {@code /summon} put on the wire:
 * compounds, lists, typed arrays ({@code [I;..]}), single and double quoted strings,
 * bare strings and numbers with type suffixes.</p>
 *
 * <p>Values come back as {@link Map}, {@link List}, {@link String}, {@link Number}
 * or {@link Boolean}.</p>
 */
public final class Snbt {

    private final String src;
    private int pos;

    private Snbt(String src) {
        this.src = src;
    }

    /** Parses the first value in {@code text}; trailing content is ignored. */
    public static Object parse(String text) {
        if (text == null) return null;
        Snbt r = new Snbt(text);
        r.ws();
        if (r.eof()) return null;
        return r.value();
    }

    /** Parses {@code text} and returns it as a compound, or an empty map on anything unexpected. */
    public static Map<String, Object> compound(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            Object v = parse(text);
            return v instanceof Map<?, ?> m ? cast(m) : Map.of();
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    /** Reads the compound that starts at {@code from}, which is how we pull the tag out of a summon line. */
    public static Map<String, Object> compoundAt(String text, int from) {
        Snbt r = new Snbt(text);
        r.pos = from;
        r.ws();
        Object v = r.value();
        return v instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    public static Map<String, Object> mapOf(Object o) {
        return o instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    public static List<Object> listOf(Object o) {
        return o instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    public static String stringOf(Object o, String def) {
        if (o == null) return def;
        return o instanceof String s ? s : String.valueOf(o);
    }

    public static Double doubleOf(Object o, Double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Boolean b) return b ? 1d : 0d;
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.replaceAll("[bBsSlLfFdD]$", ""));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static Integer intOf(Object o, Integer def) {
        Double d = doubleOf(o, null);
        // Deliberately not a ternary: mixing Integer and int in one would unbox def and
        // blow up with an NPE whenever the caller passes null as the default.
        if (d == null) return def;
        return (int) Math.round(d);
    }

    public static Boolean boolOf(Object o, Boolean def) {
        if (o instanceof Boolean b) return b;
        Double d = doubleOf(o, null);
        if (d == null) return def;
        return d != 0d;
    }

    // ----------------------------------------------------------------- reader

    private boolean eof() {
        return pos >= src.length();
    }

    private char peek() {
        if (eof()) throw new IllegalArgumentException("Unexpected end of SNBT at " + pos);
        return src.charAt(pos);
    }

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private void expect(char c) {
        ws();
        if (eof() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected " + c + " at " + pos + " in SNBT");
        }
        pos++;
    }

    private Object value() {
        ws();
        char c = peek();
        return switch (c) {
            case '{' -> readCompound();
            case '[' -> readList();
            case '"', '\'' -> readQuoted();
            default -> readBare();
        };
    }

    private Map<String, Object> readCompound() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            char c = peek();
            String key = (c == '"' || c == '\'') ? readQuoted() : readBareToken(false);
            expect(':');
            out.put(key, value());
            ws();
            if (peek() == ',') {
                pos++;
                ws();
                if (peek() == '}') { // tolerate a trailing comma
                    pos++;
                    return out;
                }
                continue;
            }
            expect('}');
            return out;
        }
    }

    private List<Object> readList() {
        expect('[');
        // typed array marker, e.g. [I; 1,2,3]
        if (pos + 1 < src.length() && "IBLZ".indexOf(src.charAt(pos)) >= 0 && src.charAt(pos + 1) == ';') {
            pos += 2;
        }
        List<Object> out = new ArrayList<>();
        ws();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            ws();
            if (peek() == ',') {
                pos++;
                ws();
                if (peek() == ']') {
                    pos++;
                    return out;
                }
                continue;
            }
            expect(']');
            return out;
        }
    }

    private String readQuoted() {
        char quote = peek();
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) throw new IllegalArgumentException("Unterminated string in SNBT");
            char c = src.charAt(pos++);
            if (c == '\\') {
                if (eof()) throw new IllegalArgumentException("Dangling escape in SNBT");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = src.substring(pos, Math.min(pos + 4, src.length()));
                        pos += hex.length();
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> sb.append(esc);
                }
            } else if (c == quote) {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
    }

    /**
     * Reads an unquoted token. Values may contain a colon so {@code minecraft:stone} stays in one
     * piece; keys must not, or the colon separating key and value gets swallowed.
     */
    private String readBareToken(boolean allowColon) {
        ws();
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+'
                    || (allowColon && c == ':');
            if (!ok) break;
            pos++;
        }
        if (start == pos) throw new IllegalArgumentException("Empty token at " + pos + " in SNBT");
        return src.substring(start, pos);
    }

    private Object readBare() {
        String token = readBareToken(true);
        if (token.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (token.equalsIgnoreCase("false")) return Boolean.FALSE;

        char suffix = token.charAt(token.length() - 1);
        String body = token.substring(0, token.length() - 1);
        try {
            switch (suffix) {
                case 'b', 'B' -> {
                    return Byte.parseByte(body);
                }
                case 's', 'S' -> {
                    return Short.parseShort(body);
                }
                case 'l', 'L' -> {
                    return Long.parseLong(body);
                }
                case 'f', 'F' -> {
                    return Float.parseFloat(body);
                }
                case 'd', 'D' -> {
                    return Double.parseDouble(body);
                }
                default -> {
                    // not a suffixed number, fall through to the plain parse below
                }
            }
        } catch (NumberFormatException ignored) {
            return token;
        }
        try {
            return token.contains(".") || token.contains("e") || token.contains("E")
                    ? (Object) Double.parseDouble(token)
                    : (Object) Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return token;
        }
    }
}
