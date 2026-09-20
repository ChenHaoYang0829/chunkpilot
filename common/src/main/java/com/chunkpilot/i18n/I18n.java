package com.chunkpilot.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ChunkPilot 国际化 (i18n) 运行时.
 *
 * ============================ 设计要点 ============================
 * 1. **服务端自己解析**. CP 的绝大部分输出是给控制台/RCON/命令方块看的, 那些地方没有客户端,
 *    `Component.translatable` 到不了任何人手里。所以语言文件由本类从 classpath 直接读取,
 *    `tr()` 返回**已经解析好的纯文本 String**(内含 § 颜色码), 与旧代码的 String 接口完全兼容,
 *    命令层改动最小、风险最低。
 * 2. **同时兼容标准资源路径**. 语言文件放在 `assets/chunkpilot/lang/*.json` (Minecraft 惯例),
 *    因此将来任何一处改用 `Component.translatable("chunkpilot.xxx")` 都能直接被客户端本地化,
 *    不需要再搬一次文件。
 * 3. **按玩家语言**. 服务端能拿到每个客户端的语言 (`ServerPlayer.clientInformation().language()`,
 *    来自 `serverbound/client_information` 包)。所以**玩家用中文、控制台用英文**可以同时成立,
 *    而且**玩家不需要装 CP 客户端**。
 * 4. **绝不因为翻译出错而崩**. 缺 key → 回退 en_us → 再回退 key 本身;
 *    译文占位符写坏 (`%1$s` 丢失) → 回退英文串; 语言文件坏 JSON → 回退空表。任何一条都不会抛异常。
 *
 * ============================ 语言选择优先级 ============================
 *   `[general] language` = `"auto"` (默认):
 *       玩家消息 → 该玩家客户端语言 (拿不到则 en_us)
 *       控制台/日志 → en_us
 *   `[general] language` = `"zh_cn"` / `"en_us"` / ...:
 *       **全部**输出都强制用该语言 (含玩家消息)。
 *   运行时可用 `/chunkpilot lang <code>` 临时覆盖 (不写回配置文件)。
 */
public final class I18n {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    public static final String AUTO = "auto";
    public static final String FALLBACK_LANG = "en_us";

    /** 随 jar 发布的语言 (顺序 = /chunkpilot lang 列表顺序) */
    private static final List<String> SHIPPED = List.of("en_us", "zh_cn");

    private static final String RESOURCE = "assets/chunkpilot/lang/%s.json";

    /** code → 语言表. 只增不减 (语言文件很小, 没必要淘汰). */
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();
    /** 已告警过的 key, 避免刷屏. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 控制台/日志/全局默认语言. */
    private static volatile String globalLang = FALLBACK_LANG;
    /** true = 显式指定了语言, 玩家消息也强制用它 (不再按客户端语言). */
    private static volatile boolean forced = false;

    /** 由 ChunkPilot 注入: 玩家 UUID → 客户端语言代码 (拿不到返回 null). */
    private static volatile Function<UUID, String> playerLocaleResolver = id -> null;

    private I18n() {}

    // ================= 配置 / 语言选择 =================

    /** 由 `[general] language` 调用. "auto"/null/空 = 自动. */
    public static void configure(String language) {
        if (language == null || language.isBlank() || AUTO.equalsIgnoreCase(language.trim())) {
            forced = false;
            globalLang = FALLBACK_LANG;
        } else {
            forced = true;
            globalLang = normalize(language);
        }
        LOG.info("[ChunkPilot] i18n: language={} ({}), available={}",
            language == null ? AUTO : language, forced ? globalLang : "auto/console=" + globalLang, SHIPPED);
    }

    /** 运行时覆盖 (`/chunkpilot lang <code>`). */
    public static void override(String language) {
        configure(language);
    }

    public static void setPlayerLocaleResolver(Function<UUID, String> resolver) {
        playerLocaleResolver = resolver == null ? id -> null : resolver;
    }

    public static boolean isForced() { return forced; }

    /** 控制台/日志当前使用的语言代码. */
    public static String language() { return globalLang; }

    public static List<String> availableLanguages() { return SHIPPED; }

