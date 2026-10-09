package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.ViewController;
import dev.invasion.kardscraft.game.Coords;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No sky, sun, moon or stars while the overlay is on, so the background stays pure magenta. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void kardscraft$noSky(CallbackInfo ci) {
        if (ViewController.overlayActive()) ci.cancel();
    }

    /** Keep the user's board section while hiding the superflat terrain below the transparent floor. */
    @Redirect(method = "renderSectionLayer", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getCompiled()Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;"))
    private SectionRenderDispatcher.CompiledSection kardscraft$hideTerrainBelowBoard(
            SectionRenderDispatcher.RenderSection section) {
        if (ViewController.overlayActive() && section.getBoundingBox().maxY <= Coords.floorTop())
            return SectionRenderDispatcher.CompiledSection.EMPTY;
        return section.getCompiled();
    }
}
