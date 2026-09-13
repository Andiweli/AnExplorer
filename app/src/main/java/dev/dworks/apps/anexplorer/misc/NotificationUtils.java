package dev.dworks.apps.anexplorer.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import dev.dworks.apps.anexplorer.DocumentsActivity;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.receiver.ConnectionsReceiver;
import dev.dworks.apps.anexplorer.setting.SettingsActivity;

import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_STOP_FTPSERVER;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_ROOT;

/** Notification helpers for the built-in FTP foreground service. */
public final class NotificationUtils {

    public static final int FTP_NOTIFICATION_ID = 916;
    public static final String FTP_CHANNEL_ID = "anexplorer_ftp_server";

    private NotificationUtils() {
    }

    public static Notification buildFtpNotification(Context context, Intent intent) {
        ensureFtpChannel(context);

        RootInfo root = null;
        if (intent != null && intent.getExtras() != null) {
            root = intent.getExtras().getParcelable(EXTRA_ROOT);
        }

        CharSequence contentTitle = getString(context, R.string.ftp_notif_title);
        String ftpAddress = ConnectionUtils.getFTPAddress(context);
        CharSequence contentText = String.format(
                getString(context, R.string.ftp_notif_text),
                ftpAddress == null ? "" : ftpAddress);
        CharSequence stopText = getString(context, R.string.ftp_notif_stop_server);

        Intent notificationIntent = new Intent(context, DocumentsActivity.class);
        if (root != null) {
            notificationIntent.setData(root.getUri());
        }
        if (intent != null && intent.getExtras() != null) {
            notificationIntent.putExtras(intent.getExtras());
        }
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int immutableUpdateFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        int immutableOneShotFlags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            immutableUpdateFlags |= PendingIntent.FLAG_IMMUTABLE;
            immutableOneShotFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, notificationIntent, immutableUpdateFlags);

        Intent stopIntent = new Intent(context, ConnectionsReceiver.class)
                .setAction(ACTION_STOP_FTPSERVER);
        if (intent != null && intent.getExtras() != null) {
            stopIntent.putExtras(intent.getExtras());
        }
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                context, 1, stopIntent, immutableOneShotFlags);

        return new NotificationCompat.Builder(context, FTP_CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(SettingsActivity.getPrimaryColor())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_action_stop, stopText, stopPendingIntent)
                .setShowWhen(false)
                .build();
    }

    public static void createFtpNotification(Context context, Intent intent, int notificationId) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notificationId, buildFtpNotification(context, intent));
    }

    public static void removeNotification(Context context, int notificationId) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(notificationId);
    }

    private static void ensureFtpChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(FTP_CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                FTP_CHANNEL_ID,
                context.getString(R.string.ftp_notif_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.ftp_notif_starting));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static String getString(Context context, int id) {
        return context.getResources().getString(id);
    }
}
