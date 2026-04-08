package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Simple service: just tries TelecomManager.endCall()
 * Now that battery optimization is disabled, it should work.
 */
public class HangUpService extends AccessibilityService {

    private static final String TAG = "SonneCaller";
    private static HangUpService instance = null;

    public static HangUpService getInstance() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public void endCall() {
        Log.d(TAG, "=== ENDING CALL ===");

        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                boolean ended = tm.endCall();
                Log.d(TAG, "TelecomManager.endCall() = " + ended);
            }
        } catch (Exception e) {
            Log.e(TAG, "endCall failed: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "HangUpService: ready");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
