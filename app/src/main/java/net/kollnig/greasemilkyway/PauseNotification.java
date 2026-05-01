package net.kollnig.greasemilkyway;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class PauseNotification {
    private static final String CHANNEL_ID = "pause_channel";
    private static final int NOTIFICATION_ID = 1002;

    private final Context context;
    private final NotificationManager notificationManager;
    private final ServiceConfig config;

    public PauseNotification(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.config = new ServiceConfig(context);
        createNotificationChannel();
    }

    public void show(String packageName, String appLabel) {
        if (notificationManager == null || !canPostNotifications()) {
            return;
        }

        int durationMins = config.getPauseDurationMins();
        Intent pauseIntent = new Intent(context, MainActivity.class);
        pauseIntent.setAction(MainActivity.ACTION_PAUSE_PACKAGE);
        pauseIntent.putExtra(MainActivity.EXTRA_PACKAGE_NAME, packageName);
        pauseIntent.putExtra(MainActivity.EXTRA_RETURN_TO_PACKAGE, packageName);
        pauseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pausePendingIntent = PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setLargeIcon(getAppIcon(packageName))
                .setContentTitle(context.getString(R.string.pause_notification_title, appLabel))
                .setContentText(context.getString(R.string.pause_notification_text, appLabel))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_launcher_foreground,
                        context.getString(R.string.pause_notification_action, durationMins),
                        pausePendingIntent)
                .setContentIntent(pausePendingIntent)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    public void cancel() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private void createNotificationChannel() {
        if (notificationManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.pause_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.pause_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Bitmap getAppIcon(String packageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            Drawable icon = packageManager.getApplicationIcon(appInfo);
            int size = context.getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);
            return bitmap;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
