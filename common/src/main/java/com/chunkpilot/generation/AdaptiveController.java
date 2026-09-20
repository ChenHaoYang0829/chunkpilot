package com.chunkpilot.generation;

/**
 * v0.3.0 自适应控制器 (DESIGN.md v12 第 11 章).
 *
 * 纯静态工具: 根据当前 MSPT 调整每 tick 生成 chunk 数.
 * 规则:
 *   if (currentMspt > targetMspt + 5)  return Math.max(1, currentCount - 1);
 *   if (currentMspt < targetMspt - 5)  return Math.min(maxCount, currentCount + 1);
 *   return currentCount;
 *
 * ±5ms 滞回带 (hysteresis) 避免在目标附近抖动.
 */
public final class AdaptiveController {

    /** 滞回带宽 (ms) */
    public static final double HYSTERESIS_MS = 5.0;

    private AdaptiveController() {}

    /**
     * 根据当前 MSPT 调整每 tick 生成 chunk 数.
     *
     * v0.6.0 变化: 允许降到 0 (MSPT 严重超标时完全停手, 回归 vanilla 节奏).
     * 0804 崩溃的教训: CP 预生成在负载高时不能"降到 1 还在推",
     * 必须能完全停手, 让 vanilla 自己消化.
     *
     * @param currentCount 当前每 tick 生成数 (>= 0)
     * @param currentMspt  当前服务器 MSPT (毫秒)
     * @param targetMspt   目标 MSPT (毫秒, 通常 35.0)
     * @param maxCount     每 tick 上限 (来自 GenerationConfig.maxChunksPerTick)
     * @return 调整后的每 tick 生成数, 范围 [0, maxCount]
     */
    public static int adapt(int currentCount, double currentMspt, double targetMspt, int maxCount) {
        if (currentMspt > targetMspt + HYSTERESIS_MS) {
            // 负载超标: 降, 允许降到 0
            return Math.max(0, currentCount - 1);
        }
        if (currentMspt < targetMspt - HYSTERESIS_MS) {
            return Math.min(maxCount, currentCount + 1);
        }
        return currentCount;
    }
}
