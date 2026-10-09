package dev.invasion.kardscraft.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the overlay is on: background is pure magenta (made see-through by Windows) and there is no fog. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Shadow
    private static float fogRed;
    @Shadow
    private static float fogGreen;
    @Shadow
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void kardscraft$magenta(CallbackInfo ci) {
        if (!ViewController.overlayActive()) return;
        fogRed = 1.0F;
        fogGreen = 0.0F;
        fogBlue = 1.0F;
        RenderSystem.clearColor(1.0F, 0.0F, 1.0F, 0.0F);
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void kardscraft$noFog(CallbackInfo ci) {
        if (!ViewController.overlayActive()) return;
        RenderSystem.setShaderFogStart(1.0E7F);
        RenderSystem.setShaderFogEnd(1.0E7F + 1.0F);
    }
}
