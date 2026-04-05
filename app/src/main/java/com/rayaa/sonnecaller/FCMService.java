package com.rayaa.sonnecaller;

import android.content.Intent;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "SonneFCM";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();

        if ("RING".equals(data.get("type"))) {
            Log.d(TAG, "Received RING push for: " + data.get("phone"));

            // Wake up the service if not running
            if (!CallerService.isRunning) {
                Intent intent = new Intent(this, CallerService.class);
                startForegroundService(intent);
            }
            // The service will pick up the call via polling
        }
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "New FCM token: " + token);
        // TODO: Send this token to your server or save it
        // For now, copy it from logcat and set as ANDROID_DEVICE_TOKEN env var
    }
}
