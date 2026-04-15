package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class HangUpService extends AccessibilityService {

    private static final String TAG = "SonneCaller";
    private static HangUpService instance = null;

    private static final String WA_PACKAGE = "com.whatsapp";
    private static final int CALL_BUTTON_TIMEOUT_MS = 8000;
    private static final int RING_DURATION_MS = 25000;

    private enum CallState { IDLE, WAITING_CALL_BUTTON, WAITING_CONFIRM, IN_CALL, ENDED }

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

    public void prepareForCall(CallDoneCallback onDone) {
        handler.removeCallbacksAndMessages(null);
        cancelTimeout(callButtonTimeoutRunnable);
        cancelTimeout(ringTimeoutRunnable);

        this.callback = onDone;
        this.state = CallState.WAITING_CALL_BUTTON;

        callButtonTimeoutRunnable = () -> {
            if (state == CallState.WAITING_CALL_BUTTON || state == CallState.WAITING_CONFIRM) {
                Log.e(TAG, "Timeout — could not complete call setup (state=" + state + ")");
                forceEndCall();
            }
        };
        handler.postDelayed(callButtonTimeoutRunnable, CALL_BUTTON_TIMEOUT_MS);

        Log.d(TAG, "Prepared for WhatsApp call — waiting for chat screen");
    }

    public void forceEndCall() {
        if (state == CallState.ENDED) return;
        Log.d(TAG, "Force ending call (state=" + state + ")");
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
            } else if (state == CallState.WAITING_CONFIRM) {
                handleWaitingConfirm(root);
            } else if (state == CallState.IN_CALL) {
                handleInCall(root);
            }
        } finally {
            root.recycle();
        }
    }

    private void handleWaitingCallButton(AccessibilityNodeInfo root) {
        if (clickNodeByDescriptions(root, new String[]{
                "Voice call", "Audio call", "Appel vocal", "Appel audio"
        })) {
            Log.d(TAG, "Voice call button clicked — waiting for confirm popup");
            state = CallState.WAITING_CONFIRM;
        }
    }

    private void handleWaitingConfirm(AccessibilityNodeInfo root) {
        if (clickNodeByText(root, new String[]{
                "Appeler", "APPELER",
                "Call", "CALL",
                "D\u00e9marrer un appel vocal",
                "Start voice call",
                "OK",
                "Llamar", "LLAMAR",
                "\u0627\u062a\u0635\u0644",
                "\u62e8\u6253",
                "Ligar", "LIGAR",
                "\u041f\u043e\u0437\u0432\u043e\u043d\u0438\u0442\u044c"
        })) {
            Log.d(TAG, "Confirm button clicked — call starting!");
            startInCallState();
        } else if (clickNodeByDescriptions(root, new String[]{
                "Call", "Appeler", "Start voice call",
                "D\u00e9marrer un appel vocal"
        })) {
            Log.d(TAG, "Confirm button clicked (via description) — call starting!");
            startInCallState();
        }
    }

    private void startInCallState() {
        state = CallState.IN_CALL;
        cancelTimeout(callButtonTimeoutRunnable);

        ringTimeoutRunnable = () -> {
            if (state == CallState.IN_CALL) {
                Log.d(TAG, "Ring timeout (10s) — ending call");
                endWhatsAppCall();
                finishCall(true);
            }
        };
        handler.postDelayed(ringTimeoutRunnable, RING_DURATION_MS);
        Log.d(TAG, "IN_CALL state — 10s ring timeout started");
    }

    private void handleInCall(AccessibilityNodeInfo root) {
        // Timer text like "0:00", "0:01", "00:00", "0.00"
        if (hasTimerText(root)) {
            Log.d(TAG, "Call answered (timer detected) — ending immediately!");
            endWhatsAppCall();
            finishCall(true);
        }
    }

    private boolean hasTimerText(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence text = node.getText();
        if (text != null) {
            String t = text.toString().trim();
            // Match various timer formats: "0:00", "0:01", "00:00", "0.00" etc.
            if (t.matches("\\d{1,2}[:.:]\\d{2}")) {
                // Exclude times like "14:30" (clock times) — call timers start at 0
                if (t.startsWith("0") || t.equals("1:00") || t.equals("01:00")) {
                    return true;
                }
            }
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

    private boolean clickNodeByDescriptions(AccessibilityNodeInfo node, String[] descriptions) {
        if (node == null) return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String descLower = desc.toString().toLowerCase();
            for (String target : descriptions) {
                if (descLower.contains(target.toLowerCase())) {
                    if (descLower.contains("video") || descLower.contains("vid\u00e9o")) continue;

                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
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

    private boolean clickNodeByText(AccessibilityNodeInfo node, String[] texts) {
        if (node == null) return false;

        CharSequence nodeText = node.getText();
        if (nodeText != null) {
            String textStr = nodeText.toString().trim();
            for (String target : texts) {
                if (textStr.equalsIgnoreCase(target)) {
                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
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
                boolean found = clickNodeByText(child, texts);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    /**
     * End WhatsApp call — tries multiple approaches:
     * 1. Click end call button by description
     * 2. Click end call button by text
     * 3. Tap bottom-center of screen (where red button always is)
     * 4. BACK button as last resort
     */
    private void endWhatsAppCall() {
        Log.d(TAG, "=== ENDING WHATSAPP CALL ===");

        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean clicked = false;

        if (root != null) {
            // 1. Try end call button by description
            clicked = clickNodeByDescriptions(root, new String[]{
                    "End call", "Hang up", "Decline",
                    "Raccrocher", "Terminer l'appel", "Refuser",
                    "Finalizar", "Colgar", "Recusar",
                    "\u0625\u0646\u0647\u0627\u0621 \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0629"
            });

            // 2. Try by text
            if (!clicked) {
                clicked = clickNodeByText(root, new String[]{
                        "Raccrocher", "End call", "Hang up",
                        "Terminer", "Decline", "Refuser",
                        "Finalizar", "Colgar"
                });
            }

            root.recycle();
        }

        if (!clicked) {
            Log.d(TAG, "End button not found — tapping bottom center of screen");
            // 3. Tap the red end call button at bottom-center of screen
            tapEndCallButton();
        }

        // 4. BACK as extra safety after a delay
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 800);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 1300);

        // Return to home screen
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 2000);
    }

    /**
     * Tap the bottom-center of the screen where WhatsApp's red end call button is.
     * This is the most reliable fallback when we can't find the button via accessibility.
     */
    private void tapEndCallButton() {
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;

            // WhatsApp end call button is at bottom-RIGHT of screen
            float tapX = screenWidth * 0.85f;
            float tapY = screenHeight * 0.85f;

            Log.d(TAG, "Tapping at (" + tapX + ", " + tapY + ") to end call");

            Path path = new Path();
            path.moveTo(tapX, tapY);

            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                    .build();

            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "Tap gesture completed");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.e(TAG, "Tap gesture cancelled");
                }
            }, null);

            // Second tap slightly different position
            handler.postDelayed(() -> {
                float tapX2 = screenWidth * 0.80f;
                float tapY2 = screenHeight * 0.90f;
                Path path2 = new Path();
                path2.moveTo(tapX2, tapY2);

                GestureDescription gesture2 = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path2, 0, 100))
                        .build();

                dispatchGesture(gesture2, null, null);
                Log.d(TAG, "Second tap at (" + tapX2 + ", " + tapY2 + ")");
            }, 400);

        } catch (Exception e) {
            Log.e(TAG, "Tap gesture failed: " + e.getMessage());
        }
    }

    private void finishCall(boolean success) {
        if (state == CallState.ENDED) return;
        state = CallState.ENDED;
        cancelTimeout(callButtonTimeoutRunnable);
        cancelTimeout(ringTimeoutRunnable);
        Log.d(TAG, "Call finished (success=" + success + ")");

        if (callback != null) {
            CallDoneCallback cb = callback;
            callback = null;
            handler.post(() -> cb.onCallDone(success));
        }
    }

    private void cancelTimeout(Runnable runnable) {
        if (runnable != null) handler.removeCallbacks(runnable);
    }

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
