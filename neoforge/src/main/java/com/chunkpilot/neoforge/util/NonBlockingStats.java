package com.chunkpilot.neoforge.util;

// port/26.2: 从 fabric 侧按语义等价移植 (二阶段整改"neoforge 缺那族 mixin");
//   源: fabric/src/main/java/com/chunkpilot/fabric/mixin/<同名> (权威语义) +
//       /data/cp-port/1.21.5/neoforge/... 的成品 (含 require=0/expect=0 防御性注入);
//   26.2 适配: 仅 ChunkPos API (asLong→pack / toLong→pack); 其余逐行未改。

import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.11.6 "主线程永不 park" 的替换计数 (供 {@code /chunkpilot status} / 探针展示).
 *
 * parkSubstitutions: 有多少次"本该阻塞主线程的区块读取"被换成了空区块.
 * 这个数只要在涨, 就说明服务器当前确实处于"生成跟不上"的状态 ——
 * 它是本族非阻塞 mixin **真的在生效** 的最直接机械证据 (见二阶段 B2 报告 §兼容性证据).
 *
 * 对应 fabric 侧 com.chunkpilot.fabric.util.NonBlockingStats, 逐字同源.
 */
public final class NonBlockingStats {

    private static final AtomicLong PARK_SUBSTITUTIONS = new AtomicLong();

    private NonBlockingStats() {}

    public static void countParkSubstitution() {
        PARK_SUBSTITUTIONS.incrementAndGet();
    }

    public static long parkSubstitutions() {
        return PARK_SUBSTITUTIONS.get();
    }
}
