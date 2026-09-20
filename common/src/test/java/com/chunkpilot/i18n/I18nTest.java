package com.chunkpilot.i18n;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * i18n 契约测试.
 *
 * 翻译最容易出的两类事故:
 *   ① 加了新 key 只改了英文 → 中文界面出现英文原文 (或反过来中文 key 多出来没人用);
 *   ② 译文手滑写坏占位符 (漏掉 %2$s / 多写一个 %s) → 运行时 `String.format` 抛异常,
 *      命令直接炸给玩家看.
 * 这两类都在这里静态查出来, 不让它们进游戏。
 */
class I18nTest {

    private static Map<String, String> en() {
        return I18n.table("en_us");
    }

    private static Map<String, String> zh() {
        return I18n.table("zh_cn");
    }

    @Test
    void languageFilesAreNotMissing() {
        assertFalse(en().isEmpty(), "en_us.json 没被读到 (资源路径/打包有问题)");
        assertFalse(zh().isEmpty(), "zh_cn.json 没被读到");
    }

    @Test
    void everyShippedLanguageHasTheSameKeys() {
        for (String code : I18n.availableLanguages()) {
            List<String> missing = I18n.missingKeys(code);
            assertTrue(missing.isEmpty(), code + " 缺少这些 key (以 en_us 为基准): " + missing);
        }
        // 反向: 中文不能有 en_us 里没有的 key
        TreeSet<String> extra = new TreeSet<>(zh().keySet());
        extra.removeAll(en().keySet());
        assertTrue(extra.isEmpty(), "zh_cn.json 有多余的 key: " + extra);
    }

    @Test
    void everyKeyIsNamespaced() {
        for (String key : en().keySet()) {
            assertTrue(key.startsWith("chunkpilot."), "key 必须以 chunkpilot. 开头: " + key);
        }
    }

