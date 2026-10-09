package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The dark screen-edge vignette would tint the see-through magenta, and the crosshair means nothing in the table view. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void kardscraft$noVignette(CallbackInfo ci) {
        if (ViewController.overlayActive()) ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void kardscraft$noCrosshair(CallbackInfo ci) {
        if (ViewController.cardViewActive()) ci.cancel();
    }
}
