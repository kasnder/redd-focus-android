package net.kollnig.greasemilkyway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Manages the persistent notification that allows users to activate the element picker
 * from other apps.
 */
public class ElementPickerNotification {
    private static final String TAG = "ElementPickerNotification";
    public static final String CHANNEL_ID = "element_picker_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START_PICKER = "net.kollnig.greasemilkyway.ACTION_START_PICKER";
    public static final String ACTION_STOP_PICKER = "net.kollnig.greasemilkyway.ACTION_STOP_PICKER";

    private final Context context;
    private final NotificationManager notificationManager;

    public ElementPickerNotification(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.picker_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(context.getString(R.string.picker_channel_description));
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Shows the persistent notification with the "Pick element" action.
     */
    public void showNotification() {
        Intent pickerIntent = new Intent(ACTION_START_PICKER);
        pickerIntent.setPackage(context.getPackageName());
        PendingIntent pickerPendingIntent = PendingIntent.getBroadcast(
                context, 0, pickerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.picker_notification_title))
                .setContentText(context.getString(R.string.picker_notification_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pickerPendingIntent)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * Shows a notification indicating picker mode is active, with a "Stop" action.
     */
    public void showPickerActiveNotification() {
        Intent stopIntent = new Intent(ACTION_STOP_PICKER);
        stopIntent.setPackage(context.getPackageName());
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.picker_active_title))
                .setContentText(context.getString(R.string.picker_active_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(stopPendingIntent)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * Removes the notification.
     */
    public void cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
