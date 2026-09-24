package com.chunkpilot.neoforge.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.11.6 (B3 专项): mixin "首次生效"探针。
 *
 * ============================ 为什么需要它 ============================
 * 本次移植的每个注入点都按作业书 §3.3 加了 `require = 0, expect = 0` ⇒ 目标方法在某版本缺失时
 * **只是该 mixin 静默不生效**, 不会崩服。代价是"注入失败"与"注入成功但没触发"在日志上无法区分。
 *
 * 于是每个新 mixin 在自己**真正起作用的那一刻**调一次本探针:
 *   · 开关打开 → 日志出现 `[ChunkPilot] mixin 生效: ...`;
 *   · 开关关掉(或目标缺失) → 该行**不出现**。
 * 这就是作业书 §3.5/§7② 要的"开/关对照证据", 且只有一条日志, 零热路径成本。
 *
 * 纯诊断, 不参与任何决策, 不改变任何原版行为。
 */
public final class MixinProbe {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private MixinProbe() {}

    public static void once(String key, String message) {
        try {
            if (SEEN.add(key)) {
                org.slf4j.LoggerFactory.getLogger("ChunkPilot")
                    .info("[ChunkPilot] mixin 生效: {}", message);
            }
        } catch (Throwable ignored) {
            // 诊断失败绝不影响原版
        }
    }
}
