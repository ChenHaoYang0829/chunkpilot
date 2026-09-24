package com.chunkpilot.neoforge.mixin;
import com.chunkpilot.neoforge.util.PlayerChunkSendHolder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 PlayerChunkSender.sendNextChunks(ServerPlayer)
 *
 * 在调用前将 player 存入 ThreadLocal, 供 PlayerChunkSenderMixin 读取.
 * 在调用后清除 ThreadLocal.
 *
 * sendNextChunks 内部调用 collectChunksToSend, 所以 ThreadLocal 在
 * collectChunksToSend 执行期间是有效的.
 */
// 2026-09-25 (二阶段整改项 1, 按主代理硬要求): 本模块的 mixin 注入一律 `require = 0, expect = 0`。
//   理由: 这些类用的是**硬编码 descriptor / 可读方法名**, 版本一漂移就会在**启动期**报
//   InvalidInjectionException → MixinApplyError → ModLoadingException (1.21.11 的
//   ConnectionNetTrackerMixin 就是这么崩的)。软失败只会丢一项增强, 不会让服务端起不来。
//   功能是否真的生效由**服务端探针 + 飞行跑分**验收, 不靠"启动成功"。
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderSendMixin {

    @Inject(
        method = "sendNextChunks",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void chunkpilot$beforeSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        PlayerChunkSendHolder.currentPlayer.set(player);
    }

    @Inject(
        method = "sendNextChunks",
        at = @At("RETURN"),
        require = 0, expect = 0
    )
    private void chunkpilot$afterSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        PlayerChunkSendHolder.currentPlayer.remove();
    }
}