    /**
     * 把任意客户端语言码归一化到我们发布的语言.
     * `zh-cn` / `zh_Hans_CN` / `zh_tw` → `zh_cn`; `en_GB` / `en` → `en_us`; 其它原样小写化.
     */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) return FALLBACK_LANG;
        String c = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (c.startsWith("zh")) return "zh_cn";
        if (c.startsWith("en")) return "en_us";
        return c;
    }

    // ================= 翻译 =================

    /** 用"当前语言"(控制台语言 / 已强制语言) 翻译. */
    public static String tr(String key, Object... args) {
        return tr(globalLang, key, args);
    }

    /**
     * 用**某个玩家的客户端语言**翻译; 语言被显式强制或拿不到客户端语言时退回 {@link #tr}.
     *
     * 命令处理里应优先用这个 (命令执行者可能是玩家, 也可能是控制台).
     */
    public static String trFor(UUID playerId, String key, Object... args) {
        if (!forced && playerId != null) {
            try {
                String lang = playerLocaleResolver.apply(playerId);
                if (lang != null && !lang.isBlank()) return tr(normalize(lang), key, args);
            } catch (Throwable ignored) {
                // 解析客户端语言失败不影响输出
            }
        }
        return tr(key, args);
    }

    /** 指定语言翻译 (lang 会被归一化). */
    public static String tr(String lang, String key, Object... args) {
        String code = normalize(lang);
        String pattern = lookup(code, key);
        if (pattern == null) {
            pattern = lookup(FALLBACK_LANG, key);
        }
        if (pattern == null) {
            warnOnce("missing key: " + key);
            return key;
        }
        if (args == null || args.length == 0) return pattern;
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            // 译文把占位符写坏了 (例如漏掉 %2$s) —— 退回英文, 绝不让命令抛异常
            String fb = lookup(FALLBACK_LANG, key);
            if (fb != null && !fb.equals(pattern)) {
                try {
                    return String.format(fb, args);
                } catch (RuntimeException ignored) {
                    // 英文也坏 → 下面统一处理
                }
            }
            warnOnce("bad format: " + key + " in " + code + " → " + e);
            return pattern;
        }
    }

    /** key 在当前语言(或英文)里是否存在. */
    public static boolean has(String key) {
        return lookup(globalLang, key) != null || lookup(FALLBACK_LANG, key) != null;
    }

    /** 该语言的显示名 (给 `/chunkpilot lang` 列表用). */
    public static String displayName(String code) {
        String name = lookup(normalize(code), "chunkpilot.lang.name");
        return name == null ? normalize(code) : name;
    }

    /** 语言表快照 (只读) —— 给"缺 key 自检"之类的工具用. */
    public static Map<String, String> table(String code) {
        return Collections.unmodifiableMap(rawTable(normalize(code)));
    }

    /** 清空缓存 (测试 / 热重载语言文件用). */
    public static void clearCache() {
        CACHE.clear();
        WARNED.clear();
    }

    // ================= 内部 =================

    private static String lookup(String code, String key) {
        if (key == null) return null;
        return rawTable(code).get(key);
    }

    private static Map<String, String> rawTable(String code) {
        Map<String, String> cached = CACHE.get(code);
        if (cached != null) return cached;
        Map<String, String> loaded = JsonLangReader.readResource(String.format(RESOURCE, code));
        if (loaded.isEmpty() && !FALLBACK_LANG.equals(code)) {
            warnOnce("language file not found or empty: " + code);
        }
        CACHE.put(code, loaded);
        return loaded;
    }

    private static void warnOnce(String msg) {
        if (WARNED.size() > 500) WARNED.clear();
        if (WARNED.add(msg)) LOG.warn("[ChunkPilot] i18n: {}", msg);
    }

    /** 仅测试用: 直接注入一张语言表. */
    static void putTable(String code, Map<String, String> table) {
        CACHE.put(normalize(code), new LinkedHashMap<>(table));
    }

    /** 缺 key 自检: 返回 en_us 有而 target 没有的 key 列表 (发布前跑一遍). */
    public static List<String> missingKeys(String target) {
        List<String> missing = new ArrayList<>();
        Map<String, String> en = rawTable(FALLBACK_LANG);
        Map<String, String> other = rawTable(normalize(target));
        for (String k : en.keySet()) {
            if (!other.containsKey(k)) missing.add(k);
        }
        return missing;
    }
}