    /**
     * 两种语言的占位符必须**同一套** (索引 → 类型), 否则中文会拿到英文的参数或直接抛异常。
     * 注意只比较集合不比较顺序 —— 中文语序与英文不同, 允许 (也应该) 重排 `%2$s %1$s`。
     */
    @Test
    void placeholderSignaturesMatch() {
        Pattern p = Pattern.compile("%(\\d+)\\$([sdf])|%(\\d+)([sdf])");
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : en().entrySet()) {
            String key = e.getKey();
            String other = zh().get(key);
            if (other == null) continue;
            TreeSet<String> a = new TreeSet<>(signature(p, e.getValue()));
            TreeSet<String> b = new TreeSet<>(signature(p, other));
            if (!a.equals(b)) {
                problems.add(key + "  en=" + a + "  zh=" + b);
            }
        }
        assertTrue(problems.isEmpty(), "占位符不一致:\n  " + String.join("\n  ", problems));
    }

    /** 隐式占位符 (%s 不带索引) 无法重排, 所以两种语言里都不许用 —— 统一用 %1$s 形式。 */
    @Test
    void noImplicitPlaceholders() {
        Pattern implicit = Pattern.compile("%[sdf]");
        List<String> problems = new ArrayList<>();
        for (String code : I18n.availableLanguages()) {
            for (Map.Entry<String, String> e : I18n.table(code).entrySet()) {
                if (implicit.matcher(e.getValue()).find()) {
                    problems.add(code + " / " + e.getKey());
                }
            }
        }
        assertTrue(problems.isEmpty(), "请改用 %1$s 形式的索引占位符:\n  " + String.join("\n  ", problems));
    }

    /** 每条译文都必须能被 String.format 正确套用 (用足够的假参数试一遍). */
    @Test
    void everyEntryFormatsCleanly() {
        List<String> problems = new ArrayList<>();
        for (String code : I18n.availableLanguages()) {
            for (Map.Entry<String, String> e : I18n.table(code).entrySet()) {
                int max = maxIndex(e.getValue());
                Object[] args = new Object[Math.max(max, 0)];
                for (int i = 0; i < args.length; i++) args[i] = 7; // 整数/浮点/字符串都能吃下
                try {
                    String out = I18n.tr(code, e.getKey(), args);
                    assertNotNull(out);
                } catch (RuntimeException ex) {
                    problems.add(code + " / " + e.getKey() + " → " + ex);
                }
            }
        }
        assertTrue(problems.isEmpty(), "格式化失败:\n  " + String.join("\n  ", problems));
    }

    @Test
    void translateByExplicitLanguage() {
        String zhText = I18n.tr("zh_cn", "chunkpilot.status.online", 3);
        String enText = I18n.tr("en_us", "chunkpilot.status.online", 3);
        assertTrue(zhText.contains("在线玩家"), zhText);
        assertTrue(enText.contains("Online players"), enText);
        assertTrue(zhText.contains("3") && enText.contains("3"));
    }

    @Test
    void clientLanguageCodesAreNormalized() {
        assertEquals("zh_cn", I18n.normalize("zh_CN"));
        assertEquals("zh_cn", I18n.normalize("zh-Hans-CN"));
        assertEquals("zh_cn", I18n.normalize("zh_tw"));
        assertEquals("en_us", I18n.normalize("en_GB"));
        assertEquals("en_us", I18n.normalize("en"));
        assertEquals("en_us", I18n.normalize(null));
        assertEquals("en_us", I18n.normalize("  "));
        assertEquals("de_de", I18n.normalize("de_DE"));
    }

    @Test
    void unknownKeyFallsBackToTheKeyItself() {
        assertEquals("chunkpilot.does.not.exist", I18n.tr("chunkpilot.does.not.exist"));
        assertEquals("chunkpilot.does.not.exist", I18n.tr("zh_cn", "chunkpilot.does.not.exist"));
    }

    /** 译文占位符写坏 (引用了不存在的参数) 时必须退回英文, 而不是把异常抛给玩家. */
    @Test
    void brokenTranslationFallsBackToEnglish() {
        String key = "chunkpilot.test.broken";
        try {
            I18n.putTable("zh_cn", Map.of(key, "需要三个参数 %1$s %2$s %3$s"));
            I18n.putTable("en_us", Map.of(key, "%1$s 和 %2$s"));
            assertEquals("A 和 B", I18n.tr("zh_cn", key, "A", "B"));
        } finally {
            I18n.clearCache();
        }
    }

    /** 缺 key 时回退到英文 (语言文件没跟上新版本的情况). */
    @Test
    void missingKeyInOneLanguageFallsBackToEnglish() {
        String key = "chunkpilot.test.onlyEnglish";
        try {
            I18n.putTable("zh_cn", Map.of());
            I18n.putTable("en_us", Map.of(key, "english only"));
            assertEquals("english only", I18n.tr("zh_cn", key));
        } finally {
            I18n.clearCache();
        }
    }

    @Test
    void autoModeIsNotForced() {
        I18n.configure("auto");
        assertFalse(I18n.isForced());
        assertEquals("en_us", I18n.language());
        I18n.configure("zh_cn");
        assertTrue(I18n.isForced());
        assertEquals("zh_cn", I18n.language());
        I18n.configure(null);
        assertFalse(I18n.isForced());
    }

    // ================= helpers =================

    private static List<String> signature(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        int implicit = 0;
        while (m.find()) {
            if (m.group(1) != null) {
                out.add("#" + m.group(1) + ":" + m.group(2));
            } else {
                implicit++;
                out.add("#" + implicit + ":" + m.group(4));
            }
        }
        return out;
    }

    private static int maxIndex(String text) {
        Matcher m = Pattern.compile("%(\\d+)\\$").matcher(text);
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        // 没有索引式占位符时, 按隐式 %s 的个数给参数
        if (max == 0) {
            Matcher m2 = Pattern.compile("%[sdf]").matcher(text);
            while (m2.find()) max++;
        }
        return max;
    }
}
