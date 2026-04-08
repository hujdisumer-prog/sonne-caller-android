package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * AccessibilityService that ends calls by:
 * 1. Opening quick settings panel
 * 2. Tapping the airplane mode toggle
 * 3. Waiting 2 sec for call to drop
 * 4. Tapping airplane mode again to turn it off
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

    public boolean endCall() {
        Log.d(TAG, "HangUpService: ending call via quick settings airplane mode");

        // Step 1: Press HOME to leave the call screen (Samsung blocks quick settings during call)
        performGlobalAction(GLOBAL_ACTION_HOME);
        Log.d(TAG, "HangUpService: pressed HOME");

        // Step 2: Wait 500ms, then open quick settings
        handler.postDelayed(() -> {
            boolean opened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            Log.d(TAG, "HangUpService: opened quick settings: " + opened);

            // Step 3: Wait for panel to appear, then click airplane mode
            handler.postDelayed(() -> {
                clickAirplaneMode();

                // Step 4: Wait 3 sec for call to die, then turn airplane mode OFF
                handler.postDelayed(() -> {
                    // Re-open quick settings (it might have closed)
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);

                    handler.postDelayed(() -> {
                        clickAirplaneMode();
                        Log.d(TAG, "HangUpService: airplane mode toggled OFF");

                        // Step 5: Close quick settings
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            performGlobalAction(GLOBAL_ACTION_HOME);
                            Log.d(TAG, "HangUpService: done");
                        }, 500);
                    }, 1000);
                }, 3000);
            }, 1500);
        }, 500);

        return true;
    }

    private void clickAirplaneMode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "clickAirplaneMode: no root window");
            return;
        }

        // Try to find airplane mode toggle by text
        boolean found = findAndClick(root, "avion") ||
                        findAndClick(root, "airplane") ||
                        findAndClick(root, "flight") ||
                        findAndClick(root, "vol");

        if (!found) {
            // Try by description
            found = findAndClickByDesc(root, "avion") ||
                    findAndClickByDesc(root, "airplane") ||
                    findAndClickByDesc(root, "flight");
        }

        root.recycle();

        if (found) {
            Log.d(TAG, "clickAirplaneMode: clicked!");
        } else {
            Log.d(TAG, "clickAirplaneMode: not found, logging all nodes");
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 != null) {
                logNodes(root2, 0);
                root2.recycle();
            }
        }
    }

    private boolean findAndClick(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;

        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) {
            Log.d(TAG, "Found by text: " + nodeText + " clickable=" + node.isClickable());
            if (node.isClickable()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            // Try parent
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                if (parent.isClickable()) {
                    boolean r = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                    return r;
                }
                parent.recycle();
            }
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
            Log.d(TAG, "Found by desc: " + desc + " clickable=" + node.isClickable());
            if (node.isClickable()) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                if (parent.isClickable()) {
                    boolean r = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                    return r;
                }
                parent.recycle();
            }
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
        if (node == null || depth > 8) return;
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";
        Log.d(TAG, indent + "class=" + node.getClassName() +
              " text=" + node.getText() +
              " desc=" + node.getContentDescription() +
              " click=" + node.isClickable());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                logNodes(child, depth + 1);
                child.recycle();
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
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
