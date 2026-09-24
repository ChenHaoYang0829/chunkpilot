package com.chunkpilot.fabric.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v0.11.6: 拿 {@code ServerChunkCache} 的两个非公开入口, 用于"绝不阻塞"的区块读取.
 *
 *   - getVisibleChunkIfPresent(long): 一次 map 查找, 不阻塞 (用于取 ChunkHolder);
 *   - mainThread: 用来判断"当前是不是服务端主线程" —— 只有主线程才可能 park.
 *
 * 1.20.1 移植核实 (javap 实证):
 *   ServerChunkCache.getVisibleChunkIfPresent(long)  → private ChunkHolder  (可 @Invoker)
 *   ServerChunkCache.mainThread                      → final Thread (包级) (可 @Accessor)
 *   ServerChunkCache.getChunkFutureMainThread(int,int,ChunkStatus,boolean) → private, 可 @Invoker
 *   差异只有两处: ChunkStatus 包名 (net.minecraft.world.level.chunk) 与
 *   返回值用 Either 而不是 1.21.2+ 的 ChunkResult.
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder chunkpilot$getVisibleChunkIfPresent(long chunkPosLong);

    @Accessor("mainThread")
    Thread chunkpilot$mainThread();

    /**
     * 原版"取区块 future"主线程版本. 调用它会产生**必要的副作用**:
     *   - load=true 时补一张 TicketType.UNKNOWN 票;
     *   - 把生成任务排进调度器 (getOrScheduleFuture).
     * 这正是"非阻塞替换"最容易被忽略的地方 —— 只判 isDone 而不调用它,
     * 就等于把原版"我需要这个区块"的压力一起丢掉了 (实测吞吐反降 3~4 倍).
     */
    @Invoker("getChunkFutureMainThread")
    java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<
        net.minecraft.world.level.chunk.ChunkAccess, ChunkHolder.ChunkLoadingFailure>>
        chunkpilot$getChunkFutureMainThread(int x, int z,
            net.minecraft.world.level.chunk.ChunkStatus status, boolean load);

}
