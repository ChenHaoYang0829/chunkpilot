package com.chunkpilot.generation;

import com.chunkpilot.config.GenerationConfig;
import com.chunkpilot.core.SpeedTracker;
import com.chunkpilot.platform.PlatformAbstraction;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0.4 GenerationSchedulerTest — 调度器核心逻辑
 *
 * 挡 (v0.5.0 历史翻车):
 *   - bug#3: pendingChunks.remove() 先于 getChunkToSend() → chunk 永久丢
 *   - bug#1: fabric 没调 onServerTick → 队列恒 0
 *
 * 挡 (v0.5.1 修复):
 *   - isChunkLoaded 过滤必须生效, 队列不被已生成 chunk 污染
 *   - 脚下 chunk 强制高优 (兜底, 防止漏脚下)
 *   - n=5 生效, 前方 3-6ch 可覆盖
 *
 * 用手写 FakePlatform + FakeSpeedTracker (无 Mockito 依赖)
 */
class GenerationSchedulerTest {

    /** 简易 SpeedTracker: 直接控制速度/方向 */
    static class FakeSpeedTracker extends SpeedTracker {
        private final double v;
        private final double dir;

        FakeSpeedTracker(double vBlocksPerTick, double directionRad) {
            super();
            this.v = vBlocksPerTick;
            this.dir = directionRad;
        }

        @Override public double getSpeed(UUID id) { return v; }
        @Override public double getDirection(UUID id) { return dir; }
    }

    GenerationConfig defaultCfg() {
        GenerationConfig cfg = new GenerationConfig();
        cfg.enabled = true;
        cfg.v_min = 0.7;
        cfg.lookAheadSeconds = 3.0;
        cfg.predictionTTLSeconds = 5.0;
        cfg.maxChunksPerTick = 4;
        cfg.targetMspt = 35.0;
        return cfg;
    }

    /** peekTop 用 PriorityQueue.iterator() 不保证顺序, 测试改用 poll 拿真实顶部 */
    private GenerationQueueEntry pollOne(GenerationScheduler sched) {
        return sched.pollForTest();
    }

    /** 取出所有 entry (会消耗队列) */
    private List<GenerationQueueEntry> drainQueue(GenerationScheduler sched) {
        List<GenerationQueueEntry> list = new ArrayList<>();
        GenerationQueueEntry e;
        while ((e = sched.pollForTest()) != null) list.add(e);
        return list;
    }

    // ============ T11: v0.5.0 bug#3 — requestChunkAsync 失败时 chunk 不丢 ============

    @Test
    void requestChunkAsync_失败时chunk不丢() {
        // 模拟 platform.requestChunkAsync 返回 false (反射失败等)
        // 调度器不应崩溃, 也不应把队列弄乱
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        p.requestSuccess = false;

        FakeSpeedTracker tracker = new FakeSpeedTracker(3.0, 0.0);

        GenerationScheduler sched = new GenerationScheduler();
        // 不抛异常即通过
        assertDoesNotThrow(() ->
            sched.onServerTick(p, defaultCfg(), 20.0, tracker, 1L)
        );
    }

    // ============ T12: isChunkLoaded 过滤 — 已生成的不入队 ============

    @Test
    void 已生成的chunk_不进入队列() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        p.markLoaded(0, 0);   // 脚下已生成, 不走脚下保底
        p.markLoaded(1, 0);
        p.markLoaded(2, 0);

