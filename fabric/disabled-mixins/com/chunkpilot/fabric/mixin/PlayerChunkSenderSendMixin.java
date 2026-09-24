package com.chunkpilot.fabric.mixin;

import com.chunkpilot.fabric.util.PlayerChunkSendHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 PlayerChunkSender.sendNextChunks(ServerPlayer).
 *
 * sendNextChunks 内部调用 collectChunksToSend, 因此在这里把"当前正在收区块的玩家"
 * 写入 ThreadLocal, 供 ChunkDataSenderMixin 读取, 避免用反射偷 speedTracker 里
 * 第一个玩家(多人时排序会用到错误玩家). collectChunksToSend 返回后清除.
 *
 * 如果 sendNextChunks 签名在当前 MC 版本不匹配, mixin 会被跳过(required=false),
 * ThreadLocal 恒为 null → ChunkDataSenderMixin 回退到 vanilla 排序, 安全.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderSendMixin {

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void chunkpilot$beforeSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        PlayerChunkSendHolder.currentPlayer.set(player);
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void chunkpilot$afterSendNextChunks(ServerPlayer player, CallbackInfo ci) {
        PlayerChunkSendHolder.currentPlayer.remove();
    }
}
