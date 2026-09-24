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
     * port/1.21.10 新增: TicketStorage 入口。
     *
     * 1.21.10 把 addTicket/removeTicket 从 DistanceManager 挪到了
     * {@code net.minecraft.world.level.TicketStorage}, 由 ServerChunkCache 以
     * {@code private final TicketStorage ticketStorage} 持有 (javap 实证)。
     * ServerChunkCache 只暴露了 public {@code addTicket(Ticket, ChunkPos)} 与
     * {@code removeTicketWithRadius(TicketType, ChunkPos, int)} —— 后者按"半径"语义匹配,
     * 与 CP 的"显式等级票据"不对应; 所以移除操作需要本 accessor 直接拿 TicketStorage,
     * 调 public {@code removeTicket(Ticket, ChunkPos)} (内部按 type 引用相等 + level 相等匹配)。
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
