package com.chunkpilot.fabric.client;

import com.chunkpilot.client.ChunkPilotClient;
import com.chunkpilot.config.ClientRenderConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Fabric 客户端入口 (ClientModInitializer)
 *
 * v0.4.0: 独立于服务端入口, 不引用任何服务端类.
 * 客户端只做渲染优化, 使用默认配置 (服务端会通过 CapabilityPacket 发送覆盖).
 */
public class ChunkPilotFabricClient implements ClientModInitializer {

    private static long lastTickTime = 0;

    @Override
    public void onInitializeClient() {
        System.out.println("[ChunkPilot] Client initializing...");

        // 用默认配置初始化客户端 (enabled=false, 等服务端发 Capability 后才激活)
        ClientRenderConfig defaultConfig = new ClientRenderConfig();
        ChunkPilotClient client = new ChunkPilotClient(defaultConfig);

        // 检测 Sodium
        boolean sodiumLoaded = false;
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            sodiumLoaded = true;
        } catch (ClassNotFoundException e) {
            // Sodium 不存在
        }
        client.setSodiumDetected(sodiumLoaded);

        if (sodiumLoaded) {
            System.out.println("[ChunkPilot] Sodium detected, using Sodium-compatible render path");
        } else {
            System.out.println("[ChunkPilot] Sodium not detected, using vanilla render path");
        }

        // 注册客户端 tick
        ClientTickEvents.END_CLIENT_TICK.register(mcClient -> {
            onClientTick();
        });

        System.out.println("[ChunkPilot] Client initialized");
    }

    private static void onClientTick() {
        ChunkPilotClient client = ChunkPilotClient.getInstance();
        if (client == null || !client.isEnabled()) return;

        try {
            // 反射获取 Minecraft.getInstance().player
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object player = mcClass.getMethod("player").invoke(mc);
            if (player == null) return;

            // 获取玩家位置
            Object blockPos = player.getClass().getMethod("blockPosition").invoke(player);
            int blockX = (int) blockPos.getClass().getMethod("getX").invoke(blockPos);
            int blockZ = (int) blockPos.getClass().getMethod("getZ").invoke(blockPos);
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;

            // 获取玩家速度向量
            Object deltaMove = player.getClass().getMethod("getDeltaMovement").invoke(player);
            double dx = (double) deltaMove.getClass().getField("x").get(deltaMove);
            double dz = (double) deltaMove.getClass().getField("z").get(deltaMove);
            double speedBpt = Math.sqrt(dx * dx + dz * dz);
            double direction = 0;
            if (dx != 0 || dz != 0) {
                direction = Math.atan2(dz, dx);
            }

            client.updatePlayerState(chunkX, chunkZ, speedBpt, direction);

            // 记录帧时间
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