package dev.invasion.kardscraft.mixin;

import dev.invasion.kardscraft.game.BoardManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    // doTick is also driven by the connection; cancelling the world entity tick alone is insufficient.
    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void kardscraft$freezePlayer(CallbackInfo ci) {
        if (BoardManager.shouldFreezeEntity((ServerPlayer) (Object) this)) ci.cancel();
    }
}
