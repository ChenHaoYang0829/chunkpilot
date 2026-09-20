package com.chunkpilot.generation;

import com.chunkpilot.platform.PlatformAbstraction;

import java.util.*;

/**
 * 测试专用 FakePlatform — 完全控制平台返回值, 不依赖 MC.
 *
 * 设计要点:
 *   - playerPos: 模拟玩家所在 chunk 坐标
 *   - loadedChunks: 模拟 isChunkLoaded 返回 true 的 chunk 集合
 *   - requests: 捕获所有 requestChunkAsync 调用, 便于断言
 *
 * 不引 Mockito, 保持 common 模块零外部依赖.
 */
class FakePlatform implements PlatformAbstraction {
    // 玩家位置 (chunk 坐标)
    int[] playerPos = null;
    // 玩家所在 worldId
    int playerWorldId = 1;
    // 已加载/已生成的 chunk 集合 (pack(cx, cz))
    Set<Long> loadedChunks = new HashSet<>();
    // 捕获 requestChunkAsync 调用
    List<long[]> requests = new ArrayList<>();
    // requestChunkAsync 是否成功
    boolean requestSuccess = true;
    // 在线玩家 (UUID → name)
    Map<UUID, String> online = new HashMap<>();
    // 玩家 UUID
    UUID playerId = UUID.randomUUID();

    // ===== 速度追踪 =====
    double vBpt = 3.0;          // blocks/tick, 默认 60m/s
    double direction = 0.0;     // 弧度, 默认 +X

    // ===== 性能指标 =====
    double mspt = 20.0;
    int viewDistance = 10;
    double memPercent = 50.0;

    FakePlatform() {
        online.put(playerId, "TestPlayer");
        playerPos = new int[]{0, 0};
    }

    FakePlatform(UUID id) {
        this.playerId = id;
        online.put(id, "TestPlayer");
        playerPos = new int[]{0, 0};
    }

    void setPlayerPos(int cx, int cz) {
        this.playerPos = new int[]{cx, cz};
    }

    void setSpeed(double vBpt, double directionRad) {
        this.vBpt = vBpt;
        this.direction = directionRad;
    }

    /** 调用 platform.isChunkLoaded 时返回的 chunk 标记为已加载 */
    void markLoaded(int cx, int cz) {
        loadedChunks.add(packChunkPos(cx, cz));
    }

    static long packChunkPos(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    // ============ PlatformAbstraction 实现 ============

    @Override public boolean isModLoaded(String modId) { return false; }
    @Override public String getPlatformName() { return "fake"; }
    @Override public int getServerRenderDistance() { return viewDistance; }
    @Override public double getCurrentMspt() { return mspt; }
    @Override public double getMemoryUsagePercent() { return memPercent; }

    @Override public int[] getPlayerChunkPos(UUID id) { return playerPos; }
    @Override public double getPlayerDirection(UUID id) { return direction; }
    @Override public double getPlayerSpeed(UUID id) { return vBpt; }

    @Override public boolean addChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) { return true; }
    @Override public boolean removeChunkTicket(int worldId, int chunkX, int chunkZ, int ticketLevel) { return true; }

    @Override public Object getPlayerObject(UUID playerId) { return null; }
    @Override public NetStats getPlayerNetStats(UUID playerId) { return NetStats.empty(); }

    @Override public Map<UUID, String> getOnlinePlayers() { return online; }

    /**
     * 契约 (见 PlatformAbstraction#getPlayerWorldId): **未找到 (离线) 返回 0**.
     * v0.11.5b: GenerationScheduler 步骤 6d 依赖该语义清理离线玩家的 backlog,
     *   Fake 必须忠实建模, 否则测不出该行为 (真实 FabricPlatform 也是 player==null → 0).
     */
    @Override public int getPlayerWorldId(UUID id) {
        if (!online.containsKey(id)) return 0;
        return playerWorldId;
    }

    @Override
    public boolean requestChunkAsync(int worldId, int chunkX, int chunkZ) {
        requests.add(new long[]{chunkX, chunkZ});
        return requestSuccess;
    }

    @Override
    public boolean isChunkLoaded(int worldId, int chunkX, int chunkZ) {
        return loadedChunks.contains(packChunkPos(chunkX, chunkZ));
    }

    @Override public void broadcastMessage(UUID playerId, String message) {}
    @Override public void sendMessage(UUID playerId, String message) {}
    @Override public void logCommand(String executor, String message) {}
}
