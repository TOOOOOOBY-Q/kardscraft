package dev.invasion.kardscraft.mixin;

import dev.invasion.kardscraft.game.BoardManager;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void kardscraft$freezeMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (player.server.isSameThread() && BoardManager.isInteractionBlocked(player)) {
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            ci.cancel();
        }
    }

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void kardscraft$freezeVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (player.server.isSameThread() && BoardManager.isInteractionBlocked(player)) ci.cancel();
    }
}
