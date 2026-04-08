package com.rayaa.sonnecaller;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;

public class CallActivity extends Activity {

    private static final String TAG = "SonneCaller";
    private static final int RING_DURATION_MS = 25000;

    private PowerManager.WakeLock wakeLock;
    private boolean callEnded = false;
    private String currentRequestId;
    private Handler handler;
    private BroadcastReceiver phoneStateReceiver;
    private boolean callWasOffhook = false;

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
            registerPhoneStateReceiver();
            makeCall(phone);
        } else {
            Log.e(TAG, "CallActivity: no phone number");
            finish();
        }
    }

    private void registerPhoneStateReceiver() {
        phoneStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                Log.d(TAG, "Phone state broadcast: " + state);

                if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                    // Call is active (dialing or answered)
                    callWasOffhook = true;
                    Log.d(TAG, "OFFHOOK detected");
                }

                if (TelephonyManager.EXTRA_STATE_IDLE.equals(state) && callWasOffhook) {
                    // Call ended by itself (person hung up, voicemail ended, etc.)
                    Log.d(TAG, "IDLE after OFFHOOK — call ended naturally");
                    endCall();
                }
            }
        };

        IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        registerReceiver(phoneStateReceiver, filter);
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

            Log.d(TAG, "CallActivity: call started to " + phone);

            // Safety: max 25 seconds
            handler.postDelayed(() -> {
                if (!callEnded) {
                    Log.d(TAG, "Max duration reached, hanging up");
                    endCall();
                }
            }, RING_DURATION_MS);

        } catch (Exception e) {
            Log.e(TAG, "CallActivity: call error: " + e.getMessage());
            reportCallDone(currentRequestId, false);
            finish();
        }
    }

    private void endCall() {
        if (callEnded) return;
        callEnded = true;

        boolean ended = false;

        // Method 1: AccessibilityService
        if (HangUpService.isAvailable()) {
            HangUpService.getInstance().endCall();
            Log.d(TAG, "Method 1 (AccessibilityService): called");
            ended = true;
        }

        // Method 2: TelecomManager.endCall() (backup)
        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null) {
                boolean r = telecomManager.endCall();
                Log.d(TAG, "Method 2 (TelecomManager): " + r);
                if (r) ended = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Method 2 failed: " + e.getMessage());
        }

        Log.d(TAG, "endCall completed, ended=" + ended);
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
                Log.d(TAG, "Reported call done: " + requestId);
            } catch (Exception e) {
                Log.e(TAG, "Report error: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (phoneStateReceiver != null) {
            try { unregisterReceiver(phoneStateReceiver); } catch (Exception e) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
