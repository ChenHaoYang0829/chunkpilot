package com.chunkpilot.neoforge.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * v0.11.6: 拿 {@code ServerChunkCache} 的非公开入口 —— 与 fabric 侧
 * (fabric/src/main/java/com/chunkpilot/fabric/mixin/ServerChunkCacheAccessor.java) 逐项等价.
 *
 *   - getVisibleChunkIfPresent(long): 一次 map 查找, 不阻塞 (用于取 ChunkHolder);
 *   - mainThread: 用来判断"当前是不是服务端主线程" —— 只有主线程才可能 park;
 *   - getChunkFutureMainThread(...): 原版"取区块 future"主线程版本. 调用它会产生
 *     **必要的副作用** (load=true 时补票 + 把生成任务排进调度器), 这正是"非阻塞替换"
 *     最容易被忽略的地方 —— 只判 isDone 而不调用它, 就等于把原版"我需要这个区块"的
 *     压力一起丢掉了 (fabric 实测吞吐反降 3~4 倍).
 *
 * javap 实证 (1.21.1/1.21.2/1.21.3/1.21.4 的 minecraft-merged 官方映射 jar 完全一致):
 *   private net.minecraft.server.level.ChunkHolder getVisibleChunkIfPresent(long);
 *   final java.lang.Thread mainThread;
 *   private java.util.concurrent.CompletableFuture<ChunkResult<ChunkAccess>>
 *        getChunkFutureMainThread(int, int, ChunkStatus, boolean);
 * ⇒ 两个方法都是 private, 字段是 package-private ⇒ 必须靠 {@code @Invoker}/{@code @Accessor},
 *   不能按名反射 (1.21.3 负责人已记录: 1.21.10 分支上反射 getChunkHolder 一直静默降级).
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder chunkpilot$getVisibleChunkIfPresent(long chunkPosLong);

    @Accessor("mainThread")
    Thread chunkpilot$mainThread();

    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> chunkpilot$getChunkFutureMainThread(
        int x, int z, ChunkStatus status, boolean load);
}
