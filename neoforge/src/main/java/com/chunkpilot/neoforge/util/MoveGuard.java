package com.chunkpilot.neoforge.util;

/**
 * v0.11.6 玩家移动处理期标记 (ThreadLocal) —— 与 fabric 侧同名类逐行等价
 * (fabric/src/main/java/com/chunkpilot/fabric/util/MoveGuard.java).
 *
 * 为什么需要它: `ServerGamePacketListenerImpl.handleMovePlayer` 内部会做
 *   落地检查 / 流体检查 / 碰撞解析, 这些都会去读"玩家包围盒附近区块"的方块状态、
 *   流体状态、碰撞盒. 只要那个区块还没生成到 FULL, `ServerChunkCache.getChunk` 就会
 *   调用 `mainThreadProcessor.managedBlock(future)` —— **主线程原地 park**,
 *   最终触发 Server Watchdog 强制关服.
 *
 * CP 的修法: 只有在这段代码里, 未就绪的区块按"不存在"(空气/无流体/无碰撞)处理,
 *   主线程永不 park. ThreadLocal 把作用范围精确限制在"玩家移动包处理期间",
 *   不影响世界生成、实体 tick、红石等其它路径.
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
