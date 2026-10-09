package dev.invasion.kardscraft.mixin;

import dev.invasion.kardscraft.game.BoardManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips simulation, preserving projectile velocity, mob AI timers and creeper fuse progress. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void kardscraft$freezeEntity(Entity entity, CallbackInfo ci) {
        if (BoardManager.shouldFreezeEntity(entity)) ci.cancel();
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void kardscraft$freezePassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (BoardManager.shouldFreezeEntity(passenger)) ci.cancel();
    }
}
