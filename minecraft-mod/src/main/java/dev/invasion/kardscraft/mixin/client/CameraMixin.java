package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the table view, draw from the KARDS camera (including its roll) instead of Steve's eyes. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void kardscraft$tableCamera(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        if (!ViewController.cardViewActive()) return;
        ViewController.frame();
        this.setRotation(ViewController.camYaw(), ViewController.camPitch());
        double roll = ViewController.camRoll();
        if (roll != 0) {
            // GameRenderer builds the view from rotation(); rotate it about the view axis (right-hand rule),
            // and keep the camera's up/left vectors in step. Picking uses the same angle (TableProjection).
            Camera self = (Camera) (Object) this;
            Vector3f forward = self.getLookVector();
            Quaternionf about = new Quaternionf().fromAxisAngleRad(forward.x, forward.y, forward.z, (float) roll);
            self.rotation().premul(about);
            about.transform(self.getUpVector());
            about.transform(self.getLeftVector());
        }
        this.setPosition(ViewController.camX(), ViewController.camY(), ViewController.camZ());
        this.detached = true;
    }
}
