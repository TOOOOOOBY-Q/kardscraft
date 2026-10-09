package dev.invasion.kardscraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.invasion.kardscraft.Config;
import dev.invasion.kardscraft.KardsCraft;
import dev.invasion.kardscraft.bridge.Bridge;
import dev.invasion.kardscraft.bridge.BridgeState;
import dev.invasion.kardscraft.game.BoardBlock;
import dev.invasion.kardscraft.game.BoardManager;
import dev.invasion.kardscraft.game.Coords;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import java.util.HashMap;
import java.util.Map;

/** Table input and an explicitly separate reconstructed Minecraft first-person view. */
public final class ViewController {
    private static boolean active, cardView=true, steveControl, myTurn, canAct, camValid;
    /** Why Steve is paused although it is our turn (KARDS busy, not ready, geometry missing); empty if he can act. */
    private static String pauseReason="";
    /**
     * First person requested (F10 / Esc). With KARDS following the camera this is "table walk": the overlay stays on,
     * KARDS renders its real table from Steve's eyes and Minecraft draws Steve's world on top. Without KARDS support
     * it falls back to Minecraft's own window over KARDS (cardView=false).
     */
    private static boolean walk, tableWalk, kardsFollowing, cursorHidden, lookPrimed;
    private static long walkStartMs;
    private static final long WALK_CONFIRM_GRACE_MS=1500;
    private static boolean controlDown, viewDown, inputsOwned, nativeScreen, overlayFallback, blockLeft, blockRight;
    private static boolean optionsTouched, savedBob, savedPause, savedHide;
    private static CloudStatus savedClouds;
    private static CameraType savedCamera;
    private static double camX,camY,camZ,camHFov=60;
    private static float camYaw,camPitch;
    /** Camera roll in radians about the view axis (see TableProjection.rollAngle). */
    private static double camRoll;
    private static boolean firstTick, checkedCamera;
    private static String lastDiagnostic="", inactiveReason="等待客户端 tick";
    private static String lastGameplayDiagnostic="尚未获取游戏画面状态";
    private static final Map<Integer,Boolean> down = new HashMap<>();
    private ViewController() {}
    /** Top-down table view: Minecraft renders from the KARDS table camera. */
    public static boolean cardViewActive() { return active && cardView && camValid && !tableWalk; }
    /** First person on the real KARDS table (KARDS camera follows Steve's eyes). */
    public static boolean tableWalkActive() { return active && tableWalk; }
    public static boolean keepCursorFree() { return cardViewActive() || tableWalkActive(); }
    public static boolean steveFromTable() { return cardViewActive() && steveControl && myTurn; }
    /** Overlay-polled input drives Minecraft key bindings although its own window has no mouse grab. */
    public static boolean tableInput() { return steveFromTable() || (tableWalkActive() && myTurn); }
    /** Minecraft vertical FOV used in first person; requested from KARDS as the matching horizontal FOV. */
    public static double walkFov() { return Math.max(30,Math.min(110,Minecraft.getInstance().options.fov().get())); }
    /** Same gate as the server (BoardManager.isInteractionBlocked), so client prediction never runs ahead of it. */
    public static boolean interactionBlocked() { return active && (!steveControl || !canAct); }
    /** First-person board view (F10): Minecraft's own window over KARDS, board blocks drawn. */
    public static boolean firstPersonActive() { return active && walk; }
    /** Esc inside Minecraft's own first-person window leaves first person instead of opening the pause menu. */
    public static boolean escapeFirstPerson() {
        if (!active || !walk) return false;
        setWalk(false,"Esc"); return true;
    }
    private static void setWalk(boolean on,String why) {
        if (walk==on) return;
        walk=on; lookPrimed=false;
        if (on) walkStartMs=System.currentTimeMillis();
        else { tableWalk=false; KardsCameraLink.release(why); }
        KardsCraft.LOG.info("View switch ({}): {}",why,on ? "first person" : "table overlay");
    }
    public static boolean overlayActive() { return (cardViewActive() || tableWalkActive()) && Overlay.isApplied(); }
    /** The overlay is shown and KARDS (not Steve) receives the input. */
    public static boolean kardsControlOverlay() { return overlayActive() && !steveControl; }
    public static boolean reconstructedView() { return active && !overlayActive(); }
    public static double camX() { return camX; }
    public static double camY() { return camY; }
    public static double camZ() { return camZ; }
    public static float camYaw() { return camYaw; }
    public static float camPitch() { return camPitch; }
    public static double camRoll() { return camRoll; }
    public static double aspect() {
        Minecraft mc=Minecraft.getInstance();
        return Overlay.isApplied() ? Overlay.aspect() : (double)mc.getWindow().getWidth()/Math.max(1,mc.getWindow().getHeight());
    }
    public static double verticalFov() { return Coords.toVerticalFov(camHFov,aspect()); }
    /** Retained for CameraMixin; snapshots are applied directly, avoiding an extra camera lag. */
    public static void frame() {}

