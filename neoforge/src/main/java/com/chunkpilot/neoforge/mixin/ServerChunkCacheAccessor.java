package com.chunkpilot.neoforge.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v0.11.6 (neoforge 移植): 拿 {@code ServerChunkCache} 的两个非公开入口。
 *
 * **纯访问器, 无任何行为改动** —— 与 `com.chunkpilot.fabric.mixin.ServerChunkCacheAccessor`
 * 语义逐字对应, 只是 NeoForge 运行期就是 **Mojang 官方名** (javap 实证), 所以
 * `@Invoker`/`@Accessor` 里的字面名与 fabric 侧相同。
 *
 * javap 依据 (3 个版本逐个核对, 见 artifacts/<mc>/REPORT.md 的新增节):
 *   ServerChunkCache:
 *     private ChunkHolder getVisibleChunkIfPresent(long);                       // 1.21.9/10/11 一致
 *     final java.lang.Thread mainThread;                                        // 1.21.9/10/11 一致
 *     private CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int,int,ChunkStatus,boolean);
 *
 * 另: NeoForgePlatform 的探针 (probeChunk) 也复用本 accessor 取 ChunkHolder,
 * 从而拿回真实的 ticketLevel (此前只能反射, 被具名 module 层的 setAccessible 限制挡住 ⇒ 恒 -1)。
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
     * 只判 isDone 而不调用它 = 丢掉原版"我需要这个区块"的压力 ⇒ 吞吐反降 3~4 倍
     * (fabric 侧实测, 已写进 fabric 同类的注释)。
     */
    @Invoker("getChunkFutureMainThread")
    java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>
        chunkpilot$getChunkFutureMainThread(int x, int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load);
}
