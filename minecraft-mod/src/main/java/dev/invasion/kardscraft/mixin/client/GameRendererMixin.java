package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.Overlay;
import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method="getFov",at=@At("RETURN"),cancellable=true)
    private void kardscraft$tableFov(Camera camera,float partialTick,boolean useFovSetting,CallbackInfoReturnable<Double> cir) {
        if (useFovSetting && ViewController.cardViewActive()) cir.setReturnValue(ViewController.verticalFov());
        // Fixed FOV in table first person (no sprint/speed FOV changes), so it matches what KARDS was asked to use.
        else if (useFovSetting && ViewController.tableWalkActive()) cir.setReturnValue(ViewController.walkFov());
    }
    @Inject(method="getProjectionMatrix",at=@At("RETURN"))
    private void kardscraft$aspect(double fov,CallbackInfoReturnable<Matrix4f> cir) {
        if (ViewController.overlayActive()) {
            Matrix4f matrix=cir.getReturnValue();
            matrix.m00((float)(matrix.m11()/ViewController.aspect()));
        }
    }
    @Inject(method="pick(F)V",at=@At("TAIL"))
    private void kardscraft$cursorPick(float partialTick,CallbackInfo ci) { ViewController.refreshPick(Minecraft.getInstance()); }
    @Inject(method="render",at=@At("HEAD"))
    private void kardscraft$frameStart(CallbackInfo ci) { ViewController.frameStart(Minecraft.getInstance()); }
    /** No block selection box over the real KARDS table while KARDS has the input. */
    @Inject(method="shouldRenderBlockOutline",at=@At("RETURN"),cancellable=true)
    private void kardscraft$noOutlineForKards(CallbackInfoReturnable<Boolean> cir) {
        if (ViewController.kardsControlOverlay()) cir.setReturnValue(false);
    }
    @Inject(method="render",at=@At("TAIL"))
    private void kardscraft$present(CallbackInfo ci) { Overlay.present(); }
}
