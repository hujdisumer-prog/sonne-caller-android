package com.rayaa.sonnecaller;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;

/**
 * Transparent activity that places the call and hangs up as soon as
 * the person answers or it goes to voicemail (OFFHOOK state).
 * If nobody answers, hangs up after 25 seconds.
 */
public class CallActivity extends Activity {

    private static final String TAG = "SonneCaller";
    private static final int RING_DURATION_MS = 25000;
    private PowerManager.WakeLock wakeLock;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private boolean callAnswered = false;
    private boolean callEnded = false;
    private String currentRequestId;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler();

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
        wakeLock.acquire(30000);

        String phone = getIntent().getStringExtra("phone");
        currentRequestId = getIntent().getStringExtra("requestId");

        if (phone != null) {
            setupPhoneStateListener();
            makeCall(phone);
        } else {
            Log.e(TAG, "CallActivity: no phone number");
            finish();
        }
    }

    private void setupPhoneStateListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        phoneStateListener = new PhoneStateListener() {
            private boolean wasRinging = false;

            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        // Phone is ringing (incoming — shouldn't happen for outgoing)
                        wasRinging = true;
                        Log.d(TAG, "State: RINGING");
                        break;

                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        // Call answered (person picked up) or voicemail
                        Log.d(TAG, "State: OFFHOOK — person answered or voicemail, hanging up");
                        callAnswered = true;
                        // Wait 1 second then hang up (so the person hears a brief ring)
                        handler.postDelayed(() -> endCall(), 1000);
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        // Call ended
                        Log.d(TAG, "State: IDLE — call ended");
                        if (!callEnded) {
                            callEnded = true;
                            reportCallDone(currentRequestId, true);
                            finish();
                        }
                        break;
                }
            }
        };
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
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

            // Safety: hang up after 25 seconds max even if no state change
            handler.postDelayed(() -> {
                if (!callEnded) {
                    Log.d(TAG, "CallActivity: max duration reached, hanging up");
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

        // Small delay before finishing activity
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
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
