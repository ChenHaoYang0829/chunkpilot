package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.neoforge.platform.NeoForgePlatform;
import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.GenerationConfig;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * v0.11.0: CP 独占生成 (exclusiveGenerationNoC2me) 的安全实现 —— **只调优先级, 绝不丢弃任务**.
 * NeoForge 移植, 与 fabric 侧同名 mixin 等价 (同一配置节 `[generation]`, 同一速度门控).
 *
 * ============================ 为什么必须改 (1.21.3 反编译实证) ============================
 * 旧实现 (v0.9.0~v0.10.x) 在 runGenerationTask 里 ci.cancel() 掉"不在 CP 请求集合"的生成任务,
 * 这在 1.21.3 上必然导致 watchdog 死锁:
 *   1) 区块升到 FULL / 可 entity-tick 要求整个 5x5 邻域全部到 FULL
 *      (ChunkMap.prepareEntityTickingChunk → getChunkRangeFuture(h, 2, d -> FULL));
 *      丢掉任一邻域任务 ⇒ 玩家自己的区块永远升不到 FULL ⇒ 主线程永久等待 ⇒ watchdog 杀服.
 *   2) GenerationChunkHolder 的 task 字段仍指向被丢弃的任务 (没人 removeTask/releaseClaim)
 *      ⇒ 之后重排会被拒绝 ⇒ 永久卡死.
 * 结论: "取消原版生成任务"这条路走不通, 只能改**调度顺序**.
 *
 * ============================ 现在的做法 ============================
 * 对 CP 请求/持票的 chunk, 把投递到 worldgen 优先级队列的 **排序 level** 压到 CP_PRIORITY_CAP=33:
 *   - 优先级只在 ChunkTaskPriorityQueue 内部用于排序 (低 level 先做), 不影响正确性;
 *   - 任务一个都不丢, 5x5 依赖链完整 ⇒ 不会再死锁;
 *   - 上限 33 = FULL 的 level ⇒ CP 前方 chunk 排在原版环状浅层依赖 (34~41) 之前,
 *     但不会插到玩家自身区域 (31~32) 前面 (避免玩家所在区块被饿死).
 *
 * 平台侧新增: `NeoForgePlatform.isChunkRequestedByCp/isChunkTicketedByCp` ——
 *   fabric 由 `FabricPlatform` 的同名集合提供, neoforge 之前**没有**这套集合
 *   (所以这个开关在 neoforge 上一直空转). 现在按 fabric 同口径补齐:
 *     requestedChunks 每 tick 由 `ChunkPilotNeoForge.onServerTick` 开头清空,
 *     `NeoForgePlatform.requestChunkAsync` 填充; ticketedChunks 每 5s 由
 *     `ChunkLoadOptimizer` 调 `rebuildCpTicketMarks` 用真实活跃票集合重建.
 *
 * 任何异常都回退原版提交; `[generation] enabled=false` 或 `exclusiveGenerationNoC2me=false`
 * 或非高速移动 → 一行不改.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGenerationMixin {

    private static final int CP_PRIORITY_CAP = 33;

    private static final AtomicLong cpBoosted = new AtomicLong();
    private static final AtomicLong cpNormal = new AtomicLong();
    private static final AtomicLong cpLastLog = new AtomicLong();

    /**
     * 1.21.1 版拦截点 (javap 实证, minecraft-merged 官方映射 jar):
     *
     *   ChunkMap.runGenerationTask(ChunkGenerationTask):
     *      0: aload_0
     *      1: getfield  worldgenMailbox:Lnet/minecraft/util/thread/ProcessorHandle;
     *      4: aload_1 → ChunkGenerationTask.getCenter():GenerationChunkHolder
     *     10: invokedynamic  ChunkMap::lambda(task) : Runnable
     *     15: invokestatic   ChunkTaskPriorityQueueSorter.message:(LGenerationChunkHolder;Ljava/lang/Runnable;)LChunkTaskPriorityQueueSorter$Message;
     *     18: invokeinterface ProcessorHandle.tell:(Ljava/lang/Object;)V
     *
     *   `ChunkTaskPriorityQueueSorter.message(GenerationChunkHolder, Runnable)` 内部
     *   (= `message(runnable, holder.getPos().toLong(), holder::getQueueLevel)`) 用的排序键就是
     *   `GenerationChunkHolder.getQueueLevel()` (javap: `public abstract int getQueueLevel()`),
     *   与 1.21.3 的 `ChunkTaskDispatcher.submit(..., IntSupplier)` 语义一致 (越低越先做).
     *
     *   **1.21.1 没有 `net.minecraft.server.level.ChunkTaskDispatcher`**(1.21.2 才引入新管线),
     *   所以这里重定向到 `message(Runnable, long, IntSupplier)` 并把 level 钳制;
     *   任务原样投递, 一个都不丢.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "runGenerationTask",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Lnet/minecraft/server/level/GenerationChunkHolder;Ljava/lang/Runnable;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"
        ),
        require = 0, expect = 0
    )
    private ChunkTaskPriorityQueueSorter.Message chunkpilot$submitWithCpPriority(
            GenerationChunkHolder holder, Runnable runnable) {
        // 原版排序键: holder.getQueueLevel() (ChunkTaskPriorityQueueSorter.message 内部使用)
        IntSupplier base = holder::getQueueLevel;
        IntSupplier effective = base;
        long chunkPosLong = holder.getPos().toLong();
        try {
            if (chunkpilot$shouldPrioritize(chunkPosLong)) {
                final IntSupplier b = base;
                // 只换排序键, 不改变任务本身; 排序 key 取 min(原 level, 33)
                effective = () -> Math.min(b.getAsInt(), CP_PRIORITY_CAP);
                long n = cpBoosted.incrementAndGet();
                if (n == 1) {
                    log("CP 独占生成(neoforge): 开始对 CP 请求区块提优先级 (cap=" + CP_PRIORITY_CAP + ")");
                }
            } else {
                cpNormal.incrementAndGet();
            }
            chunkpilot$maybeLogSummary();
        } catch (Throwable t) {
            effective = base; // 任何异常都不影响原版提交
        }
        // 与原版 message(holder, runnable) 完全等价, 唯一差别是 level 供应商被钳制
        return ChunkTaskPriorityQueueSorter.message(runnable, chunkPosLong, effective);
    }

    /** CP 请求集合 / CP 持票集合里的 chunk 才提优先级. */
    private static boolean chunkpilot$shouldPrioritize(long chunkPosLong) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null) return false;
            GenerationConfig genCfg = cp.getConfig().generation;
            if (genCfg == null || !genCfg.enabled) return false;
            if (!genCfg.exclusiveGenerationNoC2me) return false;
            // 有 C2ME 时由 C2ME 管生成, CP 不插手
            if (cp.getPlatform().isModLoaded("c2me")) return false;
            // 只有快速移动玩家在场时才启用 (静止时保持原版顺序)
            if (!cp.getOptimizer().getSpeedTracker()
                    .hasPlayerAboveSpeedChunksPerSecond(genCfg.v_min)) return false;
            return NeoForgePlatform.isChunkRequestedByCp(chunkPosLong)
                || NeoForgePlatform.isChunkTicketedByCp(chunkPosLong);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每 20000 次提交打一行统计, 便于飞行测试时判断独占生成是否真的在起作用. */
    private static void chunkpilot$maybeLogSummary() {
        long total = cpBoosted.get() + cpNormal.get();
        long last = cpLastLog.get();
        if (total - last >= 20_000 && cpLastLog.compareAndSet(last, total)) {
            log("CP 独占生成统计(neoforge): CP 优先=" + cpBoosted.get() + ", 原版正常=" + cpNormal.get());
        }
    }

    private static void log(String msg) {
        try {
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").info("[ChunkPilot] " + msg);
        } catch (Throwable t) { /* ignore */ }
    }
}
