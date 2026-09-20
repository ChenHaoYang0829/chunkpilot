package com.chunkpilot.neoforge.util;

import net.minecraft.server.level.ServerPlayer;

/**
 * ThreadLocal 用于在 PlayerChunkSenderMixin 中传递当前 player.
 *
 * 流程:
 *   1. PlayerChunkSenderSendMixin 拦截 sendNextChunks(ServerPlayer) → 存入 ThreadLocal
 *   2. PlayerChunkSenderMixin 拦截 collectChunksToSend → 读取 ThreadLocal
 *   3. collectChunksToSend 返回后 → 清除 ThreadLocal
 */
public class PlayerChunkSendHolder {
    public static final ThreadLocal<ServerPlayer> currentPlayer = new ThreadLocal<>();
}