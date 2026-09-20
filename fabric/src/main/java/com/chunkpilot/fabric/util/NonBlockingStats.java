package com.chunkpilot.fabric.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.11.6 "主线程永不 park" 的替换计数 (供 /chunkpilot status 展示).
 *
 * parkSubstitutions: 有多少次"本该阻塞主线程的区块读取"被换成了空区块.
 * 这个数只要在涨, 就说明服务器当前确实处于"生成跟不上"的状态.
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

    public static void reset() {
        PARK_SUBSTITUTIONS.set(0);
    }
}
