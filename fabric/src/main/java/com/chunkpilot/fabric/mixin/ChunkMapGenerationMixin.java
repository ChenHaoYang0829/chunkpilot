package com.chunkpilot.fabric.mixin;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.config.GenerationConfig;
import com.chunkpilot.fabric.platform.FabricPlatform;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * v0.11.0: CP 独占生成 (exclusiveGenerationNoC2me) 的安全实现 —— 只调优先级, 绝不丢弃任务.
 *
 * ============================ 为什么必须改 (1.21.3 反编译实证) ============================
 * 旧实现 (v0.9.0~v0.10.x) 在 runGenerationTask 里 ci.cancel() 掉"不在 CP 请求集合"的生成任务.
 * 这在 1.21.3 上必然导致 server-watchdog 死锁, 有两处硬伤:
 *
 * 1) 1.21.3 区块升到 FULL / 可 entity-tick, 要求整个 5x5 邻域全部到 FULL:
 *      ChunkMap.prepareEntityTickingChunk(h) -> getChunkRangeFuture(h, 2, d -> FULL)
 *      其中 lambda (method_17254 / method_17245) 对所有距离都返回 ChunkStatus.FULL.
 *    (javap 实证: net.minecraft.server.level.ChunkMap.method_17254(int) 恒 return FULL)
 *    所以任一邻域 chunk 的生成任务被丢掉 -> 玩家自己的 chunk 永远升不到 FULL
 *    -> 主线程 Entity.tick -> getEntities -> getChunk 永久等待 -> watchdog 杀服.
 *
 * 2) runGenerationTask 只是把任务提交给 worldgen 调度器;
 *    ci.cancel() 丢掉的是唯一一次提交, 但 GenerationChunkHolder 的 task 字段仍指向该任务
 *    (没人调用 removeTask / releaseClaim) -> 该 chunk 之后即使被重新请求,
 *    scheduleChunkGenerationTask 也会因 (task != null 且 status 不比 task.targetStatus 更深) 拒绝重排
 *    -> 永久卡死, 同时它 17x17 StaticCache2D 的 claim 永不释放 (崩溃时 W:1767).
 *
 * 结论: "取消原版生成任务"这条路走不通, 只能改调度顺序.
 *
 * ============================ 现在的做法 ============================
 * 对 CP 请求/持票的 chunk, 把投递到 worldgen 优先级队列的 **排序 level** 压到 CP_PRIORITY_CAP:
 *   - 优先级只在 ChunkTaskPriorityQueue 内部用于排序 (低 level 先做), 不影响正确性;
 *   - 任务一个都不丢, 5x5 依赖链完整 -> 不会再死锁;
 *   - 上限 33 = FULL 的 level, CP 前方 chunk 因此排在原版环状浅层依赖 (34~41) 之前,
 *     但不会插到玩家自身区块前面 (避免玩家所在区块被饿死).
 *
 * ============================ port/1.21.1: 等价替换 (必须改) ============================
 * **1.21.1 没有 `net.minecraft.server.level.ChunkTaskDispatcher`**(1.21.2 才引入的新 chunk
 * 生成管线). javap 实证 (1.21.1 minecraft-merged, official mappings):
 *
 *   ChunkMap.runGenerationTask(ChunkGenerationTask):
 *     0: aload_1
 *     1: invokevirtual ChunkGenerationTask.getCenter:()Lnet/minecraft/server/level/GenerationChunkHolder;
 *     5: aload_0
 *     6: getfield  ChunkMap.worldgenMailbox:Lnet/minecraft/util/thread/ProcessorHandle;
 *    10: <lambda: ChunkMap::method_xxx(task)>
 *    15: invokestatic ChunkTaskPriorityQueueSorter.message:(LGenerationChunkHolder;Ljava/lang/Runnable;)LChunkTaskPriorityQueueSorter$Message;
 *    18: invokeinterface ProcessorHandle.tell:(Ljava/lang/Object;)V
 *
 * 且 `ChunkTaskPriorityQueueSorter.message(GenerationChunkHolder, Runnable)` 的内部实现是
 * (javap -c, BootstrapMethod #3 实证):
 *     message(runnable, holder.getPos().toLong(), holder::getQueueLevel)   // ← IntSupplier
 * 而 1.21.3 的 `ChunkTaskDispatcher.submit(runnable, chunkPos, IntSupplier)` 用的是
 * `GenerationChunkHolder::getTicketLevel`, 两者语义一致 (都是"越低越先做"的排序键).
 *
 * 所以 1.21.1 的**等价拦截点**是: 把 runGenerationTask 里对
 * `ChunkTaskPriorityQueueSorter.message(holder, runnable)` 的调用重定向成对
 * `ChunkTaskPriorityQueueSorter.message(Runnable, long, IntSupplier)` 的调用,
 * 并把 IntSupplier 换成 `min(holder.getQueueLevel(), 33)`。
 * 优先级效果与 1.21.3 完全一致, 且没有丢掉任何一个生成任务。
 *
 * 1.21.3 -> 1.21.1 的映射变化: `ChunkTaskDispatcher.submit(Runnable,long,IntSupplier)`
 *   → `ChunkTaskPriorityQueueSorter.message(Runnable,long,IntSupplier)`;
 *   排序键 `getTicketLevel()` → `getQueueLevel()` (1.21.1 的排序键就是 queueLevel).
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGenerationMixin {

    /**
     * CP 请求/持票 chunk 的投递优先级上限.
     *
     * v0.11.5c (2026-09-13 停摆根因修复): 31 → 33.
     *   旧的 31 与"玩家自身区域"同级, 而 CP 预生成动辄几百个区块 → 同优先级 FIFO 下
     *   玩家 tick 实体时急需的区块被排在预生成大队列后面 → 主线程 park 12~15 秒
     *   (两处停摆 jstack 签名一致: ServerChunkCache$MainThreadExecutor; GC 已排除).
     *   改成 33 后: CP 仍排在原版环状浅层依赖 (34~41) 之前 (提前生成收益保留),
     *   但**不再插到玩家自身区域 (31~32) 前面** —— 这正是本类注释原本想表达的效果.
     */
    private static final int CP_PRIORITY_CAP = 33;

    private static final AtomicLong cpBoosted = new AtomicLong();
    private static final AtomicLong cpNormal = new AtomicLong();
    private static final AtomicLong cpLastLog = new AtomicLong();

    /**
     * 1.21.1 版: 重定向 worldgen 消息构造, 只换排序 level, 任务原样投递.
     *
     * 原调用点 (1.21.1 official mappings):
     *   ChunkTaskPriorityQueueSorter.message(GenerationChunkHolder, Runnable) : Message
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "runGenerationTask",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Lnet/minecraft/server/level/GenerationChunkHolder;Ljava/lang/Runnable;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"
        )
    )
    private ChunkTaskPriorityQueueSorter.Message chunkpilot$submitWithCpPriority(
            GenerationChunkHolder holder, Runnable runnable) {
        // 原版排序键: holder.getQueueLevel() (1.21.1 ChunkTaskPriorityQueueSorter.message 内部使用)
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
                    log("CP 独占生成: 开始对 CP 请求区块提优先级 (cap=" + CP_PRIORITY_CAP + ")");
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
            return FabricPlatform.isChunkRequestedByCp(chunkPosLong)
                || FabricPlatform.isChunkTicketedByCp(chunkPosLong);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每 20000 次提交打一行统计, 便于飞行测试时判断独占生成是否真的在起作用. */
    private static void chunkpilot$maybeLogSummary() {
        long total = cpBoosted.get() + cpNormal.get();
        long last = cpLastLog.get();
        if (total - last >= 20_000 && cpLastLog.compareAndSet(last, total)) {
            log("CP 独占生成统计: CP 优先=" + cpBoosted.get() + ", 原版正常=" + cpNormal.get());
        }
    }

    private static void log(String msg) {
        try {
            org.slf4j.LoggerFactory.getLogger("ChunkPilot").info("[ChunkPilot] " + msg);
        } catch (Throwable t) { /* ignore */ }
    }
}
