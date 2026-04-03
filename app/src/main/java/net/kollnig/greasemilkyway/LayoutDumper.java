package net.kollnig.greasemilkyway;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles dumping of the accessibility view hierarchy to JSON format.
 */
public class LayoutDumper {
    private static final String TAG = "LayoutDumper";
    private static final int LAYOUT_DUMP_INTERVAL_MS = 2000; // Dump layout every 2 seconds
    private static final boolean ENABLED = false; // Disable dumping by default

    private final Handler ui;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Runnable dumpLayout = new Runnable() {
        @Override
        public void run() {
            if (!isRunning.get()) return;

            try {
                AccessibilityNodeInfo root = DistractionControlService.getInstance().getRootInActiveWindow();
                if (root != null) {
                    try {
                        StringBuilder json = new StringBuilder();
                        json.append("{\n");
                        json.append("  \"packageName\": \"").append(root.getPackageName()).append("\",\n");
                        json.append("  \"className\": \"").append(root.getClassName()).append("\",\n");
                        json.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
                        json.append("  \"root\": ");
                        dumpNodeHierarchy(root, json);
                        json.append("\n}");
                        Log.d(TAG, json.toString());
                    } finally {
                        root.recycle();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error dumping layout", e);
            }

            // Schedule next dump if still running
            if (isRunning.get()) {
                ui.postDelayed(this, LAYOUT_DUMP_INTERVAL_MS);
            }
        }
    };

    public LayoutDumper() {
        this.ui = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (!ENABLED) return;
        if (isRunning.compareAndSet(false, true)) {
            ui.post(dumpLayout);
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            ui.removeCallbacks(dumpLayout);
        }
    }

    private void dumpNodeHierarchy(AccessibilityNodeInfo node, StringBuilder json) {
        if (node == null) {
            json.append("null");
            return;
        }

        json.append("{\n");
        json.append("  \"className\": \"").append(node.getClassName() != null ? node.getClassName().toString() : "null").append("\",\n");

        String viewId = node.getViewIdResourceName();
        if (viewId != null) {
            json.append("  \"viewId\": \"").append(viewId.replace("\"", "\\\"")).append("\",\n");
        }

        String text = node.getText() != null ? node.getText().toString() : "";
        if (!text.isEmpty()) {
            json.append("  \"text\": \"").append(text.replace("\"", "\\\"")).append("\",\n");
        }

        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        if (!desc.isEmpty()) {
            json.append("  \"description\": \"").append(desc.replace("\"", "\\\"")).append("\",\n");
        }

        // Add bounds information
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        json.append("  \"bounds\": {\n");
        json.append("    \"left\": ").append(bounds.left).append(",\n");
        json.append("    \"top\": ").append(bounds.top).append(",\n");
        json.append("    \"right\": ").append(bounds.right).append(",\n");
        json.append("    \"bottom\": ").append(bounds.bottom).append("\n");
        json.append("  },\n");

        // Add children
        json.append("  \"children\": [\n");
        boolean hasChildren = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    if (hasChildren) {
                        json.append(",\n");
                    }
                    dumpNodeHierarchy(child, json);
                    hasChildren = true;
                } finally {
                    child.recycle();
                }
            }
        }
        json.append("\n  ]\n");
        json.append("}");
    }
} 