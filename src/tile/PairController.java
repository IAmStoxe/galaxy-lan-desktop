package org.galaxylan.desktop;
import android.content.*;
import android.util.Log;

public final class PairController extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if ("org.galaxylan.desktop.PREPARE".equals(intent.getAction())) {
            setResultCode(context.getExternalFilesDir(null) != null ? 1 : 0);
            return;
        }
        PendingResult pending=goAsync();
        new Thread(()->{
            try {
                new WirelessController(context).pair(intent.getIntExtra("port",0),intent.getStringExtra("code"));
                pending.setResultCode(1);
            } catch(Exception e){Log.w("DexBrowserTile","Pairing failed: "+e.getClass().getSimpleName());}
            finally{pending.finish();}
        }).start();
    }
}