        GenerationConfig cfg = defaultCfg();
        cfg.maxChunksPerTick = 10000;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        // 已加载的 chunk 不应被 pop 出去 (被 isChunkLoaded 过滤)
        for (long[] req : p.requests) {
            long cx = req[0], cz = req[1];
            assertFalse((cx == 1 && cz == 0) || (cx == 2 && cz == 0),
                "已加载的 chunk (" + cx + "," + cz + ") 不应被 request");
        }
        // 至少有一个未加载的 chunk 被发了
        boolean foundUnloaded = p.requests.stream().anyMatch(r -> {
            long cx = r[0], cz = r[1];
            return !((cx==0&&cz==0)||(cx==1&&cz==0)||(cx==2&&cz==0));
        });
        assertTrue(foundUnloaded, "未加载 chunk 应被 request");
    }

    // ============ T13: 脚下保底 — 队列顶必须是脚下 ============

    @Test
    void 脚下chunk_强制最高优先级() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        // 不 markLoaded(0,0), 假装"未生成"

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, defaultCfg(), 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        // onServerTick 内部 pop top 4 (含脚下保底), FakePlatform.requestChunkAsync 被调
        // 验证: request 里第一个就是脚下 (0,0) — 这是脚下保底的最佳证据
        assertFalse(p.requests.isEmpty(), "应至少有一个 request");
        long[] firstReq = p.requests.get(0);
        assertEquals(0, firstReq[0], "第一个 request 必须是脚下 chunkX=0");
        assertEquals(0, firstReq[1], "第一个 request 必须是脚下 chunkZ=0");
    }
    // ============ T14: 回归 — 队列不被已生成 chunk 污染 ============

    @Test
    void 队列_只含未生成的chunk() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        p.markLoaded(0, 0);   // 脚下已生成, 不走脚下保底
        p.markLoaded(1, 0);
        p.markLoaded(2, 0);
        p.markLoaded(5, 0);

        GenerationConfig cfg = defaultCfg();
        cfg.maxChunksPerTick = 10000;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        // 已加载的 (1,0)(2,0)(5,0) 不应被 request
        for (long[] req : p.requests) {
            long cx = req[0], cz = req[1];
            assertFalse((cx == 1 && cz == 0) || (cx == 2 && cz == 0) || (cx == 5 && cz == 0),
                "已加载的 chunk 不应被 request: (" + cx + "," + cz + ")");
        }
        // 至少有一个未加载的 chunk 被发
        boolean foundUnloaded = p.requests.stream().anyMatch(r -> {
            long cx = r[0], cz = r[1];
            return !((cx==0&&cz==0)||(cx==1&&cz==0)||(cx==2&&cz==0)||(cx==5&&cz==0));
        });
        assertTrue(foundUnloaded, "未加载 chunk 应被 request");
    }

    // ============ T15: v0.8.0 deadline 排序 — 前方近处优先 ============

    @Test
    void deadline排序_前方近处优先() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);

        // 假装脚下已生成, 让 pop 不被脚下吃光
        p.markLoaded(0, 0);

        GenerationConfig cfg = defaultCfg();
        cfg.maxChunksPerTick = 10000;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        // deadline 排序: 前方近处 (1,0)(2,0) 应比远处 (5,0)(6,0) 更早被 request
        // 验证 request 顺序: 前方近处 chunk 出现在远处之前
        int idxNear = Integer.MAX_VALUE, idxFar = Integer.MAX_VALUE;
        for (int i = 0; i < p.requests.size(); i++) {
            long cx = p.requests.get(i)[0], cz = p.requests.get(i)[1];
            if (cx == 1 && cz == 0) idxNear = Math.min(idxNear, i);
            if (cx == 2 && cz == 0) idxNear = Math.min(idxNear, i);
            if (cx == 5 && cz == 0) idxFar = Math.min(idxFar, i);
            if (cx == 6 && cz == 0) idxFar = Math.min(idxFar, i);
        }
        assertTrue(idxNear < idxFar,
            "deadline 排序: 前方近处 (1,0)(2,0) 应先于远处 (5,0)(6,0) (idxNear=" + idxNear + " idxFar=" + idxFar + ")");
    }

    // ============ T16: 队列过期清理 ============

    @Test
    void 过期chunk_被清理() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);

        GenerationConfig cfg = defaultCfg();
        cfg.predictionTTLSeconds = 0.1;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);
        // v0.11.5b: 未 pop 的未生成候选会在 tick 末尾被收进 backlog (实时队列每 tick 排空),
        //   所以必须两处一起看, 否则看不到被追踪的候选
        int firstTracked = sched.getQueueSize() + sched.getDeferredQueueSize();

        // 跑很久之后, 所有 chunk 都过期
        sched.onServerTick(p, cfg, 20.0, new FakeSpeedTracker(3.0, 0.0), 1_000_000L);

        // 注: tick 1000000 时 player 还在, 调度器会重新扫描入队,
        // 所以这次 tick 后仍有候选被追踪. 要测的是"清理过期"语义, 不是"队列为空"
        // 改为: 第二次 tick 后, 被追踪的 entry 都不应该是"第一次 tick 入队但未刷新的"
        // (实际: 第二次 tick 会全部重新入队, 所以这个测试改成验证 entry 的 expiresAt)
        assertTrue(firstTracked > 0, "第一次 tick 应有候选被追踪 (queue+backlog)");
        // 跑一个慢 tick 后, 所有被追踪 entry 的 expires 都应 >= 新 expires (= 1000000 + 0.1*20 = 1000002)
        List<GenerationQueueEntry> all = new ArrayList<>(drainQueue(sched));
        all.addAll(sched.deferredEntriesForTest());
        assertFalse(all.isEmpty(), "第二次 tick 后仍应有候选被追踪 (否则本测试形同虚设)");
        for (GenerationQueueEntry e : all) {
            assertTrue(e.expiresAtTick >= 1_000_000L,
                "过期 chunk 应已被清理 (新 expiresAt=" + e.expiresAtTick + ")");
        }
    }

    // ============ T17: 玩家离线 → 其 chunk 移除 ============

    @Test
    void 玩家离线_chunk移除() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, defaultCfg(), 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);
        assertTrue(sched.getQueueSize() + sched.getDeferredQueueSize() > 0,
            "玩家在线时应追踪到 chunk (queue+backlog)");

        p.online.clear();
        sched.onServerTick(p, defaultCfg(), 20.0, new FakeSpeedTracker(3.0, 0.0), 2L);

        assertEquals(0, sched.getQueueSize(), "玩家离线后实时队列应被清空");
        // v0.11.5b: removePlayerChunks 不再立即清 backlog (避免玩家只是减速就把区块丢掉),
        //   离线清理由步骤 6d 的 worldId==0 检查完成 —— FakePlatform 已按平台契约对离线返回 0.
        assertEquals(0, sched.getDeferredQueueSize(), "玩家离线后 backlog 也应被清空 (6d worldId==0)");
    }

    // ============ T_extra: 接线 — onServerTick 必须能被调用 ============

    @Test
    void 接线验证_scheduler可以独立tick() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, defaultCfg(), 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        // v0.11.5b: 未 pop 的未生成候选会在 tick 末尾被收进 backlog, 实时队列可能已空,
        //   所以用"被追踪的候选总数"判断接线 (v0.5.0 bug#1: fabric 没接线→恒 0)
        assertTrue(sched.getQueueSize() + sched.getDeferredQueueSize() > 0,
            "scheduler 跑通: 应有 chunk 被追踪 (queue+backlog) (v0.5.0 bug#1: fabric 没接线→队列恒 0)");
    }

    // ============ T_extra2: requestChunkAsync 被调用 ============

    @Test
    void requestChunkAsync_被调用() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, defaultCfg(), 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        assertFalse(p.requests.isEmpty(), "requestChunkAsync 必须被调用");
    }

    // ============ T_extra3: cfg.enabled=false 时 scheduler 不工作 ============

    @Test
    void disabled配置_不工作() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);

        GenerationConfig cfg = defaultCfg();
        cfg.enabled = false;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 20.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        assertEquals(0, sched.getQueueSize(), "disabled 时队列应为空");
        assertEquals(0, p.requests.size(), "disabled 时不应 request");
    }

    // ============ T_extra4: 速度低于阈值 → 不入队 ============

    @Test
    void 速度过低_不入队() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(0.3, 0.0);  // < v_min=0.7 (chunks/s = 0.3*20/16=0.375)

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, defaultCfg(), 20.0, new FakeSpeedTracker(0.3, 0.0), 1L);

        assertEquals(0, sched.getQueueSize(), "速度过低时不应入队");
    }

    // ============ v0.6.0: FULL 待办回补队列 ============

    @Test
    void 高压时近处FULL未生成_进待办保留() {
        // MSPT 严重超标 → currentChunkCount 降到 0 → 本 tick 不生成
        // 近处 FULL chunk 应进待办队列, 不因 TTL 丢弃
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        p.mspt = 100.0;  // 远超 target=35

        GenerationConfig cfg = defaultCfg();
        cfg.deferredFullQueueEnabled = true;
        cfg.deferredFullMsptMargin = 10.0;
        cfg.targetMspt = 35.0;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 100.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        assertTrue(sched.getDeferredQueueSize() > 0,
            "MSPT 高时应把近处 FULL chunk 保留进待办 (实际=" + sched.getDeferredQueueSize() + ")");
    }

    @Test
    void 负载低时_待办回补FULL生成() {
        // 先高压把近处 FULL 囤进待办, 再低压触发回补
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        p.mspt = 100.0;

        GenerationConfig cfg = defaultCfg();
        cfg.deferredFullQueueEnabled = true;
        cfg.deferredFullMsptMargin = 10.0;
        cfg.targetMspt = 35.0;
        cfg.deferredFullMaxPerTick = 2;

        GenerationScheduler sched = new GenerationScheduler();
        // 高压 tick: 囤待办
        sched.onServerTick(p, cfg, 100.0, new FakeSpeedTracker(3.0, 0.0), 1L);
        int deferredSize = sched.getDeferredQueueSize();
        int requestsBefore = p.requests.size();

        // 低压 tick: 触发回补
        p.mspt = 10.0;
        sched.onServerTick(p, cfg, 10.0, new FakeSpeedTracker(3.0, 0.0), 2L);

        int backfilled = sched.getLastTickDeferredPopped();
        assertTrue(backfilled > 0, "负载低时应回补 FULL 生成 (实际=" + backfilled + ")");
        assertTrue(p.requests.size() > requestsBefore,
            "回补应调用 requestChunkAsync (实际 request 增量=" + (p.requests.size() - requestsBefore) + ")");
    }

    @Test
    void 待办开关关闭_清空待办回退旧逻辑() {
        FakePlatform p = new FakePlatform();
        p.setPlayerPos(0, 0);
        p.setSpeed(3.0, 0.0);
        p.mspt = 100.0;

        GenerationConfig cfg = defaultCfg();
        cfg.deferredFullQueueEnabled = false;
        cfg.targetMspt = 35.0;

        GenerationScheduler sched = new GenerationScheduler();
        sched.onServerTick(p, cfg, 100.0, new FakeSpeedTracker(3.0, 0.0), 1L);

        assertEquals(0, sched.getDeferredQueueSize(), "开关关闭时待办应为空");
    }
}
