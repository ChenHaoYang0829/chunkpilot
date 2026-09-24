package com.chunkpilot.forge.client;

import com.chunkpilot.ChunkPilot;
import com.chunkpilot.client.ChunkPilotClient;
import com.chunkpilot.config.ChunkPilotConfig;

/**
 * ChunkPilot Forge 客户端入口。
 *
 * 与 {@code ChunkPilotNeoForgeClient} 同构: **不使用任何客户端专用类** (Minecraft / Level /
 * ChunkRenderDispatcher 全部走反射), 所以这个类在专用服务端上被加载也不会 NoClassDefFoundError。
 * 由 {@link ChunkPilotForgeClientEvents} 在客户端 tick 时驱动。
 *
 * 注意 (与 neoforge 模块的差异): main 的 neoforge 模块里 ChunkPilotNeoForgeClient **从未被调用**
 * (入口类里没有任何一处引用它) —— 那是既有缺陷。这里补上了真实接线:
 * {@code ChunkPilotForge} 在 Dist.CLIENT 时注册 ClientTickEvent 监听器 → onClientTick()。
 */
public class ChunkPilotForgeClient {

    private static long lastTickTime = 0;
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;

        ChunkPilotConfig config = ChunkPilot.getInstance() != null
            ? ChunkPilot.getInstance().getConfig() : null;
        ChunkPilotClient.init(config);

        boolean sodiumLoaded = false;
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            sodiumLoaded = true;
        } catch (Throwable ignored) {
        }
        ChunkPilotClient.getInstance().setSodiumDetected(sodiumLoaded);

        if (sodiumLoaded) {
            System.out.println("[ChunkPilot] Sodium detected, using Sodium-compatible render path");
        } else {
            System.out.println("[ChunkPilot] Sodium not detected, using vanilla render path");
        }
    }

    /** 每客户端 tick 调用。用反射取 Minecraft / 玩家位置, 避免编译期依赖客户端类。 */
    public static void onClientTick() {
        init();

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

            Object deltaMove = player.getClass().getMethod("getDeltaMovement").invoke(player);
            double dx = (double) deltaMove.getClass().getField("x").get(deltaMove);
            double dz = (double) deltaMove.getClass().getField("z").get(deltaMove);
            double speedBpt = Math.sqrt(dx * dx + dz * dz);
            double direction = 0;
            if (dx != 0 || dz != 0) {
                direction = Math.atan2(dz, dx);
            }

            client.updatePlayerState(blockX >> 4, blockZ >> 4, speedBpt, direction);

            long now = System.nanoTime();
            if (lastTickTime > 0) {
                client.onFrameEnd(now - lastTickTime);
            }
            lastTickTime = now;
        } catch (Throwable t) {
            // 反射失败 → 静默 (客户端渲染路径不能因为 CP 崩)
        }
    }
}
