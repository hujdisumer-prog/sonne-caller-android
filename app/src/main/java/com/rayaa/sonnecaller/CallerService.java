package com.rayaa.sonnecaller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

public class CallerService extends Service {

    private static final String TAG = "SonneCaller";
    private static final String CHANNEL_ID = "sonne_caller_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final int RING_DURATION_MS = 25000; // 25 seconds

    public static boolean isRunning = false;

    private ScheduledExecutorService scheduler;
    private OkHttpClient httpClient;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sonne Caller")
                .setContentText("En attente d'appels...")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        isRunning = true;

        // Start polling for calls
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollForCalls, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        return START_STICKY;
    }

    private void pollForCalls() {
        try {
            Request request = new Request.Builder()
                    .url(BuildConfig.API_URL + "/api/next-call")
                    .addHeader("x-api-secret", BuildConfig.API_SECRET)
                    .get()
                    .build();

            Response response = httpClient.newCall(request).execute();
            String body = response.body().string();
            JSONObject json = new JSONObject(body);

            if (json.isNull("call")) {
                return; // No call in queue
            }

            JSONObject call = json.getJSONObject("call");
            String phone = call.getString("phone");
            String requestId = call.getString("id");

            Log.d(TAG, "Got call request: " + phone + " (id: " + requestId + ")");
            makeCall(phone, requestId);

        } catch (Exception e) {
            Log.e(TAG, "Poll error: " + e.getMessage());
        }
    }

    private void makeCall(String phone, String requestId) {
        try {
            // Launch CallActivity to place the call
            Intent callActivityIntent = new Intent(this, CallActivity.class);
            callActivityIntent.putExtra("phone", phone);
            callActivityIntent.putExtra("requestId", requestId);
            callActivityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            // Check if screen is on
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean screenOn = pm.isInteractive();

            if (screenOn) {
                // Screen is ON — call directly from service (no activity needed)
                Log.d(TAG, "Screen ON — calling directly from service");
                String callNumber = "#31#" + phone;
                Intent callIntent = new Intent(Intent.ACTION_CALL);
                callIntent.setData(android.net.Uri.parse("tel:" + android.net.Uri.encode(callNumber)));
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(callIntent);

                updateNotification("Appel en cours: " + phone);

                // Hang up after 25 seconds
                mainHandler.postDelayed(() -> {
                    try {
                        android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(TELECOM_SERVICE);
                        if (tm != null) tm.endCall();
                    } catch (Exception e) {
                        Log.e(TAG, "Hang up error: " + e.getMessage());
                    }
                    reportCallDone(requestId, true);
                    updateNotification("En attente d'appels...");
                }, RING_DURATION_MS);
            } else {
                // Screen is OFF (locked) — use fullscreen notification
                Log.d(TAG, "Screen OFF — using fullscreen intent");
                Notification callNotif = new Notification.Builder(this, "sonne_call_channel")
                        .setContentTitle("Sonne Caller")
                        .setContentText("Appel en cours: " + phone)
                        .setSmallIcon(android.R.drawable.ic_menu_call)
                        .setFullScreenIntent(
                            android.app.PendingIntent.getActivity(
                                this, 0, callActivityIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                            ), true)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setOngoing(true)
                        .build();

                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(2, callNotif);

                // Also try direct launch as backup
                try {
                    startActivity(callActivityIntent);
                } catch (Exception bgEx) {
                    Log.w(TAG, "Backup startActivity blocked: " + bgEx.getMessage());
                }
            }

            Log.d(TAG, "CallActivity launched for " + phone);
            updateNotification("Appel en cours: " + phone);

            // Reset notification after call duration
            mainHandler.postDelayed(() -> {
                updateNotification("En attente d'appels...");
                NotificationManager nm2 = getSystemService(NotificationManager.class);
                nm2.cancel(2);
            }, RING_DURATION_MS + 2000);

        } catch (Exception e) {
            Log.e(TAG, "makeCall error: " + e.getMessage());
        }
    }

    private void reportCallDone(String requestId, boolean success) {
        new Thread(() -> {
            try {
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("id", requestId);
                json.put("success", success);
                if (!success) json.put("message", "L'appel n'a pas pu aboutir");

                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(BuildConfig.API_URL + "/api/call-done")
                        .addHeader("x-api-secret", BuildConfig.API_SECRET)
                        .post(okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json")))
                        .build();

                httpClient.newCall(request).execute();
                Log.d(TAG, "Reported call done: " + requestId);
            } catch (Exception e) {
                Log.e(TAG, "Report error: " + e.getMessage());
            }
        }).start();
    }

    private void updateNotification(String text) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sonne Caller")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        // Canal normal pour le service
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sonne Caller Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Service d'appel automatique");

        // Canal haute priorité pour les appels (perce le mode arrière-plan Samsung)
        NotificationChannel callChannel = new NotificationChannel(
                "sonne_call_channel",
                "Appels Sonne Caller",
                NotificationManager.IMPORTANCE_HIGH
        );
        callChannel.setDescription("Notifications d'appels");
        callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
        manager.createNotificationChannel(callChannel);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (scheduler != null) scheduler.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
