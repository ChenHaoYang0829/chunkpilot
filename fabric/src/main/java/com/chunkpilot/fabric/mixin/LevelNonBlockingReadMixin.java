package com.chunkpilot.fabric.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.fabric.util.MoveGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * v0.11.6 非阻塞区块读取 —— 修"无 C2ME 会崩"和客户端"墙"的**根因**.
 *
 * ============================ 1.21.3 的真实调用链 (jstack 实证, 8/8 次同签名) ============================
 *   A) 碰撞
 *   ServerGamePacketListenerImpl.handleMovePlayer
 *     → Entity.move → Entity.collide → Entity.collideBoundingBox → Entity.collectColliders
 *       → CollisionGetter.getBlockCollisions → BlockCollisions.computeNext
 *         → Level.getChunkForCollisions(cx,cz)
 *           → Level.getChunk(cx,cz,FULL,false) → ServerChunkCache.getChunk(:151)
 *             → ServerChunkCache$MainThreadExecutor.managedBlock → LockSupport.parkNanos   ← 主线程 park
 *
 *   B) 落地检查 / 流体检查
 *   handleMovePlayer
 *     → ServerPlayer.doCheckFallDamage → LivingEntity.checkFallDamage
 *       → Entity.updateInWaterStateAndDoWaterCurrentPushing
 *         → Entity.updateFluidHeightAndDoFluidPushing
 *           → Level.getFluidState(pos) → Level.getChunkAt(pos) → Level.getChunk(cx,cz)
 *             → ServerChunkCache.getChunk → managedBlock → parkNanos                   ← 主线程 park
 *
 * 关键: `load=false` **并不等于不阻塞**. 只要 ChunkHolder 在可见表里 (玩家票范围内)
 * 但还没到 FULL, `ServerChunkCache.getChunk` 依然 managedBlock 等它完成.
 *
 * 更致命的是这是个**正反馈死锁**: 主线程被 park 住 → `ChunkMap.tick`/`runAllUpdates`
 * 这些"把生成任务推下去"的步骤全都停摆 → 那个区块永远到不了 FULL → park 永远不返回.
 * CP 的 FreezeDetector 现场记录证实: 玩家周围 9x9 (r=4) 全 NOT-FULL, 而同期
 * `[Gen] queue=1 outstanding=1/32` —— 生成队列几乎是空的, 服务器**不是**算不过来,
 * 是被自己锁死了. 无 C2ME 时实测单次 park 2.8~45 秒, 最后
 * `Server Watchdog: A single server tick took 60.00 seconds` → 强制关服.
 *
 * ============================ 本 Mixin 做什么 ============================
 *   三个入口, 全部先判断"该区块是否**已经** FULL":
 *     1. Level.getChunkForCollisions  (始终生效)  → 未就绪返回 null
 *        (这是原版自己的"区块不存在"返回值, BlockCollisions 直接跳过, 无需调用方适配)
 *     2. Level.getBlockState          (仅玩家移动包处理期间) → 未就绪返回 AIR
 *     3. Level.getFluidState          (仅玩家移动包处理期间) → 未就绪返回 EMPTY
 *
 * 语义影响: "还没生成完的区块" 对碰撞/方块/流体读而言等于不存在 ——
 *   这与原版**客户端**的行为一致 (客户端没有的区块本来就没有方块/碰撞),
 *   所以服务端与客户端对"玩家能不能从这儿过去"的判断反而更一致 → 回弹更少.
 *   代价: 极端落后时实体可能短暂穿过正在生成的区块 —— 但原版在那种场合的结局
 *   就是 park 十几秒乃至崩服, 这是严格更优的取舍.
 *
 * 只作用于 ServerLevel (客户端 Level 本来就不阻塞).
 */
@Mixin(Level.class)
public abstract class LevelNonBlockingReadMixin {

    /** 该区块是否**已经**生成到 FULL (全程不阻塞: 一次 map 查找 + CompletableFuture.getNow). */
    private static boolean chunkpilot$isFullyReady(ServerLevel level, int chunkX, int chunkZ) {
        ChunkHolder holder = ((ServerChunkCacheAccessor) (ServerChunkCache) level.getChunkSource())
            .chunkpilot$getVisibleChunkIfPresent(ChunkPos.pack(chunkX, chunkZ));
        if (holder == null) return false;
        CompletableFuture<ChunkResult<LevelChunk>> full = holder.getFullChunkFuture();
        if (!full.isDone()) return false;
        ChunkResult<LevelChunk> res = full.getNow(null);
        return res != null && res.isSuccess() && res.orElse(null) != null;
    }

    private static boolean chunkpilot$enabled() {
        ChunkPilot cp = ChunkPilot.getInstance();
        return cp != null && cp.getConfig() != null && cp.getConfig().nonBlockingCollision;
    }

    /** 方块/流体读取是否全局不阻塞 (false = 只在玩家移动包处理期间). */
    private static boolean chunkpilot$readsAlways() {
        ChunkPilot cp = ChunkPilot.getInstance();
        return cp == null || cp.getConfig() == null || cp.getConfig().nonBlockingReads;
    }

    // ---- 1) 碰撞查询: 始终生效 (只影响实体碰撞, 影响面最小, 收益最大) ----
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$nonBlockingCollision(int chunkX, int chunkZ,
                                                 CallbackInfoReturnable<BlockGetter> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, chunkX, chunkZ)) return;
            cir.setReturnValue(null); // 原版"区块不存在"路径
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 2) 方块读取: 只在玩家移动包处理期间生效 ----
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$nonBlockingBlockState(BlockPos pos,
                                                  CallbackInfoReturnable<BlockState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 3) 流体读取: 只在玩家移动包处理期间生效 ----
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$nonBlockingFluidState(BlockPos pos,
                                                  CallbackInfoReturnable<FluidState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }
}
