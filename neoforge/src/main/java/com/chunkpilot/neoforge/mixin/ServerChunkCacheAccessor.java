package com.chunkpilot.neoforge.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * v0.11.6 → neoforge (二阶段 B2): 拿 {@code ServerChunkCache} 的两个非公开入口,
 * 用于"绝不阻塞"的区块读取. **纯访问器, 无任何行为改动**.
 *
 *   - getVisibleChunkIfPresent(long): 一次 map 查找, 不阻塞 (用于取 ChunkHolder);
 *   - mainThread: 用来判断"当前是不是服务端主线程" —— 只有主线程才可能 park.
 *
 * ============================ javap 依据 (1.21.5 / 1.21.6 / 1.21.7 / 1.21.8 逐版核对) ============================
 * 对 **NeoForge 打补丁后的运行期 jar** (srv/&lt;mc&gt;/neoforge/libraries/net/neoforged/neoforge/&lt;ver&gt;/neoforge-&lt;ver&gt;-server.jar)
 * 以及 loom 缓存里同版本的 mapped MC jar 各做了一次 javap, 四个版本结论一致:
 *
 *   class net.minecraft.server.level.ServerChunkCache:
 *     private   net.minecraft.server.level.ChunkHolder getVisibleChunkIfPresent(long);
 *     final     java.lang.Thread mainThread;                       // 包可见字段
 *     private   CompletableFuture&lt;ChunkResult&lt;ChunkAccess&gt;&gt; getChunkFutureMainThread(int,int,ChunkStatus,boolean);
 *
 *   ⇒ 三者都存在且可见性允许 @Invoker/@Accessor 注入.
 *   ⚠ Sponge Mixin 0.8.7 的 {@code @Invoker}/{@code @Accessor} **没有 require 参数**
 *     (javap org.spongepowered.asm.mixin.gen.Invoker: 只有 value()/remap()),
 *     因此这两个方法无法做"软失败" —— 它们的存在性只能用上面的 javap 事实来保证,
 *     这也是本类**不新增任何方法**、逐字照抄 fabric 的原因.
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
     * 就等于把原版"我需要这个区块"的压力一起丢掉了 (fabric 侧实测吞吐反降 3~4 倍).
     */
    @Invoker("getChunkFutureMainThread")
    java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>
        chunkpilot$getChunkFutureMainThread(int x, int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load);
}
