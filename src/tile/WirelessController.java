package org.galaxylan.desktop;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import java.io.*;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.*;

final class WirelessController {
    private final Context app;
    WirelessController(Context context) { app = context.getApplicationContext(); }

    private String adb(String input, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(app.getApplicationInfo().nativeLibraryDir + "/libadb.so");
        cmd.add("-L");
        cmd.add("localfilesystem:" + app.getFilesDir() + "/control.sock");
        Collections.addAll(cmd,args);
        ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
        builder.environment().put("HOME", app.getFilesDir().getPath());
        builder.environment().put("TMPDIR", app.getCacheDir().getPath());
        builder.environment().put("ADB_MDNS_AUTO_CONNECT", "0");
        Process process = builder.start();
        try (OutputStream out = process.getOutputStream()) {
            if (input != null) out.write((input+"\n").getBytes("UTF-8"));
        }
        if (!process.waitFor(9,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Controller timed out"); }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line; while ((line=reader.readLine())!=null) result.append(line).append('\n');
        }
        if (process.exitValue()!=0) throw new IOException(result.toString().trim());
        return result.toString().trim();
    }

    void pair(int port,String code) throws Exception {
        if (port<1 || port>65535 || code==null || !code.matches("[0-9]{6}")) throw new IOException("Invalid pairing details");
        try {
            if (!adb(code,"pair","127.0.0.1:"+port).contains("Successfully paired")) throw new IOException("Pairing failed");
        } finally { shutdown(); }
    }

    private int discover() throws Exception {
        NsdManager nsd=app.getSystemService(NsdManager.class);
        BlockingQueue<NsdServiceInfo> found=new LinkedBlockingQueue<>();
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
            public void onServiceFound(NsdServiceInfo s){found.offer(s);}
            public void onServiceLost(NsdServiceInfo s){}
            public void onDiscoveryStarted(String t){}
            public void onDiscoveryStopped(String t){}
            public void onStartDiscoveryFailed(String t,int e){}
            public void onStopDiscoveryFailed(String t,int e){}
        };
        nsd.discoverServices("_adb-tls-connect._tcp",NsdManager.PROTOCOL_DNS_SD,listener);
        try {
            long deadline=android.os.SystemClock.elapsedRealtime()+15000;
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                NsdServiceInfo info=found.poll(1,TimeUnit.SECONDS);
                if(info==null) continue;
                CompletableFuture<Integer> answer=new CompletableFuture<>();
                nsd.resolveService(info,new NsdManager.ResolveListener(){
                    public void onResolveFailed(NsdServiceInfo s,int error){answer.complete(0);}
                    public void onServiceResolved(NsdServiceInfo s){
                        try {answer.complete(NetworkInterface.getByInetAddress(s.getHost())!=null?s.getPort():0);}
                        catch(Exception ignored){answer.complete(0);}
                    }
                });
                try {int p=answer.get(3,TimeUnit.SECONDS);if(p>0)return p;} catch(TimeoutException ignored){}
            }
            throw new IOException("No wireless debugging service on this phone");
        } finally {nsd.stopServiceDiscovery(listener);}
    }

    void start() throws Exception {
        if(Settings.Global.getInt(app.getContentResolver(),"adb_wifi_enabled",0)!=1)
            Settings.Global.putInt(app.getContentResolver(),"adb_wifi_enabled",1);
        String target="127.0.0.1:"+discover();
        try {
            String connected=adb(null,"connect",target);
            if(!connected.contains("connected to"))throw new IOException("Pairing required");
            String output=adb(null,"-s",target,"shell","sh","/data/local/tmp/galaxy-lan-desktop/lan-services.sh","browser-on");
            if(!output.contains(":8443/"))throw new IOException("Desktop startup failed");
        } finally {shutdown();}
    }

    private void shutdown(){try{adb(null,"kill-server");}catch(Exception ignored){}}
}
