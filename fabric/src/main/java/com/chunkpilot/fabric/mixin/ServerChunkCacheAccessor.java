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
 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder chunkpilot$getVisibleChunkIfPresent(long chunkPosLong);

    @Accessor("mainThread")
    Thread chunkpilot$mainThread();

    /**
     * 1.21.8 移植: 票据容器搬到了 {@code TicketStorage}。
     *
     * javap 实证 (1.21.8 minecraft-merged):
     *   - {@code public void ServerChunkCache.addTicket(Ticket, ChunkPos)} 还在 (加票用它);
     *   - 但 {@code ServerChunkCache} 上**没有** public removeTicket(Ticket, ChunkPos),
     *     只有 {@code removeTicketWithRadius(TicketType, ChunkPos, int)} (按类型整片删, 不分等级);
     *   - 精确删除 (type + level 同时匹配) 只在 {@code TicketStorage.removeTicket(Ticket, ChunkPos)};
     *   - {@code TicketStorage} 实例是 {@code ServerChunkCache} 的 private final 字段 ticketStorage。
     *   ⇒ 用一个 @Accessor 拿它。
     */
    @Accessor("ticketStorage")
    net.minecraft.world.level.TicketStorage chunkpilot$ticketStorage();

    /**
     * 原版"取区块 future"主线程版本. 调用它会产生**必要的副作用**:
     *   - load=true 时补一张 TicketType.UNKNOWN 票;
     *   - 把生成任务排进调度器 (getOrScheduleFuture).
     * 这正是"非阻塞替换"最容易被忽略的地方 —— 只判 isDone 而不调用它,
     * 就等于把原版"我需要这个区块"的压力一起丢掉了 (实测吞吐反降 3~4 倍).
     */
    @Invoker("getChunkFutureMainThread")
    java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>
        chunkpilot$getChunkFutureMainThread(int x, int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load);

}
