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

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            // Log ALL nodes on screen to find the end call button
            logAllNodes(root, 0);

            // Try to find and click any clickable node at the bottom of the screen
            // The red end-call button is always at the bottom center
            boolean found = findAndClickButton(root, "end") ||
                            findAndClickButton(root, "raccrocher") ||
                            findAndClickButton(root, "terminer") ||
                            findAndClickButton(root, "fin") ||
                            findAndClickEndCallById(root) ||
                            clickBottomCenterButton(root);

            root.recycle();

            if (found) {
                Log.d(TAG, "HangUpService: button clicked!");
                return true;
            }
        }

        // Fallback: tap on screen using display metrics
        Log.d(TAG, "HangUpService: fallback - tapping screen center bottom");
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        float screenW = metrics.widthPixels;
        float screenH = metrics.heightPixels;

        // Tap bottom center (where red button typically is)
        float x = screenW / 2f;
        float y = screenH * 0.85f; // 85% from top

        Log.d(TAG, "HangUpService: screen=" + screenW + "x" + screenH + " tapping at " + x + "," + y);

        tapAt(x, y);

        // Also try lower
        try { Thread.sleep(300); } catch (Exception e) {}
        tapAt(x, screenH * 0.90f);

        // And even lower
        try { Thread.sleep(300); } catch (Exception e) {}
        tapAt(x, screenH * 0.80f);

        return true;
    }

    private void tapAt(float x, float y) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        path.lineTo(x + 1, y + 1);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
            new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50);
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
        Log.d(TAG, "HangUpService: tapped at " + x + "," + y);
    }

    /**
     * Find the clickable button closest to bottom center of screen
     */
    private boolean clickBottomCenterButton(AccessibilityNodeInfo node) {
        if (node == null) return false;

        android.graphics.Rect bounds = new android.graphics.Rect();
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenH = metrics.heightPixels;
        int screenW = metrics.widthPixels;

        // Find all clickable nodes in the bottom 30% of screen
        AccessibilityNodeInfo bestNode = null;
        int bestY = 0;

        findBottomClickables(node, screenH, screenW, bounds);

        return false; // clickBottomCenterButton is best-effort, tapAt handles it
    }

    private void findBottomClickables(AccessibilityNodeInfo node, int screenH, int screenW, android.graphics.Rect bounds) {
        if (node == null) return;

        node.getBoundsInScreen(bounds);
        if (node.isClickable() && bounds.top > screenH * 0.65) {
            int centerX = (bounds.left + bounds.right) / 2;
            int centerY = (bounds.top + bounds.bottom) / 2;
            Log.d(TAG, "Bottom clickable: centerX=" + centerX + " centerY=" + centerY +
                       " text=" + node.getText() + " desc=" + node.getContentDescription() +
                       " class=" + node.getClassName() + " id=" + node.getViewIdResourceName());

            // If it's near center X and in bottom area, click it
            if (Math.abs(centerX - screenW / 2) < screenW * 0.2) {
                Log.d(TAG, "Clicking bottom-center button!");
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findBottomClickables(child, screenH, screenW, bounds);
                child.recycle();
            }
        }
    }

    private void logAllNodes(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return;

        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";

        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);

        Log.d(TAG, indent + "Node: class=" + node.getClassName() +
              " text=" + node.getText() +
              " desc=" + node.getContentDescription() +
              " id=" + node.getViewIdResourceName() +
              " clickable=" + node.isClickable() +
              " bounds=" + bounds);

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                logAllNodes(child, depth + 1);
                child.recycle();
            }
        }
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
