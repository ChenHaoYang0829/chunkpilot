package com.chunkpilot.neoforge.client;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.client.ChunkPilotClient;
import com.chunkpilot.config.ChunkPilotConfig;

/**
 * NeoForge 客户端入口
 *
 * 不使用 NeoForge 事件注解 (避免编译时需要客户端类),
 * 通过反射注册客户端 tick 事件.
 * 由 ChunkPilotNeoForge (服务端入口) 在启动时检测是否客户端环境,
 * 如果是则调用 init().
 */
public class ChunkPilotNeoForgeClient {

    private static long lastTickTime = 0;
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;

        ChunkPilotConfig config = ChunkPilot.getInstance().getConfig();
        ChunkPilotClient.init(config);

        // 检测 Sodium
        boolean sodiumLoaded = false;
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            sodiumLoaded = true;
        } catch (ClassNotFoundException e) {
            // Sodium 不存在
        }
        ChunkPilotClient.getInstance().setSodiumDetected(sodiumLoaded);

        if (sodiumLoaded) {
            System.out.println("[ChunkPilot] Sodium detected, using Sodium-compatible render path");
        } else {
            System.out.println("[ChunkPilot] Sodium not detected, using vanilla render path");
        }
    }

    /**
     * 每客户端 tick 调用 (由 Mixin 或事件触发).
     * 用反射获取 Minecraft 实例和玩家位置, 避免编译时依赖客户端类.
     */
    public static void onClientTick() {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled()) return;

        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object player = mcClass.getMethod("player").invoke(mc);
            if (player == null) return;

            Object blockPos = player.getClass().getMethod("blockPosition").invoke(player);
            int blockX = (int) blockPos.getClass().getMethod("getX").invoke(blockPos);
            int blockZ = (int) blockPos.getClass().getMethod("getZ").invoke(blockPos);
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;

            Object deltaMove = player.getClass().getMethod("getDeltaMovement").invoke(player);
            double dx = (double) deltaMove.getClass().getField("x").get(deltaMove);
            double dz = (double) deltaMove.getClass().getField("z").get(deltaMove);
            double speedBpt = Math.sqrt(dx * dx + dz * dz);
            double direction = 0;
            if (dx != 0 || dz != 0) {
                direction = Math.atan2(dz, dx);
            }

            client.updatePlayerState(chunkX, chunkZ, speedBpt, direction);

            long now = System.nanoTime();
            if (lastTickTime > 0) {
                long frameNanos = now - lastTickTime;
                client.onFrameEnd(frameNanos);
            }
            lastTickTime = now;
        } catch (Exception e) {
            // 反射失败, 静默
        }
    }
}