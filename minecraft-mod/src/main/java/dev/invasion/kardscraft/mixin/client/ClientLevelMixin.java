package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.game.BoardManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stop client prediction as well as server simulation for frozen board entities. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
    private void kardscraft$freezeEntity(Entity entity,CallbackInfo ci) {
        if (BoardManager.shouldFreezeEntity(entity)) { entity.setOldPosAndRot(); ci.cancel(); }
    }
    @Inject(method="tickPassenger",at=@At("HEAD"),cancellable=true)
    private void kardscraft$freezePassenger(Entity vehicle,Entity passenger,CallbackInfo ci) {
        if (BoardManager.shouldFreezeEntity(passenger)) { passenger.setOldPosAndRot(); ci.cancel(); }
    }
}
