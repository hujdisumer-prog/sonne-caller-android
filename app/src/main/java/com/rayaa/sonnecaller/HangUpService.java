package com.rayaa.sonnecaller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * AccessibilityService that ends calls by finding and clicking
 * the red "end call" button on Samsung's call screen.
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

    public boolean endCall() {
        Log.d(TAG, "HangUpService: attempting to end call");

        // Method 1: Try finding button by text/ID
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            boolean found = findAndClickButton(root, "end call") ||
                            findAndClickButton(root, "raccrocher") ||
                            findAndClickButton(root, "terminer") ||
                            findAndClickButton(root, "fin") ||
                            findAndClickEndCallById(root);

            root.recycle();

            if (found) {
                Log.d(TAG, "HangUpService: end call button clicked!");
                return true;
            }
        }

        // Method 2: Click on the coordinates where the red button is on Samsung S9
        // S9 screen: 1440x2960 — red button is bottom center
        Log.d(TAG, "HangUpService: trying coordinate tap on red button");
        android.graphics.Path path = new android.graphics.Path();
        // Bottom center of screen (red hang-up button position)
        float x = 720f;  // center X
        float y = 2500f; // near bottom Y
        path.moveTo(x, y);
        path.lineTo(x + 1, y + 1);

        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
            new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50);
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                Log.d(TAG, "HangUpService: tap gesture completed");
            }
            @Override
            public void onCancelled(android.accessibilityservice.GestureDescription gestureDescription) {
                Log.d(TAG, "HangUpService: tap gesture cancelled");
            }
        }, null);

        Log.d(TAG, "HangUpService: gesture dispatched=" + dispatched);

        // Also try different Y position (some Samsung UIs vary)
        if (dispatched) {
            try { Thread.sleep(300); } catch (Exception e) {}
            // Try slightly higher position too
            android.graphics.Path path2 = new android.graphics.Path();
            path2.moveTo(720f, 2300f);
            path2.lineTo(721f, 2301f);
            android.accessibilityservice.GestureDescription.StrokeDescription stroke2 =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path2, 0, 50);
            android.accessibilityservice.GestureDescription gesture2 =
                new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke2).build();
            dispatchGesture(gesture2, null, null);
        }

        return dispatched;
    }

    private boolean findAndClickButton(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;

        // Check this node
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();

        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) {
            Log.d(TAG, "Found button by text: " + nodeText);
            return clickNode(node);
        }
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(text.toLowerCase())) {
            Log.d(TAG, "Found button by description: " + nodeDesc);
            return clickNode(node);
        }

        // Check children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (findAndClickButton(child, text)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private boolean findAndClickEndCallById(AccessibilityNodeInfo root) {
        // Samsung uses specific view IDs for end call button
        String[] possibleIds = {
            "com.samsung.android.incallui:id/end_call_button",
            "com.samsung.android.incallui:id/floating_end_call_action_button",
            "com.samsung.android.incallui:id/end_button",
            "com.android.incallui:id/end_call_button",
            "com.android.dialer:id/incall_end_call",
        };

        for (String id : possibleIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(TAG, "Found end call button by ID: " + id);
                boolean result = clickNode(nodes.get(0));
                for (AccessibilityNodeInfo n : nodes) n.recycle();
                return result;
            }
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // Try parent if node itself is not clickable
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null && parent.isClickable()) {
            boolean result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            parent.recycle();
            return result;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "HangUpService: interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
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
