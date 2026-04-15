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
import android.util.Log;

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
    private static final int SAFETY_TIMEOUT_MS = 20000; // 20s safety net
    private static final String WA_PACKAGE = "com.whatsapp";

    public static boolean isRunning = false;

    private ScheduledExecutorService scheduler;
    private OkHttpClient httpClient;
    private Handler mainHandler;
    private boolean callInProgress = false;

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

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollForCalls, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        return START_STICKY;
    }

    private void pollForCalls() {
        if (callInProgress) return;

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
                return;
            }

            JSONObject call = json.getJSONObject("call");
            String phone = call.getString("phone");
            String requestId = call.getString("id");

            Log.d(TAG, "Got call request: " + phone + " (id: " + requestId + ")");
            makeWhatsAppCall(phone, requestId);

        } catch (Exception e) {
            Log.e(TAG, "Poll error: " + e.getMessage());
        }
    }

    private void makeWhatsAppCall(String phone, String requestId) {
        callInProgress = true;

        try {
            // Strip + and non-digit chars for wa.me URL
            String waNumber = phone.replaceAll("[^0-9]", "");

            // Set up HangUpService callback
            if (HangUpService.isAvailable()) {
                HangUpService.getInstance().prepareForCall(success -> {
                    mainHandler.post(() -> {
                        if (!callInProgress) return; // Already handled by safety timeout
                        Log.d(TAG, "Call completed via HangUpService (success=" + success + ")");
                        completeCall(requestId, success);
                    });
                });
            }

            // Open WhatsApp chat
            Intent waIntent = new Intent(Intent.ACTION_VIEW);
            waIntent.setData(Uri.parse("https://wa.me/" + waNumber));
            waIntent.setPackage(WA_PACKAGE);
            waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Check if screen is on or off
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean screenOn = pm.isInteractive();

            if (!screenOn) {
                // Screen OFF — use CallActivity to wake screen then open WhatsApp
                Intent wakeIntent = new Intent(this, CallActivity.class);
                wakeIntent.putExtra("waNumber", waNumber);
                wakeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                Notification callNotif = new Notification.Builder(this, "sonne_call_channel")
                        .setContentTitle("Sonne Caller")
                        .setContentText("Appel WhatsApp: " + phone)
                        .setSmallIcon(android.R.drawable.ic_menu_call)
                        .setFullScreenIntent(
                                android.app.PendingIntent.getActivity(
                                        this, 0, wakeIntent,
                                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                                ), true)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setOngoing(true)
                        .build();

                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(2, callNotif);

                try { startActivity(wakeIntent); } catch (Exception e) {
                    Log.e(TAG, "Could not start CallActivity: " + e.getMessage());
                }
            } else {
                // Screen ON — open WhatsApp directly
                startActivity(waIntent);
            }

            Log.d(TAG, "WhatsApp chat opened for " + waNumber + " (screen=" + (screenOn ? "ON" : "OFF") + ")");
            updateNotification("Appel WhatsApp: " + phone);

            // SAFETY TIMEOUT: force end after 20s
            mainHandler.postDelayed(() -> {
                if (!callInProgress) return;
                Log.d(TAG, "Safety timeout (20s) — forcing end");

                if (HangUpService.isAvailable()) {
                    HangUpService.getInstance().forceEndCall();
                }

                mainHandler.postDelayed(() -> {
                    if (!callInProgress) return;
                    completeCall(requestId, true);
                }, 3000);

            }, SAFETY_TIMEOUT_MS);

        } catch (Exception e) {
            Log.e(TAG, "makeWhatsAppCall error: " + e.getMessage());
            completeCall(requestId, false);
        }
    }

    private void completeCall(String requestId, boolean success) {
        callInProgress = false;
        reportCallDone(requestId, success);
        updateNotification("En attente d'appels...");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(2);
    }

    private void reportCallDone(String requestId, boolean success) {
        new Thread(() -> {
            try {
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("id", requestId);
                json.put("success", success);
                if (!success) json.put("message", "L'appel WhatsApp n'a pas pu aboutir");

                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(BuildConfig.API_URL + "/api/call-done")
                        .addHeader("x-api-secret", BuildConfig.API_SECRET)
                        .post(okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json")))
                        .build();

                httpClient.newCall(request).execute();
                Log.d(TAG, "Reported call done: " + requestId + " (success=" + success + ")");
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
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sonne Caller Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Service d'appel automatique");

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
