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
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderSendMixin {

    @Inject(
        method = "sendNextChunks",
        at = @At("HEAD")
    )
    private void chunkpilot$beforeSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        PlayerChunkSendHolder.currentPlayer.set(player);
    }

    @Inject(
        method = "sendNextChunks",
        at = @At("RETURN")
    )
    private void chunkpilot$afterSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        PlayerChunkSendHolder.currentPlayer.remove();
    }
}