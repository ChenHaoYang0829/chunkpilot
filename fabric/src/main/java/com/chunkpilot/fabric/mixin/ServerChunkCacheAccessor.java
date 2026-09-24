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

    /**
     * 1.21.11 新增: ticket 系统重写后, `DistanceManager` 不再暴露 addTicket/removeTicket,
     * 全部落到 `net.minecraft.world.level.TicketStorage`. `ServerChunkCache.ticketStorage`
     * 是 private final, 而公开 API 只有 `addTicket(Ticket, ChunkPos)` 与
     * `removeTicketWithRadius(TicketType, ChunkPos, radius)` —— 后者只能表达
     * `ChunkLevel.byStatus(FULL) - radius` 这一族等级, 无法按任意等级移除.
     * 这里用 accessor 拿到 TicketStorage, 以便调用其 public `removeTicket(Ticket, ChunkPos)`
     * (按 "类型 + 等级" 匹配, 与加入时对称).
     *
     * 注意: 这是**反射契约**的一部分 —— accessor 名经 refmap 映射到 intermediary 字段名,
     * 所以即使 remapJar 后也不会像"按名字反射"那样失效.
     */
    @Accessor("ticketStorage")
    net.minecraft.world.level.TicketStorage chunkpilot$ticketStorage();

}
