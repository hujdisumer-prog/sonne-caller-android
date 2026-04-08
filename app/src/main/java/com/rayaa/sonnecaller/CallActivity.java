package com.rayaa.sonnecaller;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.WindowManager;

/**
 * Transparent activity that wakes the screen, places the call, hangs up after 25s, then finishes.
 * This bypasses Samsung's background activity restrictions.
 */
public class CallActivity extends Activity {

    private static final String TAG = "SonneCaller";
    private static final int RING_DURATION_MS = 25000;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Wake screen and show over lock screen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        // Acquire wake lock
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "sonnecaller:call"
        );
        wakeLock.acquire(30000); // 30 sec max

        String phone = getIntent().getStringExtra("phone");
        String requestId = getIntent().getStringExtra("requestId");

        if (phone != null) {
            makeCall(phone, requestId);
        } else {
            Log.e(TAG, "CallActivity: no phone number");
            finish();
        }
    }

    private void makeCall(String phone, String requestId) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CallActivity: no CALL_PHONE permission");
            finish();
            return;
        }

        try {
            String callNumber = "#31#" + phone;
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + Uri.encode(callNumber)));
            startActivity(callIntent);

            Log.d(TAG, "CallActivity: call started to " + phone);

            // Hang up after 25 seconds
            new Handler().postDelayed(() -> {
                hangUp();
                reportCallDone(requestId, true);
                finish();
            }, RING_DURATION_MS);

        } catch (Exception e) {
            Log.e(TAG, "CallActivity: call error: " + e.getMessage());
            reportCallDone(requestId, false);
            finish();
        }
    }

    private void hangUp() {
        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null) {
                telecomManager.endCall();
            }
        } catch (Exception e) {
            Log.e(TAG, "CallActivity: hang up error: " + e.getMessage());
        }
    }

    private void reportCallDone(String requestId, boolean success) {
        if (requestId == null) return;
        // Delegate to CallerService's static method
        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("id", requestId);
                json.put("success", success);
                if (!success) json.put("message", "L'appel n'a pas pu aboutir");

                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(BuildConfig.API_URL + "/api/call-done")
                        .addHeader("x-api-secret", BuildConfig.API_SECRET)
                        .post(okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json")))
                        .build();

                client.newCall(request).execute();
                Log.d(TAG, "CallActivity: reported call done: " + requestId);
            } catch (Exception e) {
                Log.e(TAG, "CallActivity: report error: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
