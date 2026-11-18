import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** Mirrors the owned virtual monitor into an AVC encoder while a viewer is connected. */
public final class DexVideo {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        int id = Integer.parseInt(args[0]);
        if (id <= 0) throw new IllegalArgumentException("External display required");
        Class<?> dm = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object info = dm.getMethod("getDisplayInfo", int.class).invoke(dm.getMethod("getInstance").invoke(null), id);
        if (info == null || !"DeX Browser".equals(info.getClass().getField("name").get(info))) {
            throw new IllegalStateException("Virtual desktop unavailable");
        }
        int stack = info.getClass().getField("layerStack").getInt(info);
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // Keep the encoder advancing when the desktop is otherwise static.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000);
        MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = encoder.createInputSurface();
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder display = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class).invoke(null, "Browser AVC", false);
        Rect rect = new Rect(0, 0, 1920, 1080);
        sc.getMethod("openTransaction").invoke(null);
        try {
            sc.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, display, surface);
            sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class).invoke(null, display, 0, rect, rect);
            sc.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, display, stack);
        } finally { sc.getMethod("closeTransaction").invoke(null); }
        encoder.start();
        MediaCodec.BufferInfo packet = new MediaCodec.BufferInfo();
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out, 65536));
        while (true) {
            int index = encoder.dequeueOutputBuffer(packet, 500000);
            if (index < 0) continue;
            ByteBuffer buffer = encoder.getOutputBuffer(index);
            if (buffer != null && packet.size > 0) {
                byte[] data = new byte[packet.size];
                buffer.position(packet.offset);
                buffer.get(data);
                out.writeInt(data.length);
                out.writeInt(packet.flags);
                out.writeLong(packet.presentationTimeUs);
                out.write(data);
                out.flush();
            }
            encoder.releaseOutputBuffer(index, false);
        }
    }
}
