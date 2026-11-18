package org.galaxylan.desktop;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

public final class PairNotification extends BroadcastReceiver {
    static final String PREFS = "desktop-setup";
    static final String PAIRED = "paired";
    private static final String CHANNEL = "pairing";
    private static final String REPLY = "pairing-details";
    private static final int NOTIFICATION = 31;

    static boolean available(Context context) {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static void prompt(Context context) {
        show(context, "Pair Galaxy LAN Desktop", "In Settings, open the pairing dialog. Reply here with the port and code.", true);
    }

    private static void show(Context context, String title, String text, boolean reply) {
        if (!available(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Setup and pairing", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.desktop).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
                .setAutoCancel(!reply);
        if (reply) {
            PendingIntent response = PendingIntent.getBroadcast(context, 1,
                    new Intent(context, PairNotification.class),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput input = new RemoteInput.Builder(REPLY).setLabel("Pairing port and six-digit code").build();
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_edit,
                    "Enter port and code", response).addRemoteInput(input).build());
        }
        manager.notify(NOTIFICATION, builder.build());
    }

    @Override public void onReceive(Context context, Intent intent) {
        Bundle input = RemoteInput.getResultsFromIntent(intent);
        CharSequence reply = input == null ? null : input.getCharSequence(REPLY);
        if (reply == null) return;
        String[] fields = reply.toString().trim().split("\\s+");
        if (fields.length != 2 || !fields[0].matches("[0-9]{1,5}") || !fields[1].matches("[0-9]{6}")) {
            show(context, "Enter the port and code", "Separate the two numbers with a space. Use the port shown in the pairing dialog.", true);
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                new WirelessController(context).pair(Integer.parseInt(fields[0]), fields[1]);
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply();
                show(context, "Phone paired", "Tap to open Galaxy LAN Desktop and finish setup.", false);
            } catch (Exception error) {
                show(context, "Pairing failed", "Open a fresh pairing dialog in Settings and try again.", true);
            } finally { pending.finish(); }
        }, "galaxy-pairing").start();
    }
}
