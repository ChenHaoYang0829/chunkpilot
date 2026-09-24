package com.chunkpilot.neoforge.util;

/**
 * v0.11.6 玩家移动处理期标记 (ThreadLocal).
 *
 * 语义与 `com.chunkpilot.fabric.util.MoveGuard` **逐字一致** —— NeoForge 侧移植
 * (main 的 NEOFORGE_REMEDIATION_BRIEF §2/§3: 参照 fabric 同名文件, 不自己发明新偏离)。
 * 只换包名, 行为零改动。
 */
public final class MoveGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private MoveGuard() {}

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) d[0]--;
        if (d[0] == 0) DEPTH.remove();
    }

    public static boolean active() {
        int[] d = DEPTH.get();
        return d[0] > 0;
    }
}
