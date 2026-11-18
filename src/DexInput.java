import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.lang.reflect.Method;

/** One input channel per authenticated browser connection, scoped to its display. */
public final class DexInput {
    private final int display;
    private final Object manager;
    private final Method inject, target;
    private long down;
    private int x, y;
    private final KeyCharacterMap keyboard = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private DexInput(int id) throws Exception {
        if (id <= 0) throw new IllegalArgumentException();
        display = id;
        Class<?> service = Class.forName("android.hardware.input.InputManagerGlobal");
        manager = service.getMethod("getInstance").invoke(null);
        inject = service.getMethod("injectInputEvent", InputEvent.class, int.class);
        target = InputEvent.class.getMethod("setDisplayId", int.class);
    }

    private void send(InputEvent event) throws Exception {
        target.invoke(event, display);
        if (!Boolean.TRUE.equals(inject.invoke(manager, event, 0))) throw new IllegalStateException("Input rejected");
    }

    private void motion(int action) throws Exception {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) down = now;
        if (down == 0) return;
        MotionEvent e = MotionEvent.obtain(down, now, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { send(e); } finally { e.recycle(); }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) down = 0;
    }

    private void run() throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        System.out.write(1); System.out.flush();
        try {
            while (true) {
                int kind = in.readUnsignedByte();
                x = in.readUnsignedShort(); y = in.readUnsignedShort();
                int key = in.readUnsignedShort();
                if (kind == 6 || kind == 7) {
                    MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
                    pointer.id = 0;
                    pointer.toolType = MotionEvent.TOOL_TYPE_MOUSE;
                    MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
                    coords.x = x;
                    coords.y = y;
                    if (kind == 6) coords.setAxisValue(MotionEvent.AXIS_VSCROLL, key - 8);
                    long now = SystemClock.uptimeMillis();
                    MotionEvent event = MotionEvent.obtain(now, now,
                            kind == 6 ? MotionEvent.ACTION_SCROLL : MotionEvent.ACTION_HOVER_MOVE,
                            1, new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coords},
                            0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
                    try { send(event); } finally { event.recycle(); }
                } else if (kind == 4) {
                    long t = SystemClock.uptimeMillis();
                    send(new KeyEvent(t,t,KeyEvent.ACTION_DOWN,key,0));
                    send(new KeyEvent(t,t,KeyEvent.ACTION_UP,key,0));
                } else if (kind == 5) {
                    KeyEvent[] events = keyboard.getEvents(new char[]{(char)key});
                    if (events != null) for (KeyEvent event : events) send(event);
                } else {
                    motion(kind);
                }
                System.out.write(1); System.out.flush();
            }
        } catch (EOFException end) {
            if (down != 0) motion(MotionEvent.ACTION_CANCEL);
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        new DexInput(Integer.parseInt(args[0])).run();
    }
}
