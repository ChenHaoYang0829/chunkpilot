package com.chunkpilot.neoforge.util;

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
 
 * ============================ ★ 26.x 适配 (port/26.1, 2026-09-24) ============================
 * 本文件从 `/data/cp-port/1.21.5/neoforge/...` 的 B2 整改版**逐字照抄**, 只做了 26.1 的真实 API 差异替换
 * (每一条都有 javap 实证, 见 `artifacts/26.1/REPORT.md` §3.2 与 §11):
 *   (本类不碰任何 MC 类型, 26.x 无需任何替换 —— 逐字照抄。)
 *   · 26.1 **不再混淆** ⇒ 不再需要任何 refmap; 本模块的 `fixRefmap` 已整体删除
 *     (见根报告 §2.2 B6), 所以"新增 mixin 要同步改硬编码 refmap"这条历史约束**已消失**,
 *     新增 `ServerChunkCacheAccessor` 不再有额外成本。
 *   · 注入点全部用 26.1 **内层真实服务端 jar**(`META-INF/versions/26.1/server-26.1.jar`)
 *     与 loom 的 `minecraft-merged-deobf-26.1.jar` 双向核对:
 *       Level.getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;                       public
 *       Level.getBlockState(Lnet/minecraft/core/BlockPos;)L.../block/state/BlockState;               public
 *       Level.getFluidState(Lnet/minecraft/core/BlockPos;)L.../material/FluidState;                  public
 *       ServerChunkCache.getChunk(IIL.../chunk/status/ChunkStatus;Z)L.../chunk/ChunkAccess;          public
 *       ServerChunkCache.getVisibleChunkIfPresent(J)Lnet/minecraft/server/level/ChunkHolder;         private
 *       ServerChunkCache.mainThread:Ljava/lang/Thread;                                               包可见 final
 *       ServerChunkCache.getChunkFutureMainThread(IIL.../ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;  private
 *       ChunkMap.updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V                     private (offset 58 调用 ChunkTrackingView.of)
 *       ChunkMap.runGenerationTask(Lnet/minecraft/server/level/ChunkGenerationTask;)V                private (offset 34 调用 ChunkTaskDispatcher.submit)
 *       ChunkTaskDispatcher.submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V           public
 *       ServerGamePacketListenerImpl.handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V  public
 *     ⇒ 全部命中, 与 1.21.5 的字节码形状一致; 且 26.1 / 26.1.1 / 26.1.2 三者 javap 输出 IDENTICAL。
 *   · 每个注入点都保留 `require = 0, expect = 0`: 目标缺失只是**该点不生效**, 绝不让服务端启动崩。
 * ================================================================================
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
