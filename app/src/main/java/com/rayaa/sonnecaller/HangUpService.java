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
    private static final int CALL_BUTTON_TIMEOUT_MS = 8000;
    private static final int RING_DURATION_MS = 10000;

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
        this.callback = onDone;
        this.state = CallState.WAITING_CALL_BUTTON;

        callButtonTimeoutRunnable = () -> {
            if (state == CallState.WAITING_CALL_BUTTON || state == CallState.WAITING_CONFIRM) {
                Log.e(TAG, "Timeout — could not complete call setup");
                finishCall(false);
            }
        };
        handler.postDelayed(callButtonTimeoutRunnable, CALL_BUTTON_TIMEOUT_MS);

        Log.d(TAG, "Prepared for WhatsApp call — waiting for chat screen");
    }

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
        // Try clicking the confirm button in the popup
        // WhatsApp shows "Appeler" / "Call" / "Start voice call" etc.
        if (clickNodeByText(root, new String[]{
                "Appeler", "APPELER",
                "Call", "CALL",
                "D\u00e9marrer", "DEMARRER",
                "Start", "START",
                "OK", "Ok",
                "Llamar", "LLAMAR",
                "\u0627\u062a\u0635\u0644",
                "\u62e8\u6253",
                "Ligar", "LIGAR",
                "\u041f\u043e\u0437\u0432\u043e\u043d\u0438\u0442\u044c"
        })) {
            Log.d(TAG, "Confirm button clicked — call starting!");
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
        }
        // Also try by content description in case it's not text-based
        else if (clickNodeByDescriptions(root, new String[]{
                "Call", "Appeler", "Start voice call",
                "D\u00e9marrer un appel vocal"
        })) {
            Log.d(TAG, "Confirm button clicked (via description) — call starting!");
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
        }
    }

    private void handleInCall(AccessibilityNodeInfo root) {
        if (hasTimerText(root)) {
            Log.d(TAG, "Call answered — ending immediately");
            endWhatsAppCall();
            finishCall(true);
        }
    }

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
     * Find and click a node by content description.
     * Skips video call buttons.
     */
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

    /**
     * Find and click a node by its text content (for popup buttons).
     */
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

    private void endWhatsAppCall() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            // Try end call button by description
            boolean clicked = clickNodeByDescriptions(root, new String[]{
                    "End call", "Hang up", "Decline",
                    "Raccrocher", "Terminer l'appel", "Refuser",
                    "end", "fin"
            });

            // Also try by text
            if (!clicked) {
                clicked = clickNodeByText(root, new String[]{
                        "Raccrocher", "End call", "Hang up",
                        "Terminer", "Decline", "Refuser"
                });
            }

            root.recycle();

            if (!clicked) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
            }
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 300);
        }

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
