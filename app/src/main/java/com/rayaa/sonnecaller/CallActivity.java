package com.rayaa.sonnecaller;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;

/**
 * Places a call, lets it ring, and hangs up:
 * - After 25 seconds max (nobody answers)
 * - When the person answers (detected by audio routing change)
 * - When voicemail picks up
 */
public class CallActivity extends Activity {

    private static final String TAG = "SonneCaller";
    private static final int RING_DURATION_MS = 25000;
    private static final int MIN_RING_TIME_MS = 5000; // Let it ring at least 5 sec

    private PowerManager.WakeLock wakeLock;
    private boolean callEnded = false;
    private String currentRequestId;
    private Handler handler;
    private long callStartTime;
    private Runnable checkAnsweredRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler();

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "sonnecaller:call"
        );
        wakeLock.acquire(30000);

        String phone = getIntent().getStringExtra("phone");
        currentRequestId = getIntent().getStringExtra("requestId");

        if (phone != null) {
            makeCall(phone);
        } else {
            Log.e(TAG, "CallActivity: no phone number");
            finish();
        }
    }

    private void makeCall(String phone) {
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

            callStartTime = System.currentTimeMillis();
            Log.d(TAG, "CallActivity: call started to " + phone);

            // Check every 2 seconds if the call was answered
            // Detect answer by checking if audio mode changes to MODE_IN_COMMUNICATION
            checkAnsweredRunnable = new Runnable() {
                @Override
                public void run() {
                    if (callEnded) return;

                    long elapsed = System.currentTimeMillis() - callStartTime;
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    int audioMode = am.getMode();

                    Log.d(TAG, "Check: elapsed=" + elapsed + "ms, audioMode=" + audioMode);

                    // AudioManager.MODE_IN_COMMUNICATION (3) = call answered
                    // Only hang up if we've let it ring at least MIN_RING_TIME_MS
                    if (elapsed > MIN_RING_TIME_MS && audioMode == AudioManager.MODE_IN_COMMUNICATION) {
                        Log.d(TAG, "Call answered (audio in communication), hanging up");
                        endCall();
                        return;
                    }

                    // Max duration reached
                    if (elapsed >= RING_DURATION_MS) {
                        Log.d(TAG, "Max ring duration reached, hanging up");
                        endCall();
                        return;
                    }

                    // Check again in 1 second
                    handler.postDelayed(this, 1000);
                }
            };

            // Start checking after 3 seconds (give time for call to connect)
            handler.postDelayed(checkAnsweredRunnable, 3000);

        } catch (Exception e) {
            Log.e(TAG, "CallActivity: call error: " + e.getMessage());
            reportCallDone(currentRequestId, false);
            finish();
        }
    }

    private void endCall() {
        if (callEnded) return;
        callEnded = true;

        // Remove pending checks
        if (checkAnsweredRunnable != null) {
            handler.removeCallbacks(checkAnsweredRunnable);
        }

        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null) {
                telecomManager.endCall();
                Log.d(TAG, "CallActivity: call ended");
            }
        } catch (Exception e) {
            Log.e(TAG, "CallActivity: hang up error: " + e.getMessage());
        }
        reportCallDone(currentRequestId, true);
        handler.postDelayed(this::finish, 500);
    }

    private void reportCallDone(String requestId, boolean success) {
        if (requestId == null) return;
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
        if (checkAnsweredRunnable != null) {
            handler.removeCallbacks(checkAnsweredRunnable);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
