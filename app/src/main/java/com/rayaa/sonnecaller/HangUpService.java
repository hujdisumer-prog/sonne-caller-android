package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class HangUpService extends AccessibilityService {

    private static final String TAG = "SonneCaller";
    private static HangUpService instance = null;

    private static final String WA_PACKAGE = "com.whatsapp.w4b";
    private static final int CALL_BUTTON_TIMEOUT_MS = 5000;
    private static final int RING_DURATION_MS = 10000;

    private enum CallState { IDLE, WAITING_CALL_BUTTON, IN_CALL, ENDED }

    private CallState state = CallState.IDLE;
    private CallDoneCallback callback;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable callButtonTimeoutRunnable;
    private Runnable ringTimeoutRunnable;

    public interface CallDoneCallback {
        void onCallDone(boolean success);
    }

    public static HangUpService getInstance() { return instance; }
    public static boolean isAvailable() { return instance != null; }

    /**
     * Called by CallerService before opening WhatsApp chat.
     * Sets up accessibility to click the call button, monitor call, and end it.
     */
    public void prepareForCall(CallDoneCallback onDone) {
        this.callback = onDone;
        this.state = CallState.WAITING_CALL_BUTTON;

        callButtonTimeoutRunnable = () -> {
            if (state == CallState.WAITING_CALL_BUTTON) {
                Log.e(TAG, "Could not find call button — giving up");
                finishCall(false);
            }
        };
        handler.postDelayed(callButtonTimeoutRunnable, CALL_BUTTON_TIMEOUT_MS);

        Log.d(TAG, "Prepared for WhatsApp call — waiting for chat screen");
    }

    /**
     * Force end the call (safety timeout from CallerService)
     */
    public void forceEndCall() {
        if (state == CallState.ENDED) return;
        Log.d(TAG, "Force ending call");
        endWhatsAppCall();
        finishCall(true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (state == CallState.IDLE || state == CallState.ENDED) return;
        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();
        if (!pkg.equals(WA_PACKAGE) && !pkg.equals("com.whatsapp")) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (state == CallState.WAITING_CALL_BUTTON) {
                handleWaitingCallButton(root);
            } else if (state == CallState.IN_CALL) {
                handleInCall(root);
            }
        } finally {
            root.recycle();
        }
    }

    private void handleWaitingCallButton(AccessibilityNodeInfo root) {
        // Try to click the voice call button in the chat toolbar
        if (clickNodeByDescriptions(root, new String[]{
                "Voice call", "Audio call", "Appel vocal", "Appel audio"
        })) {
            Log.d(TAG, "Voice call button clicked!");
            state = CallState.IN_CALL;
            cancelTimeout(callButtonTimeoutRunnable);

            // Start 10s ring timeout
            ringTimeoutRunnable = () -> {
                if (state == CallState.IN_CALL) {
                    Log.d(TAG, "Ring timeout (10s) — ending call");
                    endWhatsAppCall();
                    finishCall(true);
                }
            };
            handler.postDelayed(ringTimeoutRunnable, RING_DURATION_MS);
        }
    }

    private void handleInCall(AccessibilityNodeInfo root) {
        // If call was answered, WhatsApp shows a timer like "0:00", "0:01"
        if (hasTimerText(root)) {
            Log.d(TAG, "Call answered — ending immediately");
            endWhatsAppCall();
            finishCall(true);
        }
    }

    /**
     * Look for timer text (e.g. "0:01") indicating call was answered
     */
    private boolean hasTimerText(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence text = node.getText();
        if (text != null && text.toString().matches("\\d+:\\d{2}")) {
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = hasTimerText(child);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    /**
     * Find and click a node matching one of the given content descriptions.
     * Skips video call buttons.
     */
    private boolean clickNodeByDescriptions(AccessibilityNodeInfo node, String[] descriptions) {
        if (node == null) return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String descLower = desc.toString().toLowerCase();
            for (String target : descriptions) {
                if (descLower.contains(target.toLowerCase())) {
                    // Skip video call buttons
                    if (descLower.contains("video") || descLower.contains("vid\u00e9o")) continue;

                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                    // Try parent
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent != null && parent.isClickable()) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        parent.recycle();
                        return true;
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = clickNodeByDescriptions(child, descriptions);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    /**
     * End the WhatsApp call by clicking the end call button or pressing back
     */
    private void endWhatsAppCall() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            boolean clicked = clickNodeByDescriptions(root, new String[]{
                    "End call", "Hang up", "Decline",
                    "Raccrocher", "Terminer l'appel", "Refuser",
                    "end", "fin"
            });
            root.recycle();

            if (!clicked) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
            }
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
        }

        // Return to home screen
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 1000);
    }

    private void finishCall(boolean success) {
        if (state == CallState.ENDED) return;
        state = CallState.ENDED;
        cancelTimeout(callButtonTimeoutRunnable);
        cancelTimeout(ringTimeoutRunnable);

        if (callback != null) {
            CallDoneCallback cb = callback;
            callback = null;
            handler.post(() -> cb.onCallDone(success));
        }
    }

    private void cancelTimeout(Runnable runnable) {
        if (runnable != null) handler.removeCallbacks(runnable);
    }

    // Legacy method kept for compatibility
    public void endCall() {
        forceEndCall();
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "HangUpService: ready (WhatsApp mode)");
    }

    @Override
    public void onDestroy() {
        cancelTimeout(callButtonTimeoutRunnable);
        cancelTimeout(ringTimeoutRunnable);
        instance = null;
        super.onDestroy();
    }
}
