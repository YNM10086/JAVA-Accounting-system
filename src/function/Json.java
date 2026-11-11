package function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：只覆盖本项目需要的解析与转义。
 * 项目里没有 JSON 库，AI 接口的请求/响应都靠它，避免引入第三方依赖。
 */
public final class Json {

    private Json() {}

    /** 解析 JSON 文本；对象→Map，数组→List，数字→Double，字符串→String，true/false→Boolean，null→null */
    public static Object parse(String s) {
        if (s == null) return null;
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    /** 字符串转义（不含首尾引号） */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return (o instanceof List<?> l) ? (List<Object>) l : new ArrayList<>();
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    public static int i(Object o, int def) {
        return (int) Math.round(num(o, def));
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return number();
            }
        }

        private void expect(String w) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("JSON: 期望 " + w + " @" + i);
            i += w.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("JSON: 缺冒号 @" + i);
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                throw new IllegalArgumentException("JSON: 对象未闭合 @" + i);
            }
            return m;
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (i < s.length()) {
                a.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                throw new IllegalArgumentException("JSON: 数组未闭合 @" + i);
            }
            return a;
        }

        private String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("JSON: 期望字符串 @" + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"'  -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/'  -> b.append('/');
                        case 'b'  -> b.append('\b');
                        case 'f'  -> b.append('\f');
                        case 'n'  -> b.append('\n');
                        case 'r'  -> b.append('\r');
                        case 't'  -> b.append('\t');
                        case 'u'  -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("JSON: \\u 不完整 @" + i);
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("JSON: 字符串未闭合");
        }

        private Double number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("JSON: 非法字符 '" + s.charAt(i) + "' @" + i);
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
