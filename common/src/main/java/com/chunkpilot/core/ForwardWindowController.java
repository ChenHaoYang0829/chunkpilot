package com.chunkpilot.core;

import com.chunkpilot.config.ChunkPilotConfig;
import com.chunkpilot.config.ForwardWindowConfig;
import com.chunkpilot.platform.PlatformAbstraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.11.6 前瞻窗口控制器 (Forward Look-ahead Window).
 *
 * 见 {@link com.chunkpilot.config.ForwardWindowConfig} 的设计说明.
 *
 * 职责:
 *   1) 维护每个高速移动玩家的**锚点票** (生成侧前移, anchorLevel &gt; 0 时有效);
 *   2) 维护每个高速移动玩家的**跟踪窗口偏移** (发送侧前移, 由
 *      ChunkMapTrackingViewMixin 读取).
 *
 * 线程模型: 全部在服务端主线程 (ChunkLoadOptimizer.onPlayerChunkUpdate) 调用;
 * Mixin 读取 shifts 也在主线程 —— 仍用 ConcurrentHashMap 防御离线清理竞态.
 */
public class ForwardWindowController {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilot");

    /**
     * 玩家前瞻状态.
     *   - anchorLevel = 0 表示"没有真正发票" (只做发送侧偏移), 撤销时跳过;
     *   - worldId 与票一起记录, 撤销永远用"发票时的维度" (v0.11.4 同款防护);
     *   - anchorDirection = 发票时用的方向, 用来判断"累积转向"是否已大到需要搬锚点.
     */
    private record WinState(int worldId, int anchorX, int anchorZ, int anchorLevel,
                            int shiftX, int shiftZ, double anchorDirection,
                            long lastMoveTick, long lastRefreshTick) {
        boolean hasTicket() { return anchorLevel > 0; }
    }

    private final Map<UUID, WinState> states = new ConcurrentHashMap<>();

    /** 供 Mixin 快速读取: UUID → [shiftX, shiftZ]. 不存在 = 不干预. */
    private final Map<UUID, int[]> shifts = new ConcurrentHashMap<>();

    // ---- 统计 (供 /chunkpilot status / diagnose 展示) ----
    private long activations = 0;
    private long moves = 0;
    private long refreshes = 0;
    private long deactivations = 0;
    private long errors = 0;

    // ================= Mixin 查询接口 =================

    /**
     * 该玩家当前的跟踪窗口中心偏移 (chunks). null = 不干预 (走原版).
     * Mixin 每个玩家每 tick 调用一次, 必须廉价.
     */
    public int[] trackingShift(UUID playerId) {
        if (shifts.isEmpty()) return null;
        return shifts.get(playerId);
    }

    // ================= 主更新入口 =================

