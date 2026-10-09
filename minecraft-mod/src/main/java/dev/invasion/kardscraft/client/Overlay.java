package dev.invasion.kardscraft.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import dev.invasion.kardscraft.KardsCraft;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * A separate software layered window presents Minecraft's rendered texture.
 * The GLFW OpenGL window and its CS_OWNDC class are never made layered or restyled.
 * All methods run on the Minecraft client/render thread. No external process memory is read.
 */
public final class Overlay {
    private static Pointer mcWindow, kardsWindow, window, dc, bitmap, oldBitmap, dibPixels;
    private static int bitmapWidth, bitmapHeight, searchTicks;
    private static int[] rect, pixels;
    private static ByteBuffer rgba;
    private static boolean applied, clickThrough = true, failed, focusPending;
    private static boolean presented;
    private static String diagnostic="尚未请求";
    private static String frameProblem="";
    /** Last failure, kept visible in the status after the failure flag is cleared for a retry. */
    private static String lastFailure="";
    private static final OverlayPolicy.ForegroundGrace grace=new OverlayPolicy.ForegroundGrace();
    /** True once the overlay has been shown since it was last requested; re-showing then never steals focus. */
    private static boolean shownSinceRequest;
    /** Minecraft window rectangle before the overlay resized it, restored when the overlay is no longer requested. */
    private static int[] savedMcRect, sourceSizedFor;
    private static boolean sourceSizeWarned;
    /** First-person board view: Minecraft's own window is moved over the KARDS client area, in front. */
    private static boolean firstPerson, firstPersonWarned;
    private static int[] firstPersonFor;
    private static long lastFrame;
    private static final boolean[] capturedMode=new boolean[2];
    private static final String captureSession=Long.toString(System.currentTimeMillis());
    private static long lastForeground=Long.MIN_VALUE;
    private static int foregroundChanges;
    private static int lastPriorGlError;
    private Overlay() {}
    public static boolean isApplied() { return applied; }
    public static boolean failed() { return failed; }
    public static String status() { return diagnostic; }
    private static void report(String text) {
        if (!diagnostic.equals(text)) { diagnostic=text; KardsCraft.LOG.info("Overlay: {}",text); }
    }
    /** {x, y, width, height} of the KARDS client area on screen, or null. */
    public static int[] screenRect() { return rect == null ? null : rect.clone(); }
    public static double aspect() { return rect == null ? 0 : (double)rect[2] / rect[3]; }

