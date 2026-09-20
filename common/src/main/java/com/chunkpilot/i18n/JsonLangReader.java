package com.chunkpilot.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 读取器 —— 只服务于 ChunkPilot 的语言文件。
 *
 * 为什么不用 Gson: common 模块刻意保持"零第三方依赖"(ConfigLoader 也是手写 TOML 解析),
 * 这样单元测试不需要 MC classpath, 也不会因为 MC 版本换 Gson 版本而炸。
 *
 * 支持: 顶层扁平对象 {@code {"a":"b", "c":"d"}}; 字符串转义 (引号/反斜杠/斜杠/b/f/n/r/t 以及 u 开头的四位码点);
 * 遇到嵌套对象/数组/数字/true/false/null 会**整段跳过**(语言文件用不到, 但坏文件不该让服务器起不来)。
 * 解析失败一律返回空表 —— 调用方会回退到 en_us / key 本身, 绝不抛异常打断命令。
 */
final class JsonLangReader {

    private JsonLangReader() {}

    /** 从 classpath 读取语言文件; 不存在或解析失败返回空表. */
    static Map<String, String> readResource(String path) {
        ClassLoader cl = JsonLangReader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) return Map.of();
            byte[] raw = in.readAllBytes();
            String text = new String(raw, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // 去 BOM
            return parse(text);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    static Map<String, String> parse(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        Cursor c = new Cursor(json);
        c.skipWs();
        if (!c.eat('{')) return out;
        while (true) {
            c.skipWs();
            if (c.eat('}')) break;
            if (c.eof()) break;
            if (c.peek() != '"') { c.skipValue(); c.skipWs(); if (!c.eat(',')) break; continue; }
            String key = c.readString();
            c.skipWs();
            if (!c.eat(':')) break;
            c.skipWs();
            if (c.peek() == '"') {
                String value = c.readString();
                if (key != null && value != null && !key.isEmpty()) out.put(key, value);
            } else {
                c.skipValue();
            }
            c.skipWs();
            if (!c.eat(',')) { c.skipWs(); c.eat('}'); break; }
        }
        return out;
    }

    /** 一个极简游标; 所有越界都返回安全值而不是抛异常. */
    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }

        char peek() { return eof() ? '\0' : s.charAt(i); }

        void skipWs() {
            while (!eof()) {
                char ch = s.charAt(i);
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') i++;
                else break;
            }
        }

        boolean eat(char ch) {
            if (peek() == ch) { i++; return true; }
            return false;
        }

        /** 读取一个 JSON 字符串 (不含引号); 失败返回 null. */
        String readString() {
            if (!eat('"')) return null;
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char ch = s.charAt(i++);
                if (ch == '"') return sb.toString();
                if (ch != '\\') { sb.append(ch); continue; }
                if (eof()) break;
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) { /* 丢弃坏转义 */ }
                        }
                    }
                    default -> sb.append(esc);
                }
            }
            return sb.toString();
        }

        /** 跳过一个值 (对象/数组/数字/字面量). */
        void skipValue() {
            skipWs();
            char ch = peek();
            if (ch == '{' || ch == '[') {
                char close = ch == '{' ? '}' : ']';
                int depth = 0;
                while (!eof()) {
                    char c2 = s.charAt(i);
                    if (c2 == '"') { readString(); continue; }
                    if (c2 == ch) depth++;
                    else if (c2 == close) { depth--; i++; if (depth <= 0) return; continue; }
                    i++;
                }
                return;
            }
            while (!eof()) {
                char c2 = s.charAt(i);
                if (c2 == ',' || c2 == '}' || c2 == ']') return;
                i++;
            }
        }
    }
}
