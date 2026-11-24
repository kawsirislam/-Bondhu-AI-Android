package com.bondhuai.app;

import android.content.SharedPreferences;
import android.net.Uri;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        SharedPreferences prefs = getSharedPreferences("bondhu_native_prefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();
        MainActivity.onTokenRefreshedFromService(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Map<String, String> data = remoteMessage.getData();

        String type = data.getOrDefault("type", "message");
        String callId = data.getOrDefault("callId", "");

        if ("call_ended".equals(type) || "call_cancelled".equals(type)) {
            if (!callId.isEmpty()) {
                BondhuNotificationHelper.cancelNotification(this, "call_" + callId);
            }
            return;
        }

        boolean isCall = type.contains("call");
        if (!isCall && MainActivity.isAppInForeground()) {
            // Avoid duplicate OS notification while Bondhu AI is actively open on screen;
            // background WebSocket / foreground UI handles it cleanly.
            return;
        }

        String senderName = data.getOrDefault("senderName", "");
        String senderUsername = data.getOrDefault("senderUsername", "");
        String senderId = data.getOrDefault("senderId", "");
        String groupId = data.getOrDefault("groupId", "");
        String groupName = data.getOrDefault("groupName", "");
        String callerName = data.getOrDefault("callerName", "");
        String text = data.getOrDefault("text", "");

        String senderDisplay = !senderName.isEmpty()
                ? senderName
                : (!senderUsername.isEmpty() ? "@" + senderUsername : "Bondhu User");

        String title;
        String body;

        if (remoteMessage.getNotification() != null && remoteMessage.getNotification().getTitle() != null) {
            title = remoteMessage.getNotification().getTitle();
            body = remoteMessage.getNotification().getBody() != null
                    ? remoteMessage.getNotification().getBody()
                    : text;
        } else if (isCall) {
            String displayCaller = !callerName.isEmpty() ? callerName : senderDisplay;
            title = (type.contains("video") ? "📹 " : "📞 ") + displayCaller + " • Bondhu AI";
            body = type.contains("video")
                    ? "Incoming Video Call... (রিসিভ বা ডিক্লাইন করুন)"
                    : "Incoming Audio Call... (রিসিভ বা ডিক্লাইন করুন)";
        } else if (!groupId.isEmpty()) {
            title = (!groupName.isEmpty() ? groupName : "Group Chat") + " • Bondhu AI";
            body = senderDisplay + ": " + (!text.isEmpty() ? text : "নতুন মেসেজ পাঠিয়েছেন");
        } else {
            title = senderDisplay + " • Bondhu AI";
            body = !text.isEmpty() ? text : "আপনাকে একটি নতুন মেসেজ পাঠিয়েছেন।";
        }

        String targetUrl = data.getOrDefault("url", "");
        if (targetUrl.isEmpty()) {
            if (!callId.isEmpty()) {
                targetUrl = "/?callId=" + Uri.encode(callId) + "&callAction=open";
            } else if (!groupId.isEmpty()) {
                targetUrl = "/?groupId=" + Uri.encode(groupId);
            } else if (!senderId.isEmpty()) {
                targetUrl = "/?chatWith=" + Uri.encode(senderId);
            } else {
                targetUrl = "/";
            }
        }

        String tag = isCall
                ? "call_" + callId
                : (!groupId.isEmpty() ? "bondhu_group_" + groupId : "bondhu_msg_" + senderId);

        BondhuNotificationHelper.showNotification(this, title, body, isCall, tag, targetUrl);
    }
}