    public static void update(boolean wanted, boolean passThrough) {
        try { updateNative(wanted, passThrough); }
        catch (Throwable e) { fail(e); }
    }
    private static void updateNative(boolean wanted, boolean passThrough) {
        Win32.User32 u = Win32.api();
        if (u == null) { report("Win32 不可用；os="+System.getProperty("os.name")); return; }
        if (failed) {
            // A failure used to stay latched until Minecraft restarted. Clear it as soon as the overlay is
            // not requested (fallback view, leaving the board or world), so F10 or re-entering retries cleanly.
            if (wanted) return;
            failed=false;
            KardsCraft.LOG.info("Overlay: failure cleared; the next request will retry ({})",lastFailure);
        }
        if (window!=null && !u.IsWindow(window)) {
            // Closing the presentation window is an explicit switch back to native Minecraft.
            failed=true; close(); ViewController.releaseInputs(); report("叠加窗口已关闭，回到 MC 重建视角"); return;
        }
        long nativeHandle = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
        mcWindow = nativeHandle == 0 ? null : new Pointer(nativeHandle);
        if ((kardsWindow == null || !u.IsWindow(kardsWindow)) && searchTicks-- <= 0) {
            searchTicks=20; kardsWindow=Win32.findKards();
        }
        Pointer foreground = u.GetForegroundWindow();
        long foregroundId=foreground==null ? 0 : Pointer.nativeValue(foreground);
        if (wanted && foregroundId!=lastForeground) {
            lastForeground=foregroundId;
            KardsCraft.LOG.info("Overlay foreground change #{}: {}; applied={}, clickThrough={}",++foregroundChanges,
                    Win32.describe(foreground),applied,clickThrough);
        }
        // NULL foreground happens briefly during every focus switch: treat it as relevant.
        boolean relevant = foreground==null || Win32.same(foreground, kardsWindow) || Win32.same(foreground, mcWindow) || Win32.same(foreground, window);
        boolean hideForForeground = grace.shouldHide(relevant, System.nanoTime());
        // In first person the Minecraft window stays over KARDS; its original rectangle is restored on leaving the board.
        if (!wanted) { grace.reset(); shownSinceRequest=false; if (!firstPerson) restoreSource(u); hide(); report(lastFailure.isEmpty() ? "未请求展示（见客户端状态）" : "未请求展示；上次叠加失败："+lastFailure); return; }
        if (kardsWindow==null) { hide(); report("未找到 KARDS 窗口；"+Win32.searchStatus()); return; }
        if (u.IsIconic(kardsWindow)) { hide(); report("KARDS 窗口已最小化"); return; }
        if (hideForForeground) { hide(); report("前台不是 MC、KARDS 或叠加窗口"); return; }
        int[] r = new int[4], point = {0, 0};
        if (!u.GetClientRect(kardsWindow, r) || !u.ClientToScreen(kardsWindow, point)) { hide(); report("无法读取 KARDS 画面区域；Win32="+Native.getLastError()); return; }
        int width=r[2]-r[0], height=r[3]-r[1];
        if (width < 200 || height < 200 || (long)width*height > 16_777_216) { hide(); report("KARDS 画面尺寸不支持："+width+"x"+height); return; }
        rect = new int[]{point[0], point[1], width, height};
        if (window == null) {
            window = u.CreateWindowExW(Win32.WS_EX_LAYERED | Win32.WS_EX_TOOLWINDOW | Win32.WS_EX_TOPMOST,
                    // SS_NOTIFY (0x100) makes STATIC return HTCLIENT, not HTTRANSPARENT.
                    new WString("STATIC"), new WString("KardsCraft overlay"), Win32.WS_POPUP | 0x100,
                    point[0], point[1], width, height, null, null, null, null);
            if (window == null) throw new IllegalStateException("CreateWindowEx failed: " + Native.getLastError());
            KardsCraft.LOG.info("Overlay window created on native thread={}; overlay={}; Minecraft={}; KARDS={}",
                    Win32.currentThreadId(),Win32.describe(window),Win32.describe(mcWindow),Win32.describe(kardsWindow));
        }
        sizeSource(u);
        if (!applied || clickThrough != passThrough) {
            // Focus moves only when the overlay is first shown for this request or the control mode changes,
            // never when it re-appears after the user briefly used another window.
            boolean moveFocus=!shownSinceRequest || clickThrough != passThrough;
            shownSinceRequest=true;
            presented=false;
            frameProblem="";
            clickThrough=passThrough;
            int ex=Win32.WS_EX_LAYERED | Win32.WS_EX_TOOLWINDOW | Win32.WS_EX_TOPMOST;
            if (clickThrough) ex |= Win32.WS_EX_TRANSPARENT | Win32.WS_EX_NOACTIVATE;
            Native.setLastError(0);
            long previousStyle=u.SetWindowLongPtrW(window, Win32.GWL_EXSTYLE, Integer.toUnsignedLong(ex));
            int styleError=Native.getLastError();
            if (previousStyle==0 && styleError!=0) throw new IllegalStateException("SetWindowLongPtr failed: "+styleError);
            long actualStyle=u.GetWindowLongPtrW(window,Win32.GWL_EXSTYLE);
            int managedStyles=Win32.WS_EX_LAYERED|Win32.WS_EX_TOOLWINDOW|Win32.WS_EX_TOPMOST
                    |Win32.WS_EX_TRANSPARENT|Win32.WS_EX_NOACTIVATE;
            if ((actualStyle & managedStyles)!=ex) throw new IllegalStateException("Overlay style did not apply: 0x"+Long.toHexString(actualStyle));
            focusPending=moveFocus;
            lastFrame=0;
        }
        applied=true;
        report(!frameProblem.isEmpty() ? frameProblem : presented ? "已提交合成帧；"+(clickThrough ? "KARDS 操控" : "史蒂夫操控") : "窗口已准备，等待第一帧");
    }
    public static boolean isFirstPerson() { return firstPerson; }
    /**
     * Enter/keep (true) or leave (false) the first-person board view. Entering moves Minecraft's window so its client
     * area covers the KARDS client area, raises and focuses it; later ticks only follow KARDS if it moves or resizes.
     * Leaving does not move anything: the overlay then shrinks the window behind KARDS again (sizeSource), and
     * leaving the board restores the original rectangle (restoreSource).
     */
    public static void firstPerson(boolean on) {
        try { firstPersonNative(on); }
        catch (Throwable e) {
            if (!firstPersonWarned) { firstPersonWarned=true; KardsCraft.LOG.warn("First-person window placement failed: {}",e.toString()); }
        }
    }
    private static void firstPersonNative(boolean on) {
        Win32.User32 u=Win32.api();
        if (!on) {
            if (firstPerson) { firstPerson=false; firstPersonFor=null; KardsCraft.LOG.info("First-person board view: off"); }
            return;
        }
        boolean entering=!firstPerson;
        firstPerson=true;
        if (entering) KardsCraft.LOG.info("First-person board view: on");
        if (u==null || Minecraft.getInstance().getWindow().isFullscreen()) return;
        long handle=GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
        if (handle==0) return;
        mcWindow=new Pointer(handle);
        // Returning to the overlay must size the source window again.
        sourceSizedFor=null;
        if ((kardsWindow==null || !u.IsWindow(kardsWindow)) && searchTicks-- <= 0) { searchTicks=20; kardsWindow=Win32.findKards(); }
        if (kardsWindow==null || u.IsIconic(kardsWindow)) { if (entering) focusMinecraft(); return; }
        int[] r=new int[4], point={0,0};
        if (!u.GetClientRect(kardsWindow,r) || !u.ClientToScreen(kardsWindow,point)) return;
        int width=r[2]-r[0], height=r[3]-r[1];
        if (width<200 || height<200) { if (entering) focusMinecraft(); return; }
        int[] outer=new int[4], inner=new int[4], origin={0,0};
        if (!u.GetWindowRect(mcWindow,outer) || !u.GetClientRect(mcWindow,inner) || !u.ClientToScreen(mcWindow,origin)) return;
        int[] target=OverlayPolicy.coverClient(point[0],point[1],width,height,outer,inner,origin);
        if (!entering && java.util.Arrays.equals(target,firstPersonFor)) return;
        if (savedMcRect==null) savedMcRect=outer.clone();
        // HWND_TOP (null) on entry; afterwards only follow KARDS without touching z-order or focus.
        int flags=Win32.SWP_SHOWWINDOW | (entering ? 0 : Win32.SWP_NOZORDER|Win32.SWP_NOACTIVATE);
        if (u.SetWindowPos(mcWindow,null,target[0],target[1],target[2],target[3],flags)) {
            firstPersonFor=target;
            KardsCraft.LOG.info("First-person board view: Minecraft client area placed on KARDS {}x{} at {},{}",width,height,point[0],point[1]);
        } else if (!firstPersonWarned) {
            firstPersonWarned=true;
            KardsCraft.LOG.warn("First-person board view: cannot move the Minecraft window (Win32 {})",Native.getLastError());
        }
        if (entering) focusMinecraft();
    }
    public static boolean relevantForeground() {
        Win32.User32 u=Win32.api();
        if (u==null) return Minecraft.getInstance().isWindowActive();
        Pointer foreground=u.GetForegroundWindow();
        return Win32.same(foreground, mcWindow) || Win32.same(foreground, kardsWindow) || Win32.same(foreground, window);
    }
    public static boolean ownsInput() {
        return applied && !clickThrough && Win32.same(Win32.api().GetForegroundWindow(), window);
    }
    public static boolean focusMinecraft() {
        if (Win32.api()==null) return false;
        if (mcWindow==null) {
            long handle=GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
            if (handle!=0) mcWindow=new Pointer(handle);
        }
        return Win32.focus(mcWindow);
    }
    /** Normalized screen coordinates; null if the desktop cursor is outside the board. */
    public static double[] cursor() {
        if (!applied || rect==null) return null;
        int[] p=new int[2];
        if (!Win32.api().GetCursorPos(p)) return null;
        double x=(double)(p[0]-rect[0])/rect[2], y=(double)(p[1]-rect[1])/rect[3];
        return x<0 || x>1 || y<0 || y>1 ? null : new double[]{x,y};
    }
    /** At GameRenderer.render TAIL, while Minecraft's graphics context is current. */
    public static void present() {
        if (!applied || failed || rect==null) return;
        long now=System.nanoTime();
        if (now-lastFrame < 1_000_000_000L/ClientConfig.overlayFps) return;
        lastFrame=now;
        try {
            var target=Minecraft.getInstance().getMainRenderTarget();
            int sw=target.width, sh=target.height, width=rect[2], height=rect[3];
            if (sw<=0 || sh<=0 || (long)sw*sh>16_777_216) { frameProblem="MC 帧缓冲尺寸不支持："+sw+"x"+sh; report(frameProblem); return; }
            frameProblem="";
            int bytes=Math.multiplyExact(Math.multiplyExact(sw, sh), 4);
            if (rgba==null || rgba.capacity()!=bytes) {
                if (rgba!=null) MemoryUtil.memFree(rgba);
                rgba=MemoryUtil.memAlloc(bytes);
            }
            int texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int pack=GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            int rowLength=GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            int skipRows=GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS), skipPixels=GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            int packBuffer=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int priorGlError=GL11.glGetError();
            if (priorGlError!=lastPriorGlError && priorGlError!=GL11.GL_NO_ERROR)
                KardsCraft.LOG.warn("OpenGL error before overlay readback: 0x{}",Integer.toHexString(priorGlError));
            lastPriorGlError=priorGlError;
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getColorTextureId());
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,0);
                rgba.clear();
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
                int readError=GL11.glGetError();
                if (readError!=GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL frame readback failed: 0x"+Integer.toHexString(readError));
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,rowLength);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,skipRows);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,skipPixels);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,packBuffer);
            }
            ensureBitmap(width, height);
            OverlayPixels.convert(rgba, sw, sh, pixels, width, height, !clickThrough);
            dibPixels.write(0, pixels, 0, pixels.length);
            // Before the display calls: if they fail, the source-frame evidence is still saved.
            captureDiagnosticFrame(sw,sh,width,height,target.getColorTextureId());
            Win32.User32 u=Win32.api();
            if (!u.UpdateLayeredWindow(window, null, new Win32.Point(rect[0],rect[1]), new Win32.Point(width,height),
                    dc, new Win32.Point(0,0), 0, new Win32.Blend(), 2))
                throw new IllegalStateException("UpdateLayeredWindow failed: "+Native.getLastError());
            if (!u.SetWindowPos(window, Win32.HWND_TOPMOST, rect[0],rect[1],width,height,
                    Win32.SWP_NOACTIVATE|Win32.SWP_SHOWWINDOW|(focusPending ? Win32.SWP_FRAMECHANGED : 0)))
                throw new IllegalStateException("Show/position overlay failed: "+Native.getLastError());
            if (!u.IsWindowVisible(window)) throw new IllegalStateException("Overlay is still hidden after SetWindowPos(SHOWWINDOW)");
            if (focusPending) {
                focusPending=false;
                // After first-person/inventory the ordinary MC window may still cover KARDS.
                // Lower only our own source window; its position, size and styles are unchanged.
                if (mcWindow!=null && !u.SetWindowPos(mcWindow,kardsWindow,0,0,0,0,
                        Win32.SWP_NOMOVE|Win32.SWP_NOSIZE|Win32.SWP_NOACTIVATE))
                    throw new IllegalStateException("Cannot place Minecraft behind KARDS: "+Native.getLastError());
                boolean kardsFocused=Win32.same(u.GetForegroundWindow(),kardsWindow) || Win32.focus(kardsWindow);
                boolean overlayFocused=clickThrough || Win32.same(u.GetForegroundWindow(),window) || Win32.focus(window);
                KardsCraft.LOG.info("Overlay focus request: KARDS={}, overlay={}, foreground={}",kardsFocused,
                        clickThrough ? "not requested" : Boolean.toString(overlayFocused),Win32.describe(u.GetForegroundWindow()));
            }
            if (!presented) {
                presented=true;
                KardsCraft.LOG.info("Overlay first frame submitted: source={}x{}, destination={}x{} at {},{}",sw,sh,width,height,rect[0],rect[1]);
                KardsCraft.LOG.info("Overlay native state after submit: {}",nativeState());
                lastFailure="";
                report("已提交合成帧；"+(clickThrough ? "KARDS 操控" : "史蒂夫操控"));
            }
        } catch (Throwable e) { fail(e); }
    }
    private static String nativeState() {
        try {
            return "overlay={"+Win32.windowState(window)+"}; KARDS={"+Win32.windowState(kardsWindow)
                    +"}; MC={"+Win32.windowState(mcWindow)+"}; foreground={"+Win32.describe(Win32.api().GetForegroundWindow())+"}";
        } catch (Throwable e) { return "window metadata unavailable: "+e; }
    }
    private static void captureDiagnosticFrame(int sw,int sh,int width,int height,int texture) {
        int mode=clickThrough ? 0 : 1;
        if (capturedMode[mode]) return;
        // At most one pair per control mode per launch, independent of hide/show and focus churn.
        capturedMode[mode]=true;
        try {
            var frame=OverlayFrameDiagnostics.snapshot(rgba,sw,sh,pixels,width,height);
            String renderState=renderState();
            String metadata="colorTexture="+texture+"; "+renderState+"; "+nativeState();
            String name=captureSession+"-"+(clickThrough ? "kards" : "steve");
            Path directory=Minecraft.getInstance().gameDirectory.toPath().resolve("logs/kardscraft-overlay-diag");
            KardsCraft.LOG.info("Overlay pixel evidence: {}; saving {}",frame.summary(),directory.resolve(name));
            KardsCraft.LOG.info("Overlay scene evidence: {}",renderState);
            // Copies above are detached from the render buffers; PNG compression never blocks rendering.
            Thread writer=new Thread(() -> {
                try {
                    OverlayFrameDiagnostics.write(directory,name,frame,metadata);
                    KardsCraft.LOG.info("Overlay diagnostic PNGs saved: {}",directory.resolve(name));
                } catch (Exception e) { KardsCraft.LOG.warn("Overlay diagnostic save failed: {}",e.toString()); }
            },"KardsCraft-frame-diagnostic");
            writer.setDaemon(true);
            writer.start();
        } catch (Exception e) { KardsCraft.LOG.warn("Overlay diagnostic snapshot failed: {}",e.toString()); }
    }
    private static String renderState() {
        Minecraft mc=Minecraft.getInstance();
        var camera=mc.gameRenderer.getMainCamera();
        var position=camera.getPosition();
        var player=mc.player;
        return "cameraPosition="+position+",cameraDetached="+camera.isDetached()
                +",cameraEntityIsPlayer="+(camera.getEntity()==player)
                +",player="+(player==null ? "NULL" : player.position())
                +",playerShouldRender="+(player!=null && player.shouldRender(position.x,position.y,position.z))
                +",playerSectionCompiled="+(player!=null && mc.levelRenderer.isSectionCompiled(player.blockPosition()));
    }
    /**
     * Renders Minecraft at a useful resolution: its own window (kept behind KARDS) is resized to
     * ClientConfig.overlayRenderPercent of the KARDS area. Restored by restoreSource().
     */
    private static void sizeSource(Win32.User32 u) {
        if (mcWindow==null || kardsWindow==null || rect==null) return;
        int[] want=OverlayPolicy.sourceSize(rect[2],rect[3],ClientConfig.overlayRenderPercent);
        if (java.util.Arrays.equals(want,sourceSizedFor)) return;
        int[] outer=new int[4], inner=new int[4];
        if (!u.GetWindowRect(mcWindow,outer) || !u.GetClientRect(mcWindow,inner)) return;
        if (savedMcRect==null) savedMcRect=outer.clone();
        int borderW=(outer[2]-outer[0])-(inner[2]-inner[0]), borderH=(outer[3]-outer[1])-(inner[3]-inner[1]);
        if (u.SetWindowPos(mcWindow,kardsWindow,rect[0],rect[1],want[0]+borderW,want[1]+borderH,Win32.SWP_NOACTIVATE)) {
            sourceSizedFor=want;
            KardsCraft.LOG.info("Overlay source: Minecraft window resized to {}x{} client ({}% of KARDS {}x{}), behind KARDS",
                    want[0],want[1],ClientConfig.overlayRenderPercent,rect[2],rect[3]);
        } else if (!sourceSizeWarned) {
            sourceSizeWarned=true;
            KardsCraft.LOG.warn("Overlay source: cannot resize the Minecraft window (Win32 {}); keeping its size",Native.getLastError());
        }
    }
    private static void restoreSource(Win32.User32 u) {
        if (savedMcRect==null || mcWindow==null || u==null) { savedMcRect=null; sourceSizedFor=null; return; }
        int[] r=savedMcRect;
        savedMcRect=null; sourceSizedFor=null;
        if (u.SetWindowPos(mcWindow,null,r[0],r[1],r[2]-r[0],r[3]-r[1],Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER))
            KardsCraft.LOG.info("Overlay source: Minecraft window restored to {}x{} at {},{}",r[2]-r[0],r[3]-r[1],r[0],r[1]);
    }
    private static void ensureBitmap(int width,int height) {
        if (bitmap!=null && bitmapWidth==width && bitmapHeight==height) return;
        releaseBitmap();
        Win32.Gdi32 g=Win32.gdi();
        dc=g.CreateCompatibleDC(null);
        if (dc==null) throw new IllegalStateException("CreateCompatibleDC failed");
        PointerByReference bits=new PointerByReference();
        bitmap=g.CreateDIBSection(dc, new Win32.BitmapInfo(width,height), 0, bits, null, 0);
        if (bitmap==null) throw new IllegalStateException("CreateDIBSection failed");
        oldBitmap=g.SelectObject(dc,bitmap);
        dibPixels=bits.getValue();
        if (dibPixels==null || oldBitmap==null) throw new IllegalStateException("DIB selection failed");
        bitmapWidth=width; bitmapHeight=height; pixels=new int[Math.multiplyExact(width,height)];
    }
    private static void hide() {
        if (applied && window!=null) Win32.api().ShowWindow(window,0);
        applied=false; focusPending=false; presented=false;
    }
    private static void releaseBitmap() {
        Win32.Gdi32 g=Win32.gdi();
        if (g==null) return;
        if (dc!=null && oldBitmap!=null) g.SelectObject(dc,oldBitmap);
        if (bitmap!=null) g.DeleteObject(bitmap);
        if (dc!=null) g.DeleteDC(dc);
        dc=null; bitmap=null; oldBitmap=null; dibPixels=null; pixels=null;
    }
    private static void fail(Throwable e) {
        KardsCraft.LOG.error("Overlay disabled; use the reconstructed Minecraft view: {}", e.toString());
        failed=true;
        lastFailure=e.getClass().getSimpleName()+"："+e.getMessage();
        ViewController.releaseInputs();
        try { close(); }
        catch (Throwable cleanup) { applied=false; KardsCraft.LOG.warn("Overlay cleanup failed: {}",cleanup.toString()); }
        report("叠加失败："+e.getClass().getSimpleName()+"："+e.getMessage());
    }
    public static void close() {
        firstPerson=false; firstPersonFor=null;
        try { restoreSource(Win32.api()); } catch (Throwable e) { savedMcRect=null; sourceSizedFor=null; }
        shownSinceRequest=false; grace.reset();
        hide();
        releaseBitmap();
        if (window!=null && Win32.api()!=null) Win32.api().DestroyWindow(window);
        window=null; rect=null;
        if (rgba!=null) MemoryUtil.memFree(rgba);
        rgba=null;
    }
}
