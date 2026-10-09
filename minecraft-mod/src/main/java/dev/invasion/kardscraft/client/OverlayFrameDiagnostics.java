package dev.invasion.kardscraft.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Captures only our own OpenGL readback and the exact pixels sent to our layered window. */
public final class OverlayFrameDiagnostics {
    private OverlayFrameDiagnostics() {}

    public record Frame(int sourceWidth, int sourceHeight, int width, int height,
                        int[] sourceRgb, int[] compositeArgb, int sourceMagenta,
                        int sourceZeroAlpha, int outputZeroAlpha, int outputInputOnly,
                        int outputContent, int left, int top, int right, int bottom) {
        public String summary() {
            return "source="+sourceWidth+"x"+sourceHeight+", sourceMagenta="+sourceMagenta
                    +", sourceNonMagenta="+(sourceWidth*sourceHeight-sourceMagenta)
                    +", sourceAlpha0="+sourceZeroAlpha+", output="+width+"x"+height
                    +", outputAlpha0="+outputZeroAlpha+", outputAlpha1="+outputInputOnly
                    +", outputContent="+outputContent+", contentBounds=["+left+","+top+","+right+","+bottom+"]";
        }
    }

    public static Frame snapshot(ByteBuffer rgba, int sw, int sh, int[] output, int width, int height) {
        if (sw<=0 || sh<=0 || width<=0 || height<=0 || (long)sw*sh*4>rgba.limit()
                || (long)width*height>output.length) throw new IllegalArgumentException("Invalid diagnostic frame");
        int[] rgb=new int[Math.multiplyExact(sw,sh)], argb=new int[Math.multiplyExact(width,height)];
        int magenta=0, zeroAlpha=0, outZero=0, inputOnly=0, content=0;
        int left=width, top=height, right=-1, bottom=-1;
        for (int y=0;y<sh;y++) for (int x=0;x<sw;x++) {
            int offset=((sh-1-y)*sw+x)*4;
            int r=rgba.get(offset)&255, g=rgba.get(offset+1)&255, b=rgba.get(offset+2)&255;
            if (r>=250 && g<=5 && b>=250) magenta++;
            if (rgba.get(offset+3)==0) zeroAlpha++;
            // Force opaque only in the diagnostic source PNG so alpha-zero RGB remains inspectable.
            rgb[y*sw+x]=0xff000000 | r<<16 | g<<8 | b;
        }
        System.arraycopy(output,0,argb,0,argb.length);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            int alpha=argb[y*width+x]>>>24;
            if (alpha==0) outZero++;
            else if (alpha==1) inputOnly++;
            else {
                content++;
                left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x); bottom=Math.max(bottom,y);
            }
        }
        if (content==0) left=top=right=bottom=-1;
        return new Frame(sw,sh,width,height,rgb,argb,magenta,zeroAlpha,outZero,inputOnly,content,left,top,right,bottom);
    }

    public static void write(Path directory, String name, Frame frame, String windowState) throws IOException {
        Files.createDirectories(directory);
        writePng(directory.resolve(name+"-mc-rgb.png"),frame.sourceWidth,frame.sourceHeight,frame.sourceRgb);
        writePng(directory.resolve(name+"-composite-alpha.png"),frame.width,frame.height,frame.compositeArgb);
        Files.writeString(directory.resolve(name+".txt"),frame.summary()+System.lineSeparator()+windowState
                +System.lineSeparator()+"mc-rgb.png: source RGB made opaque for inspection; source alpha is counted above."
                +System.lineSeparator()+"composite-alpha.png: exact ARGB content submitted to our layered window. No KARDS/desktop pixels captured.");
    }
    private static void writePng(Path path, int width, int height, int[] pixels) throws IOException {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,width,height,pixels,0,width);
        if (!ImageIO.write(image,"png",path.toFile())) throw new IOException("PNG writer unavailable");
    }
}
