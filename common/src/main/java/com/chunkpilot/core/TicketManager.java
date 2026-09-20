package com.chunkpilot.core;

import com.chunkpilot.config.ChunkPilotConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk Ticket 管理器
 * 负责发放和回收区块加载 Ticket，管理 grace period
 */
public class TicketManager {
    
    private final ChunkPilotConfig config;
    
    // 每个玩家当前持有的 ticket 区块
    private final Map<UUID, Set<SectorCalculator.ChunkPos>> activeTickets = new ConcurrentHashMap<>();
    
    // 待移除的区块（grace period 到期后移除）
    private final Map<UUID, Map<SectorCalculator.ChunkPos, Long>> pendingRemovals = new ConcurrentHashMap<>();
    
    public TicketManager(ChunkPilotConfig config) {
        this.config = config;
    }
    
    /**
     * 更新玩家的区块加载范围
     * 计算新增和移除的差异，管理 grace period
     * 
     * @param playerId 玩家 UUID
     * @param newChunks 新的加载区块集合
     * @param currentTick 当前 tick
     */
    public void updatePlayerChunks(UUID playerId, Set<SectorCalculator.ChunkPos> newChunks, long currentTick) {
        Set<SectorCalculator.ChunkPos> current = activeTickets.getOrDefault(playerId, Set.of());
        
        // 计算差异
        Set<SectorCalculator.ChunkPos> toAdd = new HashSet<>();
        Set<SectorCalculator.ChunkPos> toRemove = new HashSet<>();
        
        if (newChunks != null) {
            for (SectorCalculator.ChunkPos pos : newChunks) {
                if (!current.contains(pos)) {
                    toAdd.add(pos);
                }
            }
            for (SectorCalculator.ChunkPos pos : current) {
                if (!newChunks.contains(pos)) {
                    toRemove.add(pos);
                }
            }
        } else {
            // newChunks == null 表示不干预，清除所有 ticket
            toRemove.addAll(current);
        }
        
        // 新区块：立即发 ticket（实际发放由平台子类实现）
        // toAdd → issueTicket()
        
        // 旧区块：延迟移除（grace period）
        Map<SectorCalculator.ChunkPos, Long> pending = pendingRemovals.computeIfAbsent(
                playerId, k -> new HashMap<>());
        for (SectorCalculator.ChunkPos pos : toRemove) {
            pending.put(pos, currentTick + config.ticketExpiryTicks);
        }
        
        // 更新活跃 ticket 集合
        if (newChunks != null) {
            activeTickets.put(playerId, new HashSet<>(newChunks));
        } else {
            activeTickets.remove(playerId);
        }
    }
    
    /**
     * 处理 grace period 到期的移除
     */
    public void processExpiry(UUID playerId, long currentTick) {
        Map<SectorCalculator.ChunkPos, Long> pending = pendingRemovals.get(playerId);
        if (pending == null || pending.isEmpty()) return;
        
        Set<SectorCalculator.ChunkPos> expired = new HashSet<>();
        for (Map.Entry<SectorCalculator.ChunkPos, Long> entry : pending.entrySet()) {
            if (currentTick >= entry.getValue()) {
                expired.add(entry.getKey());
            }
        }
        
        for (SectorCalculator.ChunkPos pos : expired) {
            pending.remove(pos);
            // 实际回收 ticket 由平台子类实现
            // revokeTicket()
        }
        
        // 如果玩家在 grace period 内又回来了，取消移除
        Set<SectorCalculator.ChunkPos> active = activeTickets.get(playerId);
        if (active != null) {
            for (SectorCalculator.ChunkPos pos : active) {
                pending.remove(pos);
            }
        }
    }
    
    /**
     * 移除玩家的所有数据
     */
    public void removePlayer(UUID playerId) {
        activeTickets.remove(playerId);
        pendingRemovals.remove(playerId);
    }
    
    public Set<SectorCalculator.ChunkPos> getActiveChunks(UUID playerId) {
        return activeTickets.getOrDefault(playerId, Set.of());
    }
}