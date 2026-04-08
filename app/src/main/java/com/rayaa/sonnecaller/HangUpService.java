package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AccessibilityService that can end calls by pressing the hang-up button.
 * Uses GLOBAL_ACTION_BACK or finds the end-call button on screen.
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

    /**
     * End the current call by performing global action
     */
    public boolean endCall() {
        Log.d(TAG, "HangUpService: ending call via GLOBAL_ACTION_BACK");

        // Method 1: Press back button multiple times to exit call screen
        boolean result = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.d(TAG, "HangUpService: GLOBAL_ACTION_BACK result=" + result);

        // Method 2: Also try pressing home to leave call screen
        performGlobalAction(GLOBAL_ACTION_HOME);

        return result;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We don't need to process events, just need the service running
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "HangUpService: interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        Log.d(TAG, "HangUpService: connected and ready");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
