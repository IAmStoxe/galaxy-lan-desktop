package org.galaxylan.desktop;

import android.content.Context;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class LanServices {
    private static final String BASE = WirelessController.BASE;
    private static final String CONTROL = "sh " + BASE + "/lan-services.sh ";
    private static final String[] PAYLOAD = {
            "dexbrowser", "web", "gosamba", "HeadlessDex.jar", "DexFrame.jar",
            "DexVideo.jar", "DexInput.jar", "lan-services.sh", "provision.sh", "welcome.html"
    };

    enum Service {
        DESKTOP("browser-on", "browser-off", 8443),
        SMB("smb-start", "smb-stop", 4450),
        WEB("web-start", "web-stop", 8080);

        final String start, stop;
        final int port;
        Service(String start, String stop, int port) {
            this.start = start;
            this.stop = stop;
            this.port = port;
        }
    }

    static final class State {
        final String ip;
        final boolean desktop, smb, web;
        State(String ip, boolean desktop, boolean smb, boolean web) {
            this.ip = ip;
            this.desktop = desktop;
            this.smb = smb;
            this.web = web;
        }
        boolean running(Service service) {
            switch (service) {
                case DESKTOP: return desktop;
                case SMB: return smb;
                case WEB: return web;
                default: throw new IllegalArgumentException();
            }
        }
    }

    static final class Access {
        final String password, fingerprint;
        Access(String password, String fingerprint) {
            this.password = password;
            this.fingerprint = fingerprint;
        }
    }

    private final Context app;
    private final WirelessController controller;

    LanServices(Context context) {
        app = context.getApplicationContext();
        controller = new WirelessController(app);
    }

    State refresh() throws Exception {
        return controller.connected(target -> {
            provision(target);
            return state(target);
        });
    }

    State set(Service service, boolean enabled) throws Exception {
        return controller.connected(target -> {
            provision(target);
            controller.shell(target, CONTROL + (enabled ? service.start : service.stop));
            for (int attempt = 0; attempt < 8; attempt++) {
                State result = state(target);
                if (result.running(service) == enabled) return result;
                Thread.sleep(150);
            }
            throw new IOException("The service did not respond. Check Wi-Fi and retry.");
        });
    }

    Access access(Service service) throws Exception {
        String file;
        switch (service) {
            case DESKTOP: file = "dex-password"; break;
            case SMB: file = "smb-password"; break;
            default: throw new IllegalArgumentException();
        }
        return controller.connected(target -> {
            String password = controller.shell(target, "cat " + BASE + "/" + file);
            if (!password.matches("[A-Za-z0-9_+/=-]{20,128}")) {
                throw new IOException("Password unavailable. Start the service first.");
            }
            String fingerprint = "";
            if (service == Service.DESKTOP) {
                String pem = controller.shell(target, "cat " + BASE + "/dex-cert.pem");
                byte[] certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII))).getEncoded();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) {
                    if (hex.length() > 0) hex.append(':');
                    hex.append(String.format(Locale.ROOT, "%02X", value & 255));
                }
                fingerprint = hex.toString();
            }
            return new Access(password, fingerprint);
        });
    }

    private State state(String target) throws Exception {
        String response = controller.shell(target, "set -e; " + CONTROL + "status; " +
                CONTROL + "browser-state; ip -4 -o addr show wlan0 | awk '{split($4,a,\"/\"); print a[1]; exit}'");
        boolean web = false, smb = false, desktop = false;
        String ip = "";
        for (String line : response.split("\\r?\\n")) {
            if (line.startsWith("web: ")) web = !line.equals("web: stopped");
            if (line.startsWith("gosamba: ")) smb = !line.equals("gosamba: stopped");
            if (line.equals("on")) desktop = true;
            if (line.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) ip = line;
        }
        return new State(ip, desktop, smb, web);
    }

    private void provision(String target) throws Exception {
        String revision;
        try (InputStream input = app.getAssets().open("payload-revision.txt")) {
            revision = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
        if (!revision.matches("[a-f0-9]{64}")) throw new IOException("Invalid app payload.");
        String current = controller.shell(target, "if [ -f " + BASE + "/apk-revision ]; then cat " + BASE + "/apk-revision; fi");
        if (revision.equals(current)) return;

        app.getExternalFilesDir(null);
        String stage = BASE + "/apk-" + UUID.randomUUID().toString();
        String names = String.join(" ", PAYLOAD);
        controller.shell(target, "umask 077; mkdir -p " + stage + " && chmod 700 " + BASE + " " + stage);
        try (ZipInputStream bundle = new ZipInputStream(app.getAssets().open("server.zip"))) {
            Set<String> missing = new HashSet<>(Arrays.asList(PAYLOAD));
            for (ZipEntry entry; (entry = bundle.getNextEntry()) != null;) {
                String name = entry.getName();
                if (entry.isDirectory() || !missing.remove(name)) continue;
                File copy = File.createTempFile("payload-", ".tmp", app.getCacheDir());
                try {
                    try (FileOutputStream output = new FileOutputStream(copy)) {
                        bundle.transferTo(output);
                    }
                    controller.push(target, copy, stage + "/" + name);
                } finally { copy.delete(); }
            }
            if (!missing.isEmpty()) throw new IOException("The release APK is missing server files. Reinstall it.");
            controller.shell(target, "set -eu; umask 077; cd " + BASE +
                    "; if [ -f lan-services.sh ]; then sh lan-services.sh stop; fi; cd " + stage +
                    "; chmod 700 dexbrowser web gosamba; chmod 400 HeadlessDex.jar DexFrame.jar DexVideo.jar DexInput.jar" +
                    "; mv " + names + " " + BASE + "/; cd " + BASE +
                    "; rmdir " + stage + "; sh provision.sh; printf '%s\\n' " + revision + " > apk-revision");
        } catch (Exception error) {
            try { controller.shell(target, "if [ -d " + stage + " ]; then cd " + stage + "; rm -f " + names + "; cd /; rmdir " + stage + "; fi"); }
            catch (Exception ignored) {}
            throw error;
        }
    }
}
