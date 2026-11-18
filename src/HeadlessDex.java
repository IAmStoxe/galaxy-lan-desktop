import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/** Owns the virtual monitor for the lifetime of the browser session. */
public final class HeadlessDex {
    public static void main(String[] args) throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Looper.prepareMainLooper();
        Object system = thread.getMethod("systemMain").invoke(null);
        Context context = ((Context) thread.getMethod("getSystemContext").invoke(system))
                .createPackageContext("com.android.shell", 0);
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        ImageReader reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> {
            try (Image frame = source.acquireLatestImage()) { }
        }, new Handler(Looper.getMainLooper()));
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL").getInt(null)
                | DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null)
                | DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP").getInt(null)
                | DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS").getInt(null)
                | DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH").getInt(null);
        VirtualDisplay monitor = manager.createVirtualDisplay("DeX Browser", 1920, 1080, 160, reader.getSurface(), flags);
        if (monitor == null) throw new IllegalStateException("Could not create virtual monitor");
        int id = monitor.getDisplay().getDisplayId();
        PowerManager pm = context.getSystemService(PowerManager.class);
        PowerManager.WakeLock desktopPower = (PowerManager.WakeLock) PowerManager.class
                .getMethod("newWakeLock", int.class, String.class, int.class)
                .invoke(pm, PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "DeXBrowser:VirtualMonitor", id);
        desktopPower.acquire();
        Object wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Class.forName("android.view.IWindowManager").getMethod("setWindowingMode", int.class, int.class).invoke(wm, id, 5);
        System.out.println(id);
        System.out.flush();
        Looper.loop();
    }
}
