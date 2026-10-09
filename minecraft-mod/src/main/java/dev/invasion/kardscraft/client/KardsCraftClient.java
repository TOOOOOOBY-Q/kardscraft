package dev.invasion.kardscraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.invasion.kardscraft.KardsCraft;
import dev.invasion.kardscraft.game.CardEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class KardsCraftClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        KardsCraft.CLIENT_DIAGNOSTICS="客户端入口已加载；等待首个 tick";
        KardsCraft.LOG.info("KardsCraft client entrypoint initialized; os={}",System.getProperty("os.name"));
        ClientConfig.load();
        EntityRendererRegistry.register(KardsCraft.CARD, CardProxyRenderer::new);
        ClientTickEvents.START_CLIENT_TICK.register(ViewController::clientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(ViewController::shutdown);
        HudRenderCallback.EVENT.register((graphics, delta) -> ViewController.drawStatus(graphics));
    }

    /** Invisible on the original KARDS board; an explicit labelled proxy in reconstructed view. */
    private static final class CardProxyRenderer extends EntityRenderer<CardEntity> {
        private final Font font;
        CardProxyRenderer(EntityRendererProvider.Context context) { super(context); font=context.getFont(); }
        @Override public ResourceLocation getTextureLocation(CardEntity entity) {
            return ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
        }
        @Override public void render(CardEntity entity,float yaw,float partialTick,PoseStack poses,MultiBufferSource buffers,int light) {
            boolean outlineOnBoard=ViewController.overlayActive() && ClientConfig.debugCardOutlines;
            if (!ViewController.reconstructedView() && !outlineOnBoard) return;
            float r=entity.getSide()==1 ? 0.3f : 1f, b=entity.getSide()==1 ? 1f : 0.3f;
            double width=entity.getBbWidth()/2.0, height=entity.getBbHeight();
            if (outlineOnBoard) {
                // Alignment check on the real KARDS board: a flat footprint that should sit on the card itself.
                LevelRenderer.renderLineBox(poses,buffers.getBuffer(RenderType.lines()),-width,0.02,-width,width,0.05,width,r,0.9f,b,1f);
                return;
            }
            // First-person board: a solid, team-coloured card plate on the board plus its outline and label.
            LevelRenderer.addChainedFilledBoxVertices(poses,buffers.getBuffer(RenderType.debugFilledBox()),
                    -width,0.01,-width,width,0.12,width,r*0.8f,0.55f,b*0.8f,0.75f);
            LevelRenderer.renderLineBox(poses,buffers.getBuffer(RenderType.lines()),-width,0,-width,width,height,width,r,0.8f,b,1f);
            String name=entity.getCardName();
            if (name==null || name.isBlank()) name="卡牌 #"+entity.getCardId();
            if (name.length()>48) name=name.substring(0,48);
            Component label=Component.literal((entity.isHeadquarters() ? "总部 · " : "")+name+"  "+entity.getAttack()+" / "+entity.getDefense());
            poses.pushPose();
            poses.translate(0,height+0.35,0);
            poses.mulPose(this.entityRenderDispatcher.cameraOrientation());
            poses.scale(-0.025f,-0.025f,0.025f);
            font.drawInBatch(label,-font.width(label)/2f,0,0xffffffff,false,poses.last().pose(),buffers,Font.DisplayMode.NORMAL,0x90000000,light);
            poses.popPose();
        }
    }
}
