package com.chunkpilot.neoforge.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.11.6 "主线程永不 park" 的替换计数.
 *
 * 语义与 `com.chunkpilot.fabric.util.NonBlockingStats` 一致 (只换包名)。
 * parkSubstitutions 在涨 ⇒ 服务器当前确实处于"生成跟不上"的状态, 也即
 * `[protection] nonBlockingGetChunk` **真的在生效** (兼容性证据用)。
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
