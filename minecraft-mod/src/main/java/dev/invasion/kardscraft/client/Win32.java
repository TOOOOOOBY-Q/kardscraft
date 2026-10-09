package dev.invasion.kardscraft.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import dev.invasion.kardscraft.KardsCraft;
import java.util.Arrays;
import java.util.Locale;

/** Win32 declarations. No input injection and no edits to the KARDS window or process. */
public final class Win32 {
    public static final int GWL_STYLE = -16, GWL_EXSTYLE = -20;
    public static final int WS_POPUP = 0x80000000;
    public static final int WS_EX_TOPMOST = 8, WS_EX_TRANSPARENT = 32, WS_EX_TOOLWINDOW = 128;
    public static final int WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x8000000;
    public static final int SWP_NOSIZE = 1, SWP_NOMOVE = 2, SWP_NOZORDER = 4, SWP_NOACTIVATE = 16, SWP_FRAMECHANGED = 32, SWP_SHOWWINDOW = 64;
    /**
     * (HWND)-1. Must be the long overload: createConstant(int) zero-extends -1 to 0x00000000ffffffff on
     * 64-bit Windows, which SetWindowPos rejects with ERROR_INVALID_WINDOW_HANDLE (1400).
     */
    public static final Pointer HWND_TOPMOST = Pointer.createConstant(-1L);
    public interface WindowEnumerator extends StdCallLibrary.StdCallCallback { boolean invoke(Pointer window, Pointer data); }
    public interface User32 extends StdCallLibrary {
        boolean EnumWindows(WindowEnumerator callback, Pointer data);
        int GetClassNameW(Pointer window, char[] value, int length);
        int GetWindowThreadProcessId(Pointer window, IntByReference id);
        Pointer CreateWindowExW(int exStyle, WString windowClass, WString title, int style,
                               int x, int y, int width, int height, Pointer parent, Pointer menu, Pointer instance, Pointer data);
        boolean DestroyWindow(Pointer window);
        boolean ShowWindow(Pointer window, int command);
        boolean GetClientRect(Pointer window, int[] rect);
        boolean GetWindowRect(Pointer window, int[] rect);
        boolean ClientToScreen(Pointer window, int[] point);
        boolean GetCursorPos(int[] point);
        boolean SetCursorPos(int x, int y);
        int ShowCursor(boolean show);
        long SetWindowLongPtrW(Pointer window, int index, long value);
        long GetWindowLongPtrW(Pointer window, int index);
        Pointer GetWindow(Pointer window, int command);
        boolean SetWindowPos(Pointer window, Pointer after, int x, int y, int width, int height, int flags);
        boolean UpdateLayeredWindow(Pointer window, Pointer screenDC, Point destination, Point size, Pointer sourceDC,
                                    Point source, int colorKey, Blend blend, int flags);
        short GetAsyncKeyState(int key);
        boolean SetForegroundWindow(Pointer window);
        Pointer GetForegroundWindow();
        boolean IsIconic(Pointer window);
        boolean IsWindow(Pointer window);
        boolean IsWindowVisible(Pointer window);
    }
    public interface Gdi32 extends StdCallLibrary {
        Pointer CreateCompatibleDC(Pointer dc);
        boolean DeleteDC(Pointer dc);
        Pointer CreateDIBSection(Pointer dc, BitmapInfo info, int usage, PointerByReference bits, Pointer section, int offset);
        Pointer SelectObject(Pointer dc, Pointer object);
        boolean DeleteObject(Pointer object);
    }
    public interface Kernel32 extends StdCallLibrary {
        int GetCurrentThreadId();
        Pointer OpenProcess(int access, boolean inherit, int processId);
        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] name, IntByReference length);
        boolean CloseHandle(Pointer handle);
    }
    public interface DwmApi extends StdCallLibrary {
        int DwmGetWindowAttribute(Pointer window, int attribute, IntByReference value, int size);
        int DwmIsCompositionEnabled(IntByReference enabled);
    }
    @Structure.FieldOrder({"x", "y"})
    public static class Point extends Structure { public int x, y; public Point(int x, int y) { this.x=x; this.y=y; } }
    @Structure.FieldOrder({"operation", "flags", "alpha", "format"})
    public static class Blend extends Structure { public byte operation=0, flags=0, alpha=(byte)255, format=1; }
    @Structure.FieldOrder({"size", "width", "height", "planes", "bitCount", "compression", "sizeImage", "xPels", "yPels", "used", "important", "colors"})
    public static class BitmapInfo extends Structure {
        public int size=40, width, height;
        public short planes=1, bitCount=32;
        public int compression=0, sizeImage=0, xPels=0, yPels=0, used=0, important=0;
        public int[] colors = new int[1];
        public BitmapInfo(int width, int height) { this.width=width; this.height=-height; }
    }
    private static User32 user32;
    private static Gdi32 gdi32;
    private static Kernel32 kernel32;
    private static DwmApi dwmApi;
    private static boolean triedDwm;
    private static boolean tried;
    private static String searchDiagnostic="尚未搜索";
    private Win32() {}
    public static User32 api() {
        if (!tried) {
            tried = true;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                try {
                    user32=Native.load("user32", User32.class); gdi32=Native.load("gdi32", Gdi32.class); kernel32=Native.load("kernel32", Kernel32.class);
                    KardsCraft.LOG.info("Win32 overlay API loaded: os={}, pointerSize={}, wcharSize={}",System.getProperty("os.name"),Native.POINTER_SIZE,Native.WCHAR_SIZE);
                }
                catch (Throwable e) { user32=null; KardsCraft.LOG.warn("Native overlay unavailable: {}", e.toString()); }
            }
        }
        return user32;
    }
    public static Gdi32 gdi() { return api() == null ? null : gdi32; }
    public static boolean isKeyDown(int key) { return api()!=null && (user32.GetAsyncKeyState(key)&0x8000)!=0; }
    public static boolean focus(Pointer window) { return api()!=null && window!=null && user32.SetForegroundWindow(window); }
    public static boolean same(Pointer a, Pointer b) { return a!=null && b!=null && Pointer.nativeValue(a)==Pointer.nativeValue(b); }
    public static int currentThreadId() { return api()==null ? 0 : kernel32.GetCurrentThreadId(); }
    /** Window metadata only: no desktop or other application's pixels are captured. */
    public static String describe(Pointer window) {
        if (window==null) return "NULL";
        try {
            char[] name=new char[128];
            IntByReference pid=new IntByReference();
            int thread=user32.GetWindowThreadProcessId(window,pid);
            user32.GetClassNameW(window,name,name.length);
            return "0x"+Long.toHexString(Pointer.nativeValue(window))+"/"+Native.toString(name)+"/pid="+pid.getValue()+"/tid="+thread;
        } catch (Throwable e) { return "window metadata unavailable: "+e; }
    }
    public static String windowState(Pointer window) {
        if (window==null) return "NULL";
        int[] bounds=new int[4];
        boolean rectOk=user32.GetWindowRect(window,bounds);
        return describe(window)+",visible="+user32.IsWindowVisible(window)+",iconic="+user32.IsIconic(window)
                +",style=0x"+Long.toHexString(user32.GetWindowLongPtrW(window,GWL_STYLE))
                +",exstyle=0x"+Long.toHexString(user32.GetWindowLongPtrW(window,GWL_EXSTYLE))
                +",rect="+(rectOk ? Arrays.toString(bounds) : "unavailable")+","+dwmState(window)
                +",above="+describe(user32.GetWindow(window,3))+",below="+describe(user32.GetWindow(window,2));
    }
    private static String dwmState(Pointer window) {
        if (!triedDwm) {
            triedDwm=true;
            try { dwmApi=Native.load("dwmapi",DwmApi.class); }
            catch (Throwable ignored) { return "dwm=unavailable"; }
        }
        if (dwmApi==null) return "dwm=unavailable";
        IntByReference cloaked=new IntByReference(), composition=new IntByReference();
        int cloakResult=dwmApi.DwmGetWindowAttribute(window,14,cloaked,4);
        int compositionResult=dwmApi.DwmIsCompositionEnabled(composition);
        return "cloaked="+(cloakResult==0 ? Integer.toString(cloaked.getValue()) : "unavailable")
                +",composition="+(compositionResult==0 ? Integer.toString(composition.getValue()) : "unavailable");
    }
    public static String searchStatus() { return searchDiagnostic; }
    /** Only a visible UnrealWindow owned by the KARDS executable is eligible. */
    public static Pointer findKards() {
        if (api()==null) return null;
        Pointer[] result = {null};
        int[] counts={0,0,0};
        StringBuilder candidates=new StringBuilder();
        boolean enumerated=user32.EnumWindows((window, data) -> {
            if (!user32.IsWindowVisible(window)) return true;
            char[] name = new char[256];
            user32.GetClassNameW(window, name, name.length);
            if (!Native.toString(name).equals("UnrealWindow")) return true;
            counts[0]++;
            IntByReference id = new IntByReference();
            user32.GetWindowThreadProcessId(window, id);
            Pointer process = kernel32.OpenProcess(0x1000, false, id.getValue());
            if (process == null) { counts[1]++; return true; }
            try {
                char[] path = new char[32768];
                IntByReference length = new IntByReference(path.length);
                if (kernel32.QueryFullProcessImageNameW(process, 0, path, length)) {
                    String file=new String(path,0,length.getValue()).toLowerCase(Locale.ROOT);
                    candidates.append(file.substring(file.lastIndexOf('\\')+1)).append(';');
                    if (file.endsWith("\\kards-win64-shipping.exe")) { result[0]=window; return false; }
                } else {
                    counts[2]++;
                }
            } finally { kernel32.CloseHandle(process); }
            return true;
        }, null);
        searchDiagnostic=result[0]!=null ? "已匹配 KARDS 进程"
                : "可见 UnrealWindow="+counts[0]+"，进程查询拒绝="+counts[1]+"，路径查询失败="+counts[2]
                +"，候选="+candidates+(enumerated ? "" : "，枚举错误="+Native.getLastError());
        return result[0];
    }
}
