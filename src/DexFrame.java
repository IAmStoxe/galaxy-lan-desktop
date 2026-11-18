import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Pair;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;

/** Android 14 shell-side capture of a specified logical display. */
public final class DexFrame {
    public static void main(String[] args) throws Exception {
        int display = Integer.parseInt(args[0]);
        if (display <= 0) throw new IllegalArgumentException("External display required");
        Looper.prepareMainLooper();
        String api = "android.window.ScreenCapture";
        Object wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        Method capture = Class.forName("android.view.IWindowManager").getMethod("captureDisplay",
                int.class, Class.forName(api + "$CaptureArgs"), Class.forName(api + "$ScreenCaptureListener"));
        Method listener = Class.forName(api).getMethod("createSyncCaptureListener");
        DataOutputStream output = new DataOutputStream(System.out);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        while (true) {
            long nextCapture = SystemClock.uptimeMillis() + 50;
            Object sync = listener.invoke(null);
            boolean pair = sync instanceof Pair;
            Object callback = pair ? ((Pair<?, ?>) sync).first : sync;
            Object result = pair ? ((Pair<?, ?>) sync).second : sync;
            capture.invoke(wm, display, null, callback);
            Object shot = result.getClass().getMethod(pair ? "get" : "getBuffer").invoke(result);
            if (shot == null) throw new IllegalStateException("Display unavailable");
            try (HardwareBuffer buffer = (HardwareBuffer) shot.getClass().getMethod("getHardwareBuffer").invoke(shot)) {
                Bitmap hardware = (Bitmap) shot.getClass().getMethod("asBitmap").invoke(shot);
                Bitmap image = hardware.copy(Bitmap.Config.ARGB_8888, false);
                hardware.recycle();
                jpeg.reset();
                image.compress(Bitmap.CompressFormat.JPEG, 75, jpeg);
                image.recycle();
                output.writeInt(jpeg.size());
                jpeg.writeTo(output);
                output.flush();
            }
            if (args.length > 1) return;
            long remaining = nextCapture - SystemClock.uptimeMillis();
            if (remaining > 0) Thread.sleep(remaining);
        }
    }
}