    /**
     * 每 tick 由 {@link ChunkLoadOptimizer} 调用.
     *
     * @param speedBps  玩家速度 (blocks/tick)
     * @param direction 移动方向 (弧度, atan2(dz, dx))
     */
    public void update(UUID playerId, int worldId, int playerChunkX, int playerChunkZ,
                       double speedBps, double direction,
                       int viewDistance, PlatformAbstraction platform,
                       ChunkPilotConfig config, long tick) {
        try {
            ForwardWindowConfig cfg = config.forwardWindow;
            if (cfg == null || !cfg.enabled) {
                deactivate(playerId, platform);
                return;
            }

            double speedChunksPerSec = speedBps * 20.0 / 16.0;
            if (speedChunksPerSec < cfg.minSpeed || speedChunksPerSec > cfg.maxSpeed) {
                // v0.11.6: 优雅收敛.
                // 直接 deactivate 会把窗口中心一次性挪回玩家脚下 → 后方 S 列区块(约 6x21=126 块)
                // 在同一 tick 内全部挤进待发集合, 落地/减速瞬间形成一次"回补风暴".
                // 改成每次最多缩 1 格 (约 0.3~0.6 秒缩完), 并把锚点票立刻撤掉(不再需要前向预生成).
                WinState prevState = states.get(playerId);
                if (prevState != null && (prevState.shiftX() != 0 || prevState.shiftZ() != 0)
                        && tick - prevState.lastMoveTick() >= 1) {
                    removeAnchor(prevState, platform);
                    int nsx = shrinkTowardZero(prevState.shiftX());
                    int nsz = shrinkTowardZero(prevState.shiftZ());
                    publishShift(playerId, nsx, nsz);
                    if (nsx == 0 && nsz == 0) {
                        states.remove(playerId);
                        deactivations++;
                    } else {
                        states.put(playerId, new WinState(prevState.worldId(), 0, 0, 0,
                            nsx, nsz, prevState.anchorDirection(), tick, tick));
                    }
                    return;
                }
                deactivate(playerId, platform);
                return;
            }

            int vd = Math.max(2, viewDistance);
            int A = Math.max(0, Math.min(cfg.aheadChunks, 32));
            int B = Math.max(0, Math.min(cfg.forwardExtra, A));
            int base = Math.max(2, 33 - vd);          // 玩家自身区块的等级
            int anchorLevel = Math.max(2, Math.min(33, base + A - B));

            int wantShift = cfg.trackingShift >= 0 ? cfg.trackingShift : A;
            wantShift = Math.max(0, Math.min(wantShift, vd));
            // 发送侧前移不应超过生成侧延伸, 否则那段区间永远没东西可发
            // (只是白等, 不产生错误; 但钳住更省心, 也让日志更好读)
            wantShift = Math.min(wantShift, B);

            boolean wantAnchor = A > 0 && B > 0;
            int newLevel = wantAnchor ? anchorLevel : 0;
            if (!wantAnchor && wantShift == 0) {
                deactivate(playerId, platform);
                return;
            }

            double dirX = Math.cos(direction);
            double dirZ = Math.sin(direction);
            int anchorX = playerChunkX + (int) Math.round(A * dirX);
            int anchorZ = playerChunkZ + (int) Math.round(A * dirZ);
            int shiftX = (int) Math.round(wantShift * dirX);
            int shiftZ = (int) Math.round(wantShift * dirZ);

            WinState prev = states.get(playerId);

            // 换维度: 先撤掉旧维度的票
            if (prev != null && prev.worldId() != worldId) {
                removeAnchor(prev, platform);
                prev = null;
            }

            if (prev != null) {
                boolean sameAnchor = (!wantAnchor && !prev.hasTicket())
                    || (wantAnchor && prev.hasTicket()
                        && prev.anchorX() == anchorX && prev.anchorZ() == anchorZ
                        && prev.anchorLevel() == anchorLevel);
                if (sameAnchor) {
                    // 锚点不变 → 只推进发送侧偏移 + 按周期续票
                    publishShift(playerId, shiftX, shiftZ);
                    long refreshAt = maybeRefresh(prev, platform, cfg, tick);
                    states.put(playerId, new WinState(prev.worldId(), prev.anchorX(), prev.anchorZ(),
                        prev.anchorLevel(), shiftX, shiftZ, prev.anchorDirection(),
                        prev.lastMoveTick(), refreshAt));
                    return;
                }
                // 锚点该搬: 节流 (未到间隔且累积转向不大就再等等)
                if (tick - prev.lastMoveTick() < Math.max(1, cfg.updateTicks)
                        && Math.abs(SectorCalculator.normalizeAngle(direction - prev.anchorDirection()))
                           <= Math.toRadians(cfg.turnThresholdDeg)) {
                    return;
                }
                removeAnchor(prev, platform);
                moves++;
            }

            if (wantAnchor) {
                if (!platform.addChunkTicket(worldId, anchorX, anchorZ, newLevel)) {
                    // 平台未能发票 (玩家已离线等) → 不要留下假状态
                    deactivate(playerId, platform);
                    return;
                }
                if (prev == null) activations++;
            }
            publishShift(playerId, shiftX, shiftZ);
            states.put(playerId, new WinState(worldId, anchorX, anchorZ, newLevel,
                shiftX, shiftZ, direction, tick, tick));
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 200 == 0) {
                LOG.warn(com.chunkpilot.i18n.I18n.tr("chunkpilot.log.forward_failed",
                    errors, t.toString()));
            }
        }
    }

    /** 玩家离线 / 停车 / 关功能时清理. */
    public void deactivate(UUID playerId, PlatformAbstraction platform) {
        shifts.remove(playerId);
        WinState prev = states.remove(playerId);
        if (prev == null) return;
        deactivations++;
        removeAnchor(prev, platform);
    }

    // ================= 内部 =================

    /** 每次调用向 0 靠近 1 格 (优雅收敛用). */
    private static int shrinkTowardZero(int v) {
        if (v > 0) return v - 1;
        if (v < 0) return v + 1;
        return 0;
    }

    private void publishShift(UUID playerId, int shiftX, int shiftZ) {
        if (shiftX == 0 && shiftZ == 0) {
            shifts.remove(playerId);
            return;
        }
        int[] cur = shifts.get(playerId);
        if (cur != null && cur[0] == shiftX && cur[1] == shiftZ) return;
        shifts.put(playerId, new int[]{shiftX, shiftZ});
    }

    /** @return 本次刷新后的 lastRefreshTick */
    private long maybeRefresh(WinState prev, PlatformAbstraction platform,
                              ForwardWindowConfig cfg, long tick) {
        if (!prev.hasTicket()) return tick;
        if (tick - prev.lastRefreshTick() < Math.max(1, cfg.refreshTicks)) return prev.lastRefreshTick();
        try {
            platform.addChunkTicket(prev.worldId(), prev.anchorX(), prev.anchorZ(), prev.anchorLevel());
            refreshes++;
            return tick;
        } catch (Throwable t) {
            errors++;
            return prev.lastRefreshTick();
        }
    }

    private void removeAnchor(WinState prev, PlatformAbstraction platform) {
        if (!prev.hasTicket()) return;
        try {
            platform.removeChunkTicket(prev.worldId(), prev.anchorX(), prev.anchorZ(), prev.anchorLevel());
        } catch (Throwable t) {
            errors++;
        }
    }

    // ================= 诊断 =================

    /** 当前活跃的锚点票数量. */
    public int activeAnchors() {
        int n = 0;
        for (WinState s : states.values()) if (s.hasTicket()) n++;
        return n;
    }

    /** 当前活跃的跟踪窗口偏移数量. */
    public int activeShifts() { return shifts.size(); }

    public AnchorInfo info(UUID playerId) {
        WinState s = states.get(playerId);
        int[] sh = shifts.get(playerId);
        if (s == null && sh == null) return null;
        return new AnchorInfo(
            s == null ? 0 : s.anchorX(), s == null ? 0 : s.anchorZ(),
            s == null ? 0 : s.anchorLevel(),
            sh == null ? 0 : sh[0], sh == null ? 0 : sh[1]);
    }

    public record AnchorInfo(int anchorX, int anchorZ, int anchorLevel, int shiftX, int shiftZ) {}

    public String statsText() {
        return statsText(null);
    }

    /** @param viewerId 观看者 (玩家 → 其客户端语言; null → 全局语言) */
    public String statsText(java.util.UUID viewerId) {
        return com.chunkpilot.i18n.I18n.trFor(viewerId, "chunkpilot.forward.stats",
            activeAnchors(), shifts.size(), activations, moves, refreshes, deactivations, errors);
    }

    public long getActivations() { return activations; }
    public long getMoves() { return moves; }
    public long getErrors() { return errors; }

    /** 服务器停止 / 全量清理. */
    public void clearAll(PlatformAbstraction platform) {
        for (UUID id : new ArrayList<>(states.keySet())) {
            deactivate(id, platform);
        }
        shifts.clear();
    }
}
