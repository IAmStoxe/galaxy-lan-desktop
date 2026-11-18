package org.galaxylan.desktop;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopActivity extends Activity {
    private static final int NOTIFICATIONS = 1;
    private static final int BACKGROUND = 0xff10151c;
    private static final int CARD = 0xff1c2530;
    private static final int MUTED = 0xffb8c9db;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final EnumMap<LanServices.Service, ServiceRow> rows = new EnumMap<>(LanServices.Service.class);
    private LanServices services;
    private LinearLayout root, setup, serviceList;
    private TextView status;
    private Button refresh, pair;
    private EditText port, code;
    private boolean busy, updating;
    private LanServices.State state;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        services = new LanServices(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        root = column();
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = label("Galaxy LAN Desktop", 28, 0xfff2f6fd);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        status = label("", 14, MUTED);
        root.addView(status);

        if (Build.VERSION.SDK_INT != 34 || !Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            status.setText("This build requires Android 14 on an ARM64 device.");
            return;
        }

        makeSetup();
        serviceList = column();
        root.addView(serviceList);
        addService(LanServices.Service.DESKTOP, "Browser desktop", "Open in a browser on the same Wi-Fi. Username: dex.");
        addService(LanServices.Service.SMB, "SMB share", "Share Files as user files. Use an SMB client that supports port 4450.");
        addService(LanServices.Service.WEB, "Static website", "Devices on your Wi-Fi can read files you put in the web directory.");
        refresh = button(serviceList, "Refresh status", () -> change(null, false));
        button(serviceList, "Add DeX Browser tile", () ->
                getSystemService(StatusBarManager.class).requestAddTileService(
                        new ComponentName(this, BrowserTile.class), "DeX Browser",
                        Icon.createWithResource(this, R.drawable.desktop), getMainExecutor(), result -> {}));
        button(serviceList, "Pair again", () -> {
            setup.setVisibility(View.VISIBLE);
            setup.requestFocus();
        });
        button(serviceList, "Developer options", this::openSettings);
        button(root, "Open-source licenses", this::showLicenses);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (setup != null && paired() && !busy) change(null, false);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void makeSetup() {
        setup = card(root);
        setup.addView(label("Pair this phone", 19, 0xfff2f6fd));
        setup.addView(label("In Developer options, turn on Wireless debugging for this Wi-Fi. Open Pair device with pairing code, then enter the port and code in the app’s notification while the pairing dialog is open.", 15, MUTED));
        button(setup, "Pair using a notification", this::pairUsingNotification);
        LinearLayout manual = column();
        setup.addView(manual);
        manual.setVisibility(View.GONE);
        button(setup, "Pair using split screen", () -> manual.setVisibility(View.VISIBLE));
        manual.addView(label("Open this app and Settings in split screen. Leave the pairing dialog open while you enter the two numbers here.", 14, MUTED));
        button(manual, "Open Developer options", this::openSettings);
        port = field(manual, "Pairing port", InputType.TYPE_CLASS_NUMBER);
        code = field(manual, "Six-digit code", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pair = button(manual, "Pair and finish setup", this::pairManually);
    }

    private void pairUsingNotification() {
        if (PairNotification.available(this)) {
            PairNotification.prompt(this);
            openSettings();
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            status.setText("Notifications are disabled. Use split screen to pair.");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != NOTIFICATIONS) return;
        if (PairNotification.available(this)) pairUsingNotification();
        else status.setText("Use split screen to pair without notifications.");
    }

    private void pairManually() {
        String portText = port.getText().toString().trim();
        String pin = code.getText().toString().trim();
        if (!portText.matches("[0-9]{1,5}") || !pin.matches("[0-9]{6}")) {
            status.setText("Enter the port and six-digit code shown in the pairing dialog.");
            return;
        }
        code.setText("");
        work("Pairing and setting up…", () -> {
            new WirelessController(this).pair(Integer.parseInt(portText), pin);
            getSharedPreferences(PairNotification.PREFS, MODE_PRIVATE).edit().putBoolean(PairNotification.PAIRED, true).apply();
            return services.refresh();
        }, this::showState);
    }

    private void addService(LanServices.Service service, String title, String detail) {
        LinearLayout card = card(serviceList);
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(18);
        toggle.setShowText(false);
        toggle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(toggle);
        card.addView(label(detail, 14, MUTED));
        TextView address = label("Off", 14, 0xffd2e7ff);
        address.setTextIsSelectable(true);
        card.addView(address);
        LinearLayout actions = new LinearLayout(this);
        card.addView(actions);
        Button copy = button(actions, "Copy address", () -> copy("LAN address", address(service), false));
        Button password = service == LanServices.Service.WEB ? null :
                button(actions, "Show password", () -> work("Reading password…", () -> services.access(service),
                        value -> showPassword(service, value)));
        rows.put(service, new ServiceRow(toggle, address, copy, password));
        toggle.setOnCheckedChangeListener((view, enabled) -> {
            if (!updating) change(service, enabled);
        });
    }

    private void change(LanServices.Service service, boolean enable) {
        work(service == null ? "Checking services…" : enable ? "Starting service…" : "Stopping service…",
                () -> service == null ? services.refresh() : services.set(service, enable), this::showState);
    }

    private void showState(LanServices.State result) {
        state = result;
        status.setText(result.ip.isEmpty() ? "Connect this phone to Wi-Fi." : "On Wi-Fi · " + result.ip);
        setup.setVisibility(View.GONE);
    }

    private void render() {
        updating = true;
        boolean paired = paired();
        serviceList.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (!paired && !busy && status.getText().length() == 0) status.setText("Pair once to start services from this phone.");
        pair.setEnabled(!busy);
        refresh.setEnabled(!busy);
        for (LanServices.Service service : LanServices.Service.values()) {
            ServiceRow row = rows.get(service);
            boolean running = state != null && state.running(service);
            row.toggle.setChecked(running);
            row.toggle.setEnabled(!busy && state != null);
            row.address.setText(running ? address(service) : state == null ? "Status unavailable" : "Off");
            row.copy.setEnabled(!busy && running && !state.ip.isEmpty());
            if (row.password != null) row.password.setEnabled(!busy && running);
        }
        updating = false;
    }

    private String address(LanServices.Service service) {
        if (state == null || state.ip.isEmpty()) return "";
        String prefix = service == LanServices.Service.SMB ? "smb://" : service == LanServices.Service.DESKTOP ? "https://" : "http://";
        return prefix + state.ip + ":" + service.port + (service == LanServices.Service.SMB ? "/Files" : "/");
    }

    private void showPassword(LanServices.Service service, LanServices.Access access) {
        status.setText(state == null ? "" : "On Wi-Fi · " + state.ip);
        LinearLayout details = column();
        details.setPadding(dp(22), dp(12), dp(22), dp(12));
        TextView password = label(access.password, 16, 0xfff2f6fd);
        password.setTypeface(Typeface.MONOSPACE);
        password.setTextIsSelectable(true);
        details.addView(password);
        if (!access.fingerprint.isEmpty()) {
            details.addView(label("The first browser visit shows a certificate warning. Check this SHA-256 fingerprint against the browser’s certificate before continuing.", 14, MUTED));
            TextView fingerprint = label(access.fingerprint, 13, 0xfff2f6fd);
            fingerprint.setTextIsSelectable(true);
            details.addView(fingerprint);
        }
        new AlertDialog.Builder(this)
                .setTitle(service == LanServices.Service.SMB ? "SMB password" : "Desktop password")
                .setView(details)
                .setPositiveButton("Copy password", (dialog, which) -> copy("Password", access.password, true))
                .setNegativeButton("Close", null).show();
    }

    private void copy(String title, String value, boolean sensitive) {
        ClipData clip = ClipData.newPlainText(title, value);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(this, title + " copied", Toast.LENGTH_SHORT).show();
    }

    private boolean paired() {
        return getSharedPreferences(PairNotification.PREFS, MODE_PRIVATE).getBoolean(PairNotification.PAIRED, false);
    }

    private void openSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }

    private void showLicenses() {
        StringBuilder text = new StringBuilder();
        try {
            for (String file : getAssets().list("licenses")) {
                try (InputStream input = getAssets().open("licenses/" + file);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    input.transferTo(output);
                    text.append(file).append("\n\n").append(new String(output.toByteArray(), StandardCharsets.UTF_8)).append("\n\n");
                }
            }
        } catch (Exception e) { text.append("Licenses could not be opened."); }
        new AlertDialog.Builder(this).setTitle("Open-source licenses").setMessage(text.toString()).setPositiveButton("Close", null).show();
    }

    interface Operation<T> { T run() throws Exception; }
    interface Completion<T> { void accept(T result); }

    private <T> void work(String message, Operation<T> operation, Completion<T> completion) {
        if (busy) return;
        busy = true;
        status.setText(message);
        render();
        worker.execute(() -> {
            try {
                T result = operation.run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    completion.accept(result);
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    String messageText = e.getMessage();
                    status.setText(messageText == null || messageText.length() > 180 ?
                            "Could not reach the phone’s controller. Check Wireless debugging and retry." : messageText);
                    render();
                });
            }
        });
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout layout = column();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        layout.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(12));
        layout.setBackground(background);
        parent.addView(layout, params);
        return layout;
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setPadding(0, dp(4), 0, dp(5));
        return label;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private EditText field(LinearLayout parent, String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        return edit;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class ServiceRow {
        final Switch toggle;
        final TextView address;
        final Button copy, password;
        ServiceRow(Switch toggle, TextView address, Button copy, Button password) {
            this.toggle = toggle;
            this.address = address;
            this.copy = copy;
            this.password = password;
        }
    }
}
