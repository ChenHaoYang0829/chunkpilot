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
 
 * ============================ ★ 26.x 适配 (port/26.1, 2026-09-24) ============================
 * 本文件从 `/data/cp-port/1.21.5/neoforge/...` 的 B2 整改版**逐字照抄**, 只做了 26.1 的真实 API 差异替换
 * (每一条都有 javap 实证, 见 `artifacts/26.1/REPORT.md` §3.2 与 §11):
 *   · `ChunkPos` 在 26.1 变成 **record** ⇒ `asLong(int,int)` → `pack(int,int)`,
 *     字段 `x`/`z` → 访问器 `x()`/`z()`; (`toLong()` → `pack()`)
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
