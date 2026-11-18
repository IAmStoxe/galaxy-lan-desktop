package org.galaxylan.desktop;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.File;
import android.os.SystemClock;



public final class BrowserTile extends TileService {
    private boolean live, changing;
    private int revision;
    private String problem;

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(live ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(changing ? "Switching…" : problem != null ? problem : live ? "On" : "Off");
        tile.updateTile();
    }

    private void run(boolean toggle) {
        if (changing) return;
        int current = ++revision;
        changing = toggle;
        problem = null;
        refresh();
        new Thread(() -> {
            boolean success = false, enabled = false;
            try {
                File dir = getExternalFilesDir(null);
                File ready = new File(dir, "ready");
                long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
                if (dir != null && ready.exists() && ready.lastModified() >= boot) {
                    File request = new File(dir, "enabled");
                    File listening = new File(dir, "listening");
                    enabled = listening.exists();
                    if (toggle) {
                        if (enabled) request.delete(); else request.createNewFile();
                        boolean before = enabled;
                        for (int i=0; i<40 && listening.exists()==before; i++) Thread.sleep(50);
                        enabled = listening.exists();
                        success = enabled != before;
                    } else success = true;
                } else {
                    if (toggle) new WirelessController(this).start();
                    enabled = toggle;
                    success = true;
                }
            } catch (Exception e) {
                Log.w("DexBrowserTile", "Service command unavailable", e);
            }
            final boolean ok = success, active = enabled;
            getMainExecutor().execute(() -> {
                if (current != revision) return;
                changing = false;
                live = active;
                if (!ok) problem = "Unavailable · tap to retry";
                refresh();
            });
        }, "dex-control").start();
    }

    @Override public void onStartListening() { run(false); }
    @Override public void onClick() { unlockAndRun(() -> run(true)); }
}
