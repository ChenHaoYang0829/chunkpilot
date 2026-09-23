package com.chunkpilot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P2: 扇形加载自适应预算控制器
 *
 * v0.10 重构 (2026-08-27): 自适应逻辑下沉到共享 {@link AdaptiveLoadController}.
 *   - 预算档位 / 保底逻辑 / MSPT 平滑全部由共享控制器负责,
 *     与生成侧 (GenerationScheduler) 共享同一个平滑 MSPT, 不再各自独立采样.
 *   - 修掉旧版 "保底下限把高负载降档顶回 40" 的自废武功 (见 AdaptiveLoadController.suggestBudget).
 *
 * 本类只负责: 持有当前预算状态 + 持久化到 config/chunkpilot/runtime_state.toml (P3).
 *
 * 关键参数 (由 AdaptiveLoadController 分档):
 *   initialBudget: 80
 *   emergencyMspt: 50ms (紧急停手, 预算归 0)
 *   高/中/低/很低/健康 档位: 45 / 40 / 35 / 30 ms → 预算 20% / 40% / 60% / 80% / 100%
 */
public final class SectorBudgetController {

    private static final Logger LOG = LoggerFactory.getLogger("ChunkPilotBudget");

    // 持久化
    private static final long PERSIST_INTERVAL_MS = 30_000;  // 30s 兜底
    private static final double PERSIST_CHANGE_RATIO = 0.2;     // 变化 >20% 立即写
    private final Path stateFile;

    // 当前预算 (自适应, 由共享控制器驱动)
    private final AtomicInteger currentBudget = new AtomicInteger(80);
    private int lastPersistedBudget = 80;
    private long lastPersistTime = 0;

    /** 共享负载自适应控制器 (生成侧与票侧共用) */
    private final AdaptiveLoadController loadController;

    public SectorBudgetController(AdaptiveLoadController loadController) {
        this.loadController = loadController;
        // 找配置目录
        Path configDir = Paths.get("config", "chunkpilot");
        this.stateFile = configDir.resolve("runtime_state.toml");
        // P3: 启动时恢复
        int restored = loadFromDisk();
        if (restored > 0) {
            currentBudget.set(restored);
            lastPersistedBudget = restored;
            LOG.info("[ChunkPilot Budget] Restored from disk: {}", restored);
        } else {
            currentBudget.set(80);
            LOG.info("[ChunkPilot Budget] Initialized default: 80");
        }
    }

    /**
     * 根据共享控制器 (当前平滑 MSPT) 更新预算.
     *
     * @param maxBudget 来自 config.maxSectorTickets 的硬上限
     */
    public void update(int maxBudget) {
        int targetBudget = loadController.suggestBudget(maxBudget);
        int prev = currentBudget.get();
        if (targetBudget != prev) {
            currentBudget.set(targetBudget);
            // P3: 持久化判断
            maybePersist(targetBudget, prev, System.currentTimeMillis());
        }
    }

    /**
     * 获取当前预算
     */
    public int getCurrentBudget(int maxBudget) {
        int b = currentBudget.get();
        return Math.min(b, maxBudget);
    }

    // ===== P3 持久化 =====

    private void maybePersist(int newBudget, int oldBudget, long now) {
        boolean changedEnough = Math.abs(newBudget - lastPersistedBudget) > lastPersistedBudget * PERSIST_CHANGE_RATIO;
        boolean timeUp = now - lastPersistTime > PERSIST_INTERVAL_MS;
        if (changedEnough || timeUp) {
            persistToDisk(newBudget);
            lastPersistedBudget = newBudget;
            lastPersistTime = now;
        }
    }

    private void persistToDisk(int budget) {
        try {
            Path parent = stateFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String content = "# ChunkPilot Runtime State (auto-generated, do not edit)\n" +
                    "[adaptive]\n" +
                    "sector_budget = " + budget + "\n" +
                    "last_persist = \"" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date()) + "\"\n";
            Files.writeString(stateFile, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.warn("[ChunkPilot Budget] Failed to persist: {}", e.toString());
        }
    }

    private int loadFromDisk() {
        if (!Files.exists(stateFile)) return 0;
        try {
            List<String> lines = Files.readAllLines(stateFile);
            int persisted = 0;
            int defaultVal = 80;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("sector_budget")) {
                    try {
                        persisted = Integer.parseInt(line.split("=")[1].trim());
                    } catch (Exception ignored) {}
                }
            }
            if (persisted <= 0) return 0;
            // 重启恢复策略: median(持久化, 默认, 持久化*1.5)
            // 不过于信任旧值, 也不完全丢弃
            int conservative = (int) Math.round((persisted + defaultVal + persisted * 1.5) / 3.0);
            return Math.max(30, conservative); // 不低于保底
        } catch (IOException e) {
            LOG.warn("[ChunkPilot Budget] Failed to load: {}", e.toString());
            return 0;
        }
    }
}
