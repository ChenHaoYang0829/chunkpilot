package com.chunkpilot.neoforge.util;

// port/26.2: 从 fabric 侧按语义等价移植 (二阶段整改"neoforge 缺那族 mixin");
//   源: fabric/src/main/java/com/chunkpilot/fabric/mixin/<同名> (权威语义) +
//       /data/cp-port/1.21.5/neoforge/... 的成品 (含 require=0/expect=0 防御性注入);
//   26.2 适配: 仅 ChunkPos API (asLong→pack / toLong→pack); 其余逐行未改。

/**
 * v0.11.6 玩家移动处理期标记 (ThreadLocal).
 *
 * 为什么需要它: `ServerGamePacketListenerImpl.handleMovePlayer` 内部会做
 *   落地检查 / 流体检查 / 碰撞解析, 这些都会去读"玩家包围盒附近区块"的方块状态、
 *   流体状态、碰撞盒. 只要那个区块还没生成到 FULL, `ServerChunkCache.getChunk` 就会
 *   调用 `mainThreadProcessor.managedBlock(future)` —— **主线程原地 park**.
 *
 * CP 的修法是: 只有在这段代码里, 未就绪的区块按"不存在"(空气/无流体/无碰撞)处理,
 * 主线程永不 park. 用一个 ThreadLocal 把作用范围精确限制在 "玩家移动包处理期间",
 * 从而不影响世界生成、实体 tick、红石等其它路径.
 *
 * 只在服务端主线程使用; 用 ThreadLocal 是因为同一进程里还有 netty 线程会读区块.
 *
 * ============================ 本类在 neoforge 侧的来历 ============================
 *   逐字对应 fabric 侧 com.chunkpilot.fabric.util.MoveGuard (二阶段 B2 整改:
 *   把 fabric 那族"非阻塞" mixin 按语义等价移植到 neoforge).
 *   **只换包名, 逻辑一行未改** —— 这是作业书 §3.1「语义等价优先」的要求.
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
