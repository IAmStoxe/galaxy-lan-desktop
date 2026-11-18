package org.galaxylan.desktop;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import java.io.*;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.*;

final class WirelessController {
    static final String BASE = "/data/local/tmp/galaxy-lan-desktop";
    private static final Object CONTROLLER_LOCK = new Object();
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
        synchronized (CONTROLLER_LOCK) {
            try {
                if (!adb(code,"pair","127.0.0.1:"+port).contains("Successfully paired")) {
                    throw new IOException("Pairing failed. Open a fresh pairing dialog and retry.");
                }
            } finally { shutdown(); }
        }
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

    interface SessionAction<T> { T run(String target) throws Exception; }

    <T> T connected(SessionAction<T> action) throws Exception {
        synchronized (CONTROLLER_LOCK) {
            if (Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0) != 1) {
                if (app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("Turn on Wireless debugging in Developer options and try again.");
                }
                Settings.Global.putInt(app.getContentResolver(), "adb_wifi_enabled", 1);
            }
            String target = "127.0.0.1:" + discover();
            try {
                adb(null, "connect", target);
                if (!"device".equals(adb(null, "-s", target, "get-state"))) {
                    throw new IOException("Pair this app in Wireless debugging first.");
                }
                return action.run(target);
            } finally { shutdown(); }
        }
    }

    String shell(String target, String command) throws Exception {
        return adb(null, "-s", target, "shell", command);
    }

    void push(String target, File file, String destination) throws Exception {
        adb(null, "-s", target, "push", file.getAbsolutePath(), destination);
    }

    void start() throws Exception {
        connected(target -> {
            String output = shell(target, "sh " + BASE + "/lan-services.sh browser-on");
            if (!output.contains(":8443/")) throw new IOException("Desktop startup failed.");
            return null;
        });
    }

    private void shutdown(){try{adb(null,"kill-server");}catch(Exception ignored){}}
}
