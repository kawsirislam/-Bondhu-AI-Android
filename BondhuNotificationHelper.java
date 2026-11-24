package com.bondhuai.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public final class BondhuNotificationHelper {
    public static final String CHANNEL_MESSAGES = "bondhu_messages";
    public static final String CHANNEL_CALLS = "bondhu_calls";

    private BondhuNotificationHelper() {}

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Uri msgSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes msgAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel msgChannel = new NotificationChannel(
                CHANNEL_MESSAGES,
                "Bondhu AI Messages",
                NotificationManager.IMPORTANCE_HIGH
        );
        msgChannel.setDescription("Incoming chat and group message notifications");
        msgChannel.setSound(msgSound, msgAttr);
        msgChannel.enableVibration(true);
        msgChannel.setVibrationPattern(new long[]{200, 100, 200});
        manager.createNotificationChannel(msgChannel);

        Uri callSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        AudioAttributes callAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel callChannel = new NotificationChannel(
                CHANNEL_CALLS,
                "Bondhu AI Calls",
                NotificationManager.IMPORTANCE_HIGH
        );
        callChannel.setDescription("Incoming audio and video call notifications");
        callChannel.setSound(callSound, callAttr);
        callChannel.enableVibration(true);
        callChannel.setVibrationPattern(new long[]{500, 250, 500, 250, 500});
        manager.createNotificationChannel(callChannel);
    }

    public static void cancelNotification(Context context, String tag) {
        if (tag == null || tag.isEmpty()) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(tag, 1001);
        }
    }

    public static void showNotification(
            Context context,
            String title,
            String body,
            boolean isCall,
            String tag,
            String targetUrl
    ) {
        ensureChannels(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (targetUrl != null && !targetUrl.isEmpty()) {
            intent.putExtra("bondhu_target_url", targetUrl);
        }

        int requestCode = (tag != null && !tag.isEmpty()) ? tag.hashCode() : (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String channelId = isCall ? CHANNEL_CALLS : CHANNEL_MESSAGES;
        Uri soundUri = RingtoneManager.getDefaultUri(
                isCall ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title != null && !title.isEmpty() ? title : "Bondhu AI")
                .setContentText(body != null && !body.isEmpty() ? body : "নতুন বার্তা এসেছে")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(isCall ? NotificationCompat.CATEGORY_CALL : NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(!isCall)
                .setSound(soundUri)
                .setDefaults(NotificationCompat.DEFAULT_LIGHTS | NotificationCompat.DEFAULT_VIBRATE)
                .setContentIntent(pendingIntent);

        if (isCall) {
            builder.setOngoing(true);
        }

        String safeTag = (tag != null && !tag.isEmpty()) ? tag : "bondhu_default";
        manager.notify(safeTag, 1001, builder.build());
    }
}