    public static void clientTick(Minecraft mc) {
        if (!firstTick) { firstTick=true; KardsCraft.LOG.info("KardsCraft client tick callback is running"); }
        BridgeState st=Bridge.getActive();
        Coords co=Coords.from(st);
        boolean enabled=st!=null && co!=null && st.cam!=null && mc.player!=null && mc.level!=null
                && BoardManager.isManagedLevel(mc.level);
        boolean relevant=mc.isWindowActive() || Overlay.relevantForeground();
        boolean control=hotkey(mc,ClientConfig.controlKey), view=hotkey(mc,ClientConfig.viewKey);
        if (enabled!=active) {
            releaseInputs();
            active=enabled;
            cardView=true; steveControl=false; overlayFallback=false;
            if (active) saveOptions(mc); else { camValid=false; restoreOptions(mc); }
        }
        if (!active) {
            controlDown=control; viewDown=view;
            if (walk) setWalk(false,"inactive");
            KardsCameraLink.release("inactive");
            showCursor();
            Overlay.firstPerson(false);
            Overlay.update(false,true);
            syncBoardVisibility(mc);
            inactiveReason=mc.level==null ? "尚未进入世界" : mc.player==null ? "玩家尚未加载"
                    : !BoardManager.isEnabled() ? "此世界未启用棋盘"
                    : !BoardManager.isManagedLevel(mc.level) ? "当前维度不属于棋盘"
                    : st==null ? "桥接快照尚不可用（检查连接、训练模式或快照时效）"
                    : co==null ? "总部坐标无效，无法计算画面比例"
                    : st.cam==null ? "KARDS 镜头数据缺失" : "客户端激活条件不满足";
            publishDiagnostic(mc,st,co,"未激活："+inactiveReason);
            return;
        }
        myTurn=st.myTurn;
        canAct=st.myTurn && st.ready && !st.busy && st.geometryReady;
        pauseReason=!st.myTurn ? "对手回合" : !st.ready ? "KARDS 尚未就绪" : st.busy ? "KARDS 正在结算"
                : !st.geometryReady ? "棋盘位置尚未就绪" : "";
        updateCamera(st,co);
        if (relevant && mc.screen==null && myTurn) {
            if (control && !controlDown) { releaseInputs(); steveControl=!steveControl; if (!steveControl) setWalk(false,"F9"); }
            if (view && !viewDown) { releaseInputs(); setWalk(!walk,"F10"); steveControl=true; }
        }
        controlDown=control; viewDown=view;
        if (!myTurn) { if (steveControl) releaseInputs(); steveControl=false; setWalk(false,"opponent turn"); }
        boolean following=kardsFollowing=KardsCameraLink.observe(st);
        boolean wasTableWalk=tableWalk;
        tableWalk=walk && camValid && !overlayFallback
                && (following || System.currentTimeMillis()-walkStartMs<WALK_CONFIRM_GRACE_MS);
        if (wasTableWalk!=tableWalk && walk) {
            releaseInputs(); lookPrimed=false;
            KardsCraft.LOG.info("First person: {}",tableWalk ? "on the KARDS table (KARDS camera follows)"
                    : "Minecraft window fallback (KARDS camera is not following)");
        }
        cardView=!walk || tableWalk;
        // Once per failure: Overlay clears its failure flag as soon as the overlay is no longer requested.
        if (Overlay.failed()) {
            overlayFallback=true; cardView=false; steveControl=myTurn; Overlay.focusMinecraft();
        }
        boolean wantsOverlay=Config.overlay && cardView && camValid && mc.screen==null && !mc.getWindow().isFullscreen();
        // Before update(): in first person the overlay must not move the Minecraft window back.
        Overlay.firstPerson(!cardView);
        Overlay.update(wantsOverlay,!steveControl);
        syncBoardVisibility(mc);
        CameraType type=cardView ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
        mc.options.setCameraType(type);
        mc.options.bobView().set(cardView ? false : savedBob);
        if (!tableWalkActive() || !Overlay.ownsInput()) showCursor();
        mc.options.hideGui=overlayActive() && !steveControl;
        if (keepCursorFree() && mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        if (!cardView && mc.screen==null && mc.isWindowActive() && !mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.grabMouse();

        boolean owns=tableInput() && Overlay.ownsInput() && mc.screen==null;
        if (!owns && inputsOwned) releaseInputs();
        if (owns) {
            if (!inputsOwned) seedHeldInputs();
            inputsOwned=true; pollOverlayInput(mc);
        }
        if (steveFromTable() && mc.screen==null) refreshPick(mc);
        if (nativeScreen && mc.screen==null) { nativeScreen=false; Overlay.update(wantsOverlay,!steveControl); }
        String reason=!Config.overlay ? "配置关闭叠加" : !cardView ? "第一人称（MC 窗口；KARDS 镜头未跟随）"
                : tableWalk ? "牌桌第一人称（KARDS 镜头跟随史蒂夫）"
                : !camValid ? "镜头参数不支持（FOV、姿态或数值无效）"
                : mc.screen!=null ? "MC 界面打开："+mc.screen.getClass().getSimpleName()
                : mc.getWindow().isFullscreen() ? "MC 全屏模式阻止叠加（需要窗口模式）"
                : "已请求牌桌叠加";
        publishDiagnostic(mc,st,co,reason);
    }
    private static void publishDiagnostic(Minecraft mc,BridgeState st,Coords co,String reason) {
        String message=reason+"；客户端激活="+active+"；棋盘="+BoardManager.isEnabled()
                +"；桥接="+(st!=null)+"；坐标="+(co!=null)+"；镜头="+(st!=null && st.cam!=null)
                +"；维度="+(mc.level==null ? "无" : mc.level.dimension().location())
                +"；窗口="+Overlay.status();
        String brief=reason+"；"+Overlay.status();
        if (mc.level!=null && mc.player!=null && mc.screen==null) lastGameplayDiagnostic=brief;
        KardsCraft.CLIENT_DIAGNOSTICS=mc.screen==null ? brief
                : "最近游戏画面："+lastGameplayDiagnostic+"；当前界面："+mc.screen.getClass().getSimpleName();
        if (!message.equals(lastDiagnostic)) { lastDiagnostic=message; KardsCraft.LOG.info("Client presentation: {}",message); }
    }
    private static boolean hotkey(Minecraft mc,int vk) {
        if (Win32.api()!=null) return Win32.isKeyDown(vk);
        int glfw=vk>=0x70 && vk<=0x87 ? GLFW.GLFW_KEY_F1+vk-0x70 : vk;
        return mc.isWindowActive() && InputConstants.isKeyDown(mc.getWindow().getWindow(),glfw);
    }
    private static void saveOptions(Minecraft mc) {
        savedPause=mc.options.pauseOnLostFocus; savedBob=mc.options.bobView().get();
        savedClouds=mc.options.cloudStatus().get(); savedHide=mc.options.hideGui; savedCamera=mc.options.getCameraType();
        optionsTouched=true;
        mc.options.pauseOnLostFocus=false; mc.options.cloudStatus().set(CloudStatus.OFF);
    }
    private static void restoreOptions(Minecraft mc) {
        if (!optionsTouched) return;
        mc.options.pauseOnLostFocus=savedPause; mc.options.bobView().set(savedBob);
        mc.options.cloudStatus().set(savedClouds); mc.options.hideGui=savedHide; mc.options.setCameraType(savedCamera);
        optionsTouched=false;
    }
    public static void shutdown(Minecraft mc) {
        KardsCameraLink.release("shutdown"); showCursor();
        releaseInputs(); active=false; camValid=false; Overlay.close(); restoreOptions(mc);
    }
    private static void updateCamera(BridgeState st,Coords co) {
        var c=st.cam;
        boolean previousValid=camValid;
        Vec3 p=co.toMc(c.x,c.y,c.z);
        float[] rotation=Coords.toMcRotation(c.pitch,c.yaw);
        // Rolled cameras (KARDS camera shake) are drawn and picked with the same roll instead of being rejected.
        double roll=Double.isFinite(c.roll) && Float.isFinite(rotation[0]) && Float.isFinite(rotation[1])
                ? TableProjection.rollAngle(rotation[0],rotation[1],Coords.toMcUp(c.pitch,c.yaw,c.roll)) : Double.NaN;
        camValid=Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z)
                && Double.isFinite(c.fov) && c.fov>1 && c.fov<179 && Double.isFinite(roll)
                && Float.isFinite(rotation[0]) && Float.isFinite(rotation[1]);
        if (!checkedCamera || previousValid!=camValid) {
            checkedCamera=true;
            KardsCraft.LOG.info("Client camera valid={}; position=({},{},{}), pitch={}, yaw={}, roll={} (Minecraft roll {} rad), horizontalFov={}",camValid,c.x,c.y,c.z,c.pitch,c.yaw,c.roll,roll,c.fov);
        }
        if (!camValid) return;
        camX=p.x; camY=p.y; camZ=p.z; camYaw=rotation[0]; camPitch=rotation[1]; camRoll=roll; camHFov=c.fov;
    }
    private static void pollOverlayInput(Minecraft mc) {
        boolean cursorInside=Overlay.cursor()!=null;
        hold(mc.options.keyUp,'W'); hold(mc.options.keyDown,'S'); hold(mc.options.keyLeft,'A'); hold(mc.options.keyRight,'D');
        hold(mc.options.keyJump,0x20); hold(mc.options.keyShift,0x10); hold(mc.options.keySprint,0x11);
        mouse(mc.options.keyAttack,0x01,0,cursorInside);
        mouse(mc.options.keyUse,0x02,1,cursorInside);
        for (int i=0;i<9;i++) if (edge('1'+i)) mc.player.getInventory().selected=i;
        if (edge('E')) openNativeScreen(mc,new InventoryScreen(mc.player));
        else if (edge('T')) openNativeScreen(mc,new ChatScreen(""));
        else if (edge(0xBF)) openNativeScreen(mc,new ChatScreen("/"));
        else if (edge(0x1B)) { releaseInputs(); setWalk(!walk,"Esc"); }
    }
    /**
     * Every frame, before the camera is set up: first-person mouse look from the desktop cursor (the overlay window,
     * not Minecraft's, has the input), and the camera request so KARDS renders from Steve's eyes.
     */
    public static void frameStart(Minecraft mc) {
        if (!active || !walk || mc.player==null) return;
        float partial=mc.getTimer().getGameTimeDeltaPartialTick(true);
        if (tableWalk && Overlay.ownsInput() && mc.screen==null) mouseLook(mc); else { lookPrimed=false; showCursor(); }
        BridgeState st=Bridge.getActive();
        Coords co=Coords.from(st);
        if (st!=null && co!=null) KardsCameraLink.request(st,co,mc.player.getEyePosition(partial),
                mc.player.getViewYRot(partial),mc.player.getViewXRot(partial),Coords.toHorizontalFov(walkFov(),aspect()));
    }
    private static void mouseLook(Minecraft mc) {
        Win32.User32 u=Win32.api();
        int[] r=Overlay.screenRect();
        if (u==null || r==null) return;
        int cx=r[0]+r[2]/2, cy=r[1]+r[3]/2;
        int[] p=new int[2];
        if (!u.GetCursorPos(p)) return;
        u.SetCursorPos(cx,cy);
        if (!cursorHidden) { u.ShowCursor(false); cursorHidden=true; }
        if (!lookPrimed) { lookPrimed=true; return; }
        double s=mc.options.sensitivity().get()*0.6+0.2, k=s*s*s*8.0;
        int invert=mc.options.invertYMouse().get() ? -1 : 1;
        int dx=p[0]-cx, dy=p[1]-cy;
        if (dx!=0 || dy!=0) mc.player.turn(dx*k,dy*k*invert);
    }
    private static void showCursor() {
        if (!cursorHidden) return;
        cursorHidden=false;
        Win32.User32 u=Win32.api();
        if (u!=null) u.ShowCursor(true);
    }
    /**
     * The board layer is drawn only in the first-person view; over the real KARDS table it is hidden (craters still
     * show as the dark base below). Re-mesh the board sections when that changes.
     */
    private static void syncBoardVisibility(Minecraft mc) {
        boolean hide=cardViewActive() || tableWalkActive();
        if (hide==BoardBlock.hidden) return;
        BoardBlock.hidden=hide;
        if (mc.levelRenderer==null || mc.level==null) return;
        int floor=BoardManager.floorY();
        int min=Math.floorDiv(-BoardManager.FLOOR_HALF,16), max=Math.floorDiv(BoardManager.FLOOR_HALF,16);
        for (int sx=min;sx<=max;sx++) for (int sz=min;sz<=max;sz++) {
            mc.levelRenderer.setSectionDirty(sx,Math.floorDiv(floor,16),sz);
            if (Math.floorDiv(floor-1,16)!=Math.floorDiv(floor,16)) mc.levelRenderer.setSectionDirty(sx,Math.floorDiv(floor-1,16),sz);
        }
    }
    private static void openNativeScreen(Minecraft mc,net.minecraft.client.gui.screens.Screen screen) {
        releaseInputs(); nativeScreen=true; Overlay.update(false,true); Overlay.focusMinecraft(); mc.setScreen(screen);
    }
    private static void hold(KeyMapping key,int vk) { key.setDown(Win32.isKeyDown(vk)); }
    private static void seedHeldInputs() {
        blockLeft=Win32.isKeyDown(1); blockRight=Win32.isKeyDown(2);
        for (int vk:new int[]{1,2,'E','T',0xBF,0x1B,'1','2','3','4','5','6','7','8','9'}) down.put(vk,Win32.isKeyDown(vk));
    }
    private static void mouse(KeyMapping key,int vk,int button,boolean allowed) {
        boolean pressed=Win32.isKeyDown(vk);
        boolean prior=down.getOrDefault(vk,false); down.put(vk,pressed);
        if (button==0) { if (!pressed) blockLeft=false; allowed&=!blockLeft; }
        else { if (!pressed) blockRight=false; allowed&=!blockRight; }
        key.setDown(allowed && pressed);
        if (allowed && pressed && !prior) KeyMapping.click(InputConstants.getKey(key.saveString()));
    }
    private static boolean edge(int vk) {
        boolean pressed=Win32.isKeyDown(vk), prior=down.getOrDefault(vk,false);
        down.put(vk,pressed); return pressed && !prior;
    }
    public static void releaseInputs() {
        KeyMapping.releaseAll(); down.clear(); inputsOwned=false;
        Minecraft mc=Minecraft.getInstance();
        if (mc.player!=null) { mc.player.input.leftImpulse=0; mc.player.input.forwardImpulse=0; mc.player.input.jumping=false; mc.player.input.shiftKeyDown=false; }
    }

    /** Ray uses the actual desktop cursor for the independent overlay window. */
    public static Vec3[] cursorRay(Minecraft mc) {
        double[] uv=Overlay.isApplied() ? Overlay.cursor() : new double[]{
                mc.mouseHandler.xpos()/Math.max(1,mc.getWindow().getScreenWidth()),
                mc.mouseHandler.ypos()/Math.max(1,mc.getWindow().getScreenHeight())};
        if (uv==null || uv[0]<0 || uv[0]>1 || uv[1]<0 || uv[1]>1) return null;
        double[] d=TableProjection.direction(camYaw,camPitch,camRoll,verticalFov(),aspect(),uv[0],uv[1]);
        return new Vec3[]{new Vec3(camX,camY,camZ),new Vec3(d[0],d[1],d[2])};
    }
    /** Replace the centre-screen vanilla pick, retaining reach and line-of-sight from Steve. */
    public static void refreshPick(Minecraft mc) {
        if (!steveFromTable() || mc.player==null || mc.level==null || mc.screen!=null) return;
        Vec3[] ray=cursorRay(mc);
        Vec3 eye=mc.player.getEyePosition();
        mc.crosshairPickEntity=null;
        if (ray==null) { mc.hitResult=miss(eye); return; }
        Vec3 from=ray[0], to=from.add(ray[1].scale(256));
        HitResult best=mc.level.clip(new ClipContext(from,to,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
        double distance=best.getType()==HitResult.Type.MISS ? Double.MAX_VALUE : from.distanceToSqr(best.getLocation());
        for (Entity entity:mc.level.getEntities(mc.player,new AABB(from,to).inflate(1),e -> !e.isSpectator() && e.isPickable())) {
            var intersection=entity.getBoundingBox().inflate(entity.getPickRadius()).clip(from,to);
            if (intersection.isPresent() && from.distanceToSqr(intersection.get())<distance) {
                distance=from.distanceToSqr(intersection.get()); best=new EntityHitResult(entity,intersection.get());
            }
        }
        Vec3 target=best.getLocation();
        double reach=best instanceof EntityHitResult ? mc.player.entityInteractionRange() : mc.player.blockInteractionRange();
        BlockHitResult obstruction=mc.level.clip(new ClipContext(eye,target,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        boolean blocked=obstruction.getType()!=HitResult.Type.MISS && eye.distanceToSqr(obstruction.getLocation())+0.01<eye.distanceToSqr(target);
        if (best.getType()==HitResult.Type.MISS || eye.distanceToSqr(target)>reach*reach || blocked) mc.hitResult=miss(target);
        else { mc.hitResult=best; if (best instanceof EntityHitResult e) mc.crosshairPickEntity=e.getEntity(); }
        Vec3 direction=target.subtract(eye);
        float yaw=(float)Math.toDegrees(Mth.atan2(direction.z,direction.x))-90;
        float pitch=(float)-Math.toDegrees(Mth.atan2(direction.y,Math.sqrt(direction.x*direction.x+direction.z*direction.z)));
        mc.player.setYRot(yaw); mc.player.setXRot(Mth.clamp(pitch,-90,90)); mc.player.setYHeadRot(yaw);
    }
    private static BlockHitResult miss(Vec3 position) { return BlockHitResult.miss(position,Direction.UP,BlockPos.containing(position)); }
    public static void drawStatus(GuiGraphics graphics) {
        Minecraft mc=Minecraft.getInstance();
        if (!active) {
            if (mc.level!=null && BoardManager.isEnabled()) graphics.drawString(mc.font,"画面尚未连接："+inactiveReason,8,8,0xffffcc66,true);
            return;
        }
        // Keep a visible connection indicator even when the rest of the HUD is hidden.
        // An empty/occluded world must not make a functioning overlay indistinguishable
        // from a missing presentation window.
        if (overlayActive() && !steveControl) {
            String title="KardsCraft · KARDS 操控";
            String hint="F9：在牌桌上操控史蒂夫  F10：史蒂夫第一人称";
            int width=Math.max(mc.font.width(title),mc.font.width(hint));
            graphics.fill(5,5,13+width,32,0xff102820);
            graphics.fill(5,5,7,32,0xff6aff9f);
            graphics.drawString(mc.font,title,10,9,0xff6aff9f,true);
            graphics.drawString(mc.font,hint,10,21,0xffeeeeee,true);
            return;
        }
        if (cardView && Config.overlay && mc.getWindow().isFullscreen()) {
            graphics.drawString(mc.font,"叠加已暂停：按 F11 将 Minecraft 改成窗口模式",8,8,0xffffcc66,true);
            return;
        }
        String mode=tableWalkActive() ? (kardsFollowing ? "牌桌第一人称 · KARDS 镜头跟随"
                        : "牌桌第一人称 · 等待 KARDS 镜头跟随…")
                : overlayActive() ? "牌桌叠加 · 史蒂夫操控"
                : !cardView ? "第一人称（MC 画面）· KARDS 镜头未跟随，需 KARDS 端支持"
                : "重建视角 · MC 卡牌代理（非 KARDS 原镜头）";
        if (!pauseReason.isEmpty()) mode+=" · 暂停："+pauseReason;
        if (!camValid) mode+=" · 镜头数据暂不可用";
        if (overlayActive() && steveControl && !Overlay.ownsInput()) mode+=" · 点击叠加层以操控";
        graphics.drawString(mc.font,mode,8,8,0xffeeeeee,true);
        String keys=tableWalkActive() ? "鼠标：转视角  右键：放置 / 点燃 TNT / 镐破坏  F10 或 Esc：回到俯视"
                : overlayActive() ? "E：物品栏  T：聊天  1–9：快捷栏  F10 或 Esc：第一人称"
                : !cardView ? "右键：放置 / 打火石点燃 TNT / 镐直接破坏  左键：挖掘或攻击  F10 或 Esc：返回牌桌" : "";
        if (!keys.isEmpty()) graphics.drawString(mc.font,keys,8,20,0xffcccccc,true);
        if (mc.player!=null) graphics.drawString(mc.font,String.format(java.util.Locale.ROOT,"生命：%.1f / %.1f 颗心",mc.player.getHealth()/2,mc.player.getMaxHealth()/2),8,keys.isEmpty()?20:32,0xffffbbbb,true);
    }
}
