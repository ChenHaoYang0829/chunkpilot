package com.chunkpilot.neoforge.mixin;

import com.chunkpilot.ChunkPilot;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.11.10 NeoForge 非阻塞 {@code isExistingChunkFull} —— 治 watchdog 崩服.
 *
 * ============================ 阻塞点 (1.21.3 实测崩服栈, 两次完全同签名) ============================
 *   Server Watchdog: A single server tick took 60.00 seconds
 *     at CompletableFuture.join
 *     at ChunkMap.isExistingChunkFull(ChunkMap.java:814)
 *     at ChunkMap.save(ChunkMap.java:776)
 *     at ChunkMap.lambda$scheduleUnload$12(ChunkMap.java:540)
 *     at ChunkMap.processUnloads(ChunkMap.java:500)
 *     at ChunkMap.tick(ChunkMap.java:465)
 *
 * `isExistingChunkFull` 的实现 (javap 实测):
 *   byte b = chunkTypeCache.get(pos.toLong());
 *   if (b != 0) return b == 1;
 *   try { tag = readChunk(pos).join().orElse(null); }        // ← 主线程同步等一次**磁盘读**
 *   catch (Exception e) { LOGGER.error(...); markPositionReplaceable(pos); return false; }
 *   return markPosition(pos, getChunkTypeFromTag(tag)) == 1;
 *
 * 注意: 这一条是 **vanilla 1.21.3 自己的代码**, 不是 NeoForge 引入的
 *   (已用 javap 逐条比对: vanilla 与 neoforge-21.3.97-server.jar 里该方法字节码**完全一致**,
 *    只有常量池索引不同).
 *
 * 为什么在 `sync-chunk-writes=true` 下必然出事 (机制, 已从字节码确认):
 *   * `sync` 只传给 `RegionFileStorage(info, path, sync)` ⇒ 落盘变成立即 write+fsync;
 *   * `IOWorker` 用 `PriorityConsecutiveExecutor(Util.ioPool())` 按 **region 串行** 执行任务;
 *   * 卸载路径 `lambda$scheduleUnload$12` 先 `chunkTypeCache.remove(pos)` **再** `save(chunk)`,
 *     所以每次卸载都命中 `b == 0` 分支 ⇒ 每次卸载都要发一次磁盘读并与同 region 的 fsync 落盘排队;
 *   * 34 m/s + CP 前瞻窗口 ⇒ 卸载时仍是 PROTOCHUNK 的区块数量暴涨 ⇒ 主线程在 join() 上被
 *     成千上万次 fsync 挡在后面 ⇒ 单 tick ≥60 秒 ⇒ watchdog 强制关服.
 *   C2ME 会接管整套区块 IO 从而绕开这条路径, 而 C2ME 只有 fabric 版 ⇒ NeoForge 必须自己治。
 *
 * ============================ 本 Mixin 做什么 ============================
 *   缓存命中 (b != 0) → 一行都不改, 交给原版 (原版此时不会读盘, 不阻塞)。
 *   缓存未知 (b == 0) → 自己发一次 `readChunk(pos)` (**异步, 绝不 join**):
 *     * 若 future 已就绪 → 完整复刻原版这一段的语义 (markPosition/markPositionReplaceable),
 *       答案与原版逐位一致;
 *     * 若未就绪 → 返回 **false**, 与"原版对未缓存位置的答案"完全一致 (见下), 且**不写缓存**,
 *       读请求留在飞, 下一 tick 再取真答案。
 *
 * 为什么"未就绪 → false"是无害的 (这是本修复成立的关键):
 *   原版对**未缓存**的位置本来就返回 false —— `markPosition` 返回的是
 *   `Long2ByteOpenHashMap.put(key, v)` 的**旧值**, 而 ChunkMap 构造里
 *   `new Long2ByteOpenHashMap()` 没有设置 defaultReturnValue, 新键 put 返回 **0**,
 *   于是 `return markPosition(...) == 1` = `0 == 1` = **false**。
 *   也就是说: 那次磁盘读的作用只是"把结论写进 chunkTypeCache 供**后续**询问使用",
 *   对**这一次**询问的返回值没有任何影响。我们把写入缓存这件事推迟到 future 就绪之后,
 *   返回值则与原版同一次询问完全一致 ⇒ 只去掉阻塞, 不改变语义。
 *
 * 稳健性约定 (与项目其它 mixin 一致):
 *   * 任何异常都静默回退原版行为 (不 cancel, 让原版继续);
 *   * 独立开关 `[protection] nonBlockingUnloadCheck = true`, 关掉即 100% 原版;
 *   * 只在服务端生效 (ChunkMap 本身只在服务端存在, 客户端没有这个类)。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapNonBlockingUnloadMixin {

    /** 原版字段: pos → 磁盘区块类型缓存 (0=未知, +1=FULL/LEVELCHUNK, -1=非完整/可覆盖). */
    @Shadow
    private Long2ByteMap chunkTypeCache;

    @Shadow
    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow
    private void markPositionReplaceable(ChunkPos pos) {
        throw new AssertionError();
    }

    @Shadow
    private byte markPosition(ChunkPos pos, ChunkType type) {
        throw new AssertionError();
    }

    /**
     * "在飞"的磁盘读 (每个 pos 最多一个, 避免高负载下反复投递把 io 队列越堆越长).
     * 懒初始化 (不依赖 mixin 字段初始化器), 只读主线程访问, 用 ConcurrentHashMap 只为稳妥。
     */
    @Unique
    private Map<Long, CompletableFuture<Optional<CompoundTag>>> chunkpilot$probes;

    /** 因为"结果未就绪"而跳过阻塞读、直接按原版未缓存答案返回的次数 (诊断用). */
    @Unique
    private static long chunkpilot$nonBlockingHits = 0L;

    /** 诊断日志: 首次生效 + 之后每 2000 次打一行, 用来在服务端日志里**实证** mixin 生效与省下的阻塞次数. */
    @Unique
    private static final Logger CHUNKPILOT_LOG = LoggerFactory.getLogger("ChunkPilot");

    @Inject(method = "isExistingChunkFull", at = @At("HEAD"), cancellable = true)
    private void chunkpilot$nonBlockingExistingChunkFull(ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        try {
            ChunkPilot cp = ChunkPilot.getInstance();
            if (cp == null || cp.getConfig() == null || !cp.getConfig().nonBlockingUnloadCheck) {
                return; // 开关关闭 → 100% 原版
            }

            long key = pos.toLong();
            if (this.chunkTypeCache.get(key) != 0) {
                return; // 已有结论 → 原版走缓存分支, 不会读盘, 不阻塞
            }

            Map<Long, CompletableFuture<Optional<CompoundTag>>> probes = this.chunkpilot$probes;
            if (probes == null) {
                probes = new ConcurrentHashMap<>();
                this.chunkpilot$probes = probes;
            } else if (probes.size() > 4096) {
                probes.clear(); // 兜底: 防止"投了读但再也没人问"的位置把内存撑大
            }

            CompletableFuture<Optional<CompoundTag>> future = probes.get(key);
            if (future == null) {
                future = this.readChunk(pos); // 异步投出去, 绝不 join
                probes.put(key, future);
            }

            Optional<CompoundTag> ready = future.getNow(null); // 非阻塞取值
            if (ready == null) {
                // 未就绪: 与原版"未缓存位置的答案"一致 (false), 不写缓存, 下 tick 再看
                long hits = ++chunkpilot$nonBlockingHits;
                if (hits == 1L || hits % 2000L == 0L) {
                    CHUNKPILOT_LOG.info(
                        "[ChunkPilot] non-blocking isExistingChunkFull: 已跳过 {} 次主线程磁盘读等待 (未 join readChunk)",
                        hits);
                }
                cir.setReturnValue(Boolean.FALSE);
                return;
            }

            // 已就绪: 逐位复刻原版这一段
            probes.remove(key);
            CompoundTag tag = ready.orElse(null);
            if (tag == null) {
                this.markPositionReplaceable(pos);
                cir.setReturnValue(Boolean.FALSE);
                return;
            }
            cir.setReturnValue(this.markPosition(pos, SerializableChunkData.getChunkTypeFromTag(tag)) == 1);
        } catch (Throwable t) {
            // 任何异常都不干扰原版: 不 cancel, 让原版继续 (可能阻塞, 但保证行为正确)
        }
    }
}
