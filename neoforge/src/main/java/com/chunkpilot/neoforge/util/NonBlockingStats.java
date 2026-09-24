package com.chunkpilot.neoforge.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.11.6 "主线程永不 park" 的替换计数 (供 {@code /chunkpilot status} / 探针展示).
 *
 * parkSubstitutions: 有多少次"本该阻塞主线程的区块读取"被换成了空区块.
 * 这个数只要在涨, 就说明服务器当前确实处于"生成跟不上"的状态 ——
 * 它是本族非阻塞 mixin **真的在生效** 的最直接机械证据 (见二阶段 B2 报告 §兼容性证据).
 *
 * 对应 fabric 侧 com.chunkpilot.fabric.util.NonBlockingStats, 逐字同源.
 
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
