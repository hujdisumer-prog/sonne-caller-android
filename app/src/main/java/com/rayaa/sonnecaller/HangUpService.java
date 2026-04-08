package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Ends calls using all available methods:
 * 1. TelecomManager.endCall()
 * 2. Quick settings → Mode hors ligne ON → confirm → wait → OFF → confirm
 */
public class HangUpService extends AccessibilityService {

    private static final String TAG = "SonneCaller";
    private static HangUpService instance = null;
    private Handler handler = new Handler(Looper.getMainLooper());

    public static HangUpService getInstance() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public void endCall() {
        Log.d(TAG, "=== ENDING CALL ===");

        // Method 1: TelecomManager (might work now that battery optimization is off)
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                boolean ended = tm.endCall();
                Log.d(TAG, "TelecomManager.endCall() = " + ended);
                if (ended) {
                    Log.d(TAG, "Call ended via TelecomManager!");
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "TelecomManager failed: " + e.getMessage());
        }

        // Method 2: Airplane/offline mode via quick settings
        Log.d(TAG, "Trying offline mode via quick settings...");

        // Step 1: HOME to leave call screen
        performGlobalAction(GLOBAL_ACTION_HOME);

        handler.postDelayed(() -> {
            // Step 2: Open quick settings
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);

            handler.postDelayed(() -> {
                // Step 3: Find and click "hors ligne" / "avion" / "airplane" / "offline"
                boolean clicked = clickOfflineMode();
                Log.d(TAG, "Offline mode click: " + clicked);

                // Step 4: Confirm popup "Désactiver" / "Activer" / "OK"
                handler.postDelayed(() -> {
                    clickConfirmButton();

                    // Step 5: Wait for call to die
                    handler.postDelayed(() -> {
                        // Step 6: Re-open quick settings
                        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);

                        handler.postDelayed(() -> {
                            // Step 7: Click offline mode again (turn OFF)
                            clickOfflineMode();

                            // Step 8: Confirm again
                            handler.postDelayed(() -> {
                                clickConfirmButton();

                                // Step 9: Close everything
                                handler.postDelayed(() -> {
                                    performGlobalAction(GLOBAL_ACTION_BACK);
                                    performGlobalAction(GLOBAL_ACTION_HOME);
                                    Log.d(TAG, "=== CALL END COMPLETE ===");
                                }, 500);
                            }, 1000);
                        }, 1500);
                    }, 3000);
                }, 1000);
            }, 1500);
        }, 500);
    }

    private boolean clickOfflineMode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        // Search by text — Samsung uses "Mode hors ligne" in French
        boolean found = findAndClick(root, "hors ligne") ||
                        findAndClick(root, "avion") ||
                        findAndClick(root, "airplane") ||
                        findAndClick(root, "offline") ||
                        findAndClick(root, "flight") ||
                        findAndClickByDesc(root, "hors ligne") ||
                        findAndClickByDesc(root, "avion") ||
                        findAndClickByDesc(root, "airplane") ||
                        findAndClickByDesc(root, "offline") ||
                        findAndClickByDesc(root, "flight");

        if (!found) {
            Log.d(TAG, "Offline mode button not found. Logging all nodes:");
            logNodes(root, 0);
        }

        root.recycle();
        return found;
    }

    private boolean clickConfirmButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        // Samsung confirmation popup buttons
        boolean found = findAndClick(root, "Activer") ||
                        findAndClick(root, "Désactiver") ||
                        findAndClick(root, "OK") ||
                        findAndClick(root, "Oui") ||
                        findAndClick(root, "Enable") ||
                        findAndClick(root, "Disable") ||
                        findAndClick(root, "Turn on") ||
                        findAndClick(root, "Turn off") ||
                        findAndClick(root, "Yes");

        Log.d(TAG, "Confirm button click: " + found);
        root.recycle();
        return found;
    }

    private boolean findAndClick(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;

        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) {
            Log.d(TAG, "Found text: '" + nodeText + "' clickable=" + node.isClickable());
            if (node.isClickable()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && parent.isClickable()) {
                boolean r = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                return r;
            }
            if (parent != null) parent.recycle();
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (findAndClick(child, text)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private boolean findAndClickByDesc(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().contains(text.toLowerCase())) {
            Log.d(TAG, "Found desc: '" + desc + "' clickable=" + node.isClickable());
            if (node.isClickable()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null && parent.isClickable()) {
                boolean r = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                return r;
            }
            if (parent != null) parent.recycle();
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (findAndClickByDesc(child, text)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private void logNodes(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return;
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";
        Log.d(TAG, indent + "text='" + node.getText() + "' desc='" + node.getContentDescription() +
              "' click=" + node.isClickable() + " class=" + node.getClassName());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                logNodes(child, depth + 1);
                child.recycle();
            }
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
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "HangUpService: ready");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
