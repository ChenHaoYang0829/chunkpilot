package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.neoforge.util.MoveGuard;
import com.chunkpilot.ChunkPilot;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.11.6 非阻塞区块读取 (NeoForge 移植) —— 与 fabric 侧
 * `fabric/.../mixin/LevelNonBlockingReadMixin.java` **逐条等价**, 只把工具类换成
 * `com.chunkpilot.neoforge.util.MoveGuard`, 并给每个注入点加 `require = 0, expect = 0` (防御性注入).
 *
 * ============================ 真实调用链 (1.21.3 jstack 实证, 8/8 次同签名) ============================
 *   A) 碰撞
 *   ServerGamePacketListenerImpl.handleMovePlayer
 *     → Entity.move → Entity.collide → Entity.collideBoundingBox → Entity.collectColliders
 *       → CollisionGetter.getBlockCollisions → BlockCollisions.computeNext
 *         → Level.getChunkForCollisions(cx,cz)
 *           → Level.getChunk(cx,cz,FULL,false) → ServerChunkCache.getChunk
 *             → ServerChunkCache$MainThreadExecutor.managedBlock → LockSupport.parkNanos   ← 主线程 park
 *
 *   B) 落地检查 / 流体检查
 *   handleMovePlayer → ServerPlayer.doCheckFallDamage → LivingEntity.checkFallDamage
 *     → Entity.updateInWaterStateAndDoWaterCurrentPushing → updateFluidHeightAndDoFluidPushing
 *       → Level.getFluidState(pos) → Level.getChunkAt(pos) → ServerChunkCache.getChunk
 *         → managedBlock → parkNanos                                                     ← 主线程 park
 *
 * 关键: `load=false` **并不等于不阻塞**. 只要 ChunkHolder 在可见表里 (玩家票范围内)
 * 但还没到 FULL, `ServerChunkCache.getChunk` 依然 managedBlock 等它完成.
 * 更致命的是正反馈死锁: 主线程被 park → `ChunkMap.tick`/`runAllUpdates` 停摆 →
 * 那个区块永远到不了 FULL → park 永远不返回 (NeoForge 无 C2ME, 这条路径只有 CP 能治).
 *
 * ============================ 本 Mixin 做什么 (与 fabric 完全一致) ============================
 *   三个入口, 全部先判断"该区块是否**已经** FULL":
 *     1. Level.getChunkForCollisions  (受 `[protection] nonBlockingCollision`, 默认 true)
 *        → 未就绪返回 null (原版自己的"区块不存在"返回值, BlockCollisions 直接跳过)
 *     2. Level.getBlockState          (受 `nonBlockingReads`; false 时只在玩家移动包处理期间)
 *        → 未就绪返回 AIR
 *     3. Level.getFluidState          (同上) → 未就绪返回 EMPTY
 *
 *   语义影响: "还没生成完的区块" 对碰撞/方块/流体读而言等于不存在 —— 与原版**客户端**
 *   行为一致 (客户端没有的区块本来就没有方块/碰撞), 服务端与客户端判断因此更一致 → 回弹更少.
 *   代价: 极端落后时实体可能短暂穿过正在生成的区块; 而原版在那种场合的结局是 park 十几秒乃至崩服.
 *
 * 只作用于 ServerLevel (客户端 Level 本来就不阻塞); 任何异常都静默回退原版; 开关关闭 → 一行不改.
 */
@Mixin(Level.class)
public abstract class LevelNonBlockingReadMixin {

    private static final Logger CHUNKPILOT_LOG = LoggerFactory.getLogger("ChunkPilot");

    /** "开关真的生效"的可 grep 证据 (只计数+低频日志, 不改变任何行为). */
    private static final AtomicLong cpCollisionSkips = new AtomicLong();
    private static final AtomicLong cpReadSkips = new AtomicLong();

    private static void chunkpilot$logSkip(AtomicLong counter, String what) {
        try {
            long n = counter.incrementAndGet();
            if (n == 1L || n % 5000L == 0L) {
                CHUNKPILOT_LOG.info("[ChunkPilot] nonBlocking{}: 已把 {} 次未就绪区块读取按"
                    + "\"区块不存在\"返回 (neoforge, 主线程未 park)", what, n);
            }
        } catch (Throwable t) { /* ignore */ }
    }

    /** 该区块是否**已经**生成到 FULL (全程不阻塞: 一次 map 查找 + CompletableFuture.getNow). */
    private static boolean chunkpilot$isFullyReady(ServerLevel level, int chunkX, int chunkZ) {
        ChunkHolder holder = ((ServerChunkCacheAccessor) (ServerChunkCache) level.getChunkSource())
            .chunkpilot$getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
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
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
    private void chunkpilot$nonBlockingCollision(int chunkX, int chunkZ,
                                                 CallbackInfoReturnable<BlockGetter> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, chunkX, chunkZ)) return;
            cir.setReturnValue(null); // 原版"区块不存在"路径
            chunkpilot$logSkip(cpCollisionSkips, "Collision");
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 2) 方块读取: 默认只在玩家移动包处理期间生效 ----
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
    private void chunkpilot$nonBlockingBlockState(BlockPos pos,
                                                  CallbackInfoReturnable<BlockState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            chunkpilot$logSkip(cpReadSkips, "Reads");
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }

    // ---- 3) 流体读取: 默认只在玩家移动包处理期间生效 ----
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true,
            require = 0, expect = 0)
    private void chunkpilot$nonBlockingFluidState(BlockPos pos,
                                                  CallbackInfoReturnable<FluidState> cir) {
        try {
            if (!chunkpilot$enabled()) return;
            if (!chunkpilot$readsAlways() && !MoveGuard.active()) return;
            Level self = (Level) (Object) this;
            if (!(self instanceof ServerLevel level)) return;
            if (chunkpilot$isFullyReady(level, pos.getX() >> 4, pos.getZ() >> 4)) return;
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
            chunkpilot$logSkip(cpReadSkips, "Reads");
        } catch (Throwable t) {
            // 任何异常都不干扰原版
        }
    }
}
