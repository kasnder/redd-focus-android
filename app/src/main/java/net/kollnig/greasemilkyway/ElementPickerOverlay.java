package net.kollnig.greasemilkyway;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen transparent overlay that intercepts touches and allows the user
 * to select accessibility nodes to block. Supports tap-to-cycle through
 * overlapping elements at the same touch point.
 */
public class ElementPickerOverlay {
    private static final String TAG = "ElementPickerOverlay";

    private final DistractionControlService service;
    private final WindowManager windowManager;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Overlay views
    private View touchInterceptor;        // Full-screen transparent view to capture touches
    private View highlightView;           // Colored border highlight on selected element
    private LinearLayout controlBar;      // Bottom bar with action buttons
    private TextView infoText;            // Node info display

    // State
    private List<AccessibilityNodeInfo> nodesAtPoint = new ArrayList<>();
    private int currentNodeIndex = 0;
    private String currentPackageName = "";
    private AccessibilityNodeInfo currentRootNode = null;
    private boolean isActive = false;
    private boolean isAtBottom = true;

    public ElementPickerOverlay(DistractionControlService service, WindowManager windowManager) {
        this.service = service;
        this.windowManager = windowManager;
    }

    public boolean isActive() {
        return isActive;
    }

    /**
     * Show the picker overlay on screen.
     */
    public void show() {
        if (isActive) return;
        isActive = true;

        ui.post(() -> {
            try {
                createTouchInterceptor();
                createControlBar();
                createHighlightView();
            } catch (Exception e) {
                Log.e(TAG, "Error showing picker overlay", e);
                hide();
            }
        });
    }

    /**
     * Remove the picker overlay from screen.
     */
    public void hide() {
        isActive = false;
        ui.post(() -> {
            removeSafely(touchInterceptor);
            removeSafely(highlightView);
            removeSafely(controlBar);
            touchInterceptor = null;
            highlightView = null;
            controlBar = null;
            infoText = null;
            recycleNodes();
        });
    }

    private void recycleNodes() {
        for (AccessibilityNodeInfo node : nodesAtPoint) {
            try {
                node.recycle();
            } catch (Exception ignored) {
            }
        }
        nodesAtPoint.clear();
        currentNodeIndex = 0;
        if (currentRootNode != null) {
            try {
                currentRootNode.recycle();
            } catch (Exception ignored) {
            }
            currentRootNode = null;
        }
    }

    private void removeSafely(View view) {
        if (view != null) {
            try {
                if (view.getParent() != null) {
                    windowManager.removeView(view);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error removing view", e);
            }
        }
    }

    /**
     * Creates the full-screen transparent overlay that intercepts all touches.
     */
    private void createTouchInterceptor() {
        touchInterceptor = new View(service) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    float x = event.getRawX();
                    float y = event.getRawY();
                    handleTap(x, y);
                }
                return true;
            }
        };

        // Semi-transparent background to indicate picker mode
        touchInterceptor.setBackgroundColor(Color.argb(40, 0, 0, 0));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(touchInterceptor, params);
    }

    /**
     * Creates the highlight view used to outline the currently selected element.
     */
    private void createHighlightView() {
        highlightView = new View(service) {
            private final Paint borderPaint = new Paint() {{
                setStyle(Paint.Style.STROKE);
                setStrokeWidth(6f);
                setColor(Color.RED);
                setAntiAlias(true);
            }};
            private final Paint fillPaint = new Paint() {{
                setStyle(Paint.Style.FILL);
                setColor(Color.argb(40, 255, 0, 0));
            }};

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawRect(0, 0, getWidth(), getHeight(), fillPaint);
                canvas.drawRect(3, 3, getWidth() - 3, getHeight() - 3, borderPaint);
            }
        };
        highlightView.setVisibility(View.GONE);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                0, 0,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(highlightView, params);
    }

    /**
     * Creates the bottom control bar with action buttons and info text.
     */
    private void createControlBar() {
        boolean isDarkMode = (service.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int bgColor = isDarkMode ? Color.argb(230, 30, 30, 30) : Color.argb(230, 255, 255, 255);
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;
        int btnTextColor = Color.WHITE;

        controlBar = new LinearLayout(service);
        controlBar.setOrientation(LinearLayout.VERTICAL);
        controlBar.setBackgroundColor(bgColor);
        int pad = dpToPx(12);
        controlBar.setPadding(pad, pad, pad, pad);

        // Info text
        infoText = new TextView(service);
        infoText.setTextColor(textColor);
        infoText.setTextSize(13f);
        infoText.setText(service.getString(R.string.picker_hint));
        infoText.setPadding(0, 0, 0, dpToPx(8));
        controlBar.addView(infoText);

        // Button row
        LinearLayout buttonRow = new LinearLayout(service);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        // Deeper button
        Button deeperBtn = createButton(service.getString(R.string.picker_deeper), Color.argb(200, 60, 60, 60), btnTextColor);
        deeperBtn.setOnClickListener(v -> cycleDeeper());
        buttonRow.addView(deeperBtn, createButtonParams());

        // Shallower button
        Button shallowerBtn = createButton(service.getString(R.string.picker_shallower), Color.argb(200, 60, 60, 60), btnTextColor);
        shallowerBtn.setOnClickListener(v -> cycleShallower());
        buttonRow.addView(shallowerBtn, createButtonParams());

        // Block button
        Button blockBtn = createButton(service.getString(R.string.picker_block), Color.argb(200, 200, 40, 40), btnTextColor);
        blockBtn.setOnClickListener(v -> confirmBlock());
        buttonRow.addView(blockBtn, createButtonParams());

        // Move button
        Button moveBtn = createButton(service.getString(R.string.picker_move), Color.argb(200, 100, 100, 100), btnTextColor);
        moveBtn.setOnClickListener(v -> toggleControlBarPosition());
        buttonRow.addView(moveBtn, createButtonParams());

        // Cancel button
        Button cancelBtn = createButton(service.getString(R.string.picker_cancel), Color.argb(200, 100, 100, 100), btnTextColor);
        cancelBtn.setOnClickListener(v -> {
            hide();
            service.stopPickerMode();
        });
        buttonRow.addView(cancelBtn, createButtonParams());

        controlBar.addView(buttonRow);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = (isAtBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.START;

        windowManager.addView(controlBar, params);
    }

    /**
     * Toggles the control bar position between top and bottom.
     */
    private void toggleControlBarPosition() {
        if (controlBar != null) {
            isAtBottom = !isAtBottom;
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) controlBar.getLayoutParams();
            params.gravity = (isAtBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.START;
            windowManager.updateViewLayout(controlBar, params);
        }
    }

    private Button createButton(String text, int bgColor, int textColor) {
        Button btn = new Button(service);
        btn.setText(text);
        btn.setTextSize(12f);
        btn.setAllCaps(false);
        btn.setBackgroundColor(bgColor);
        btn.setTextColor(textColor);
        int hPad = dpToPx(12);
        int vPad = dpToPx(6);
        btn.setPadding(hPad, vPad, hPad, vPad);
        return btn;
    }

    private LinearLayout.LayoutParams createButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        return params;
    }

    /**
     * Handle a tap at the given screen coordinates.
     * Finds all accessibility nodes at that point and selects the deepest one.
     */
    private void handleTap(float x, float y) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "No root window available");
            return;
        }

        try {
            currentPackageName = root.getPackageName() != null ? root.getPackageName().toString() : "";

            // Collect all nodes whose bounds contain the tap point
            recycleNodes();
            // Store a copy of the root for path generation
            currentRootNode = AccessibilityNodeInfo.obtain(root);
            collectNodesAtPoint(root, (int) x, (int) y, nodesAtPoint);

            if (nodesAtPoint.isEmpty()) {
                updateInfo(service.getString(R.string.picker_no_element));
                hideHighlight();
                return;
            }

            // Start from the deepest (last) element
            currentNodeIndex = nodesAtPoint.size() - 1;
            highlightCurrentNode();
        } catch (Exception e) {
            Log.e(TAG, "Error handling tap", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Recursively collect all nodes whose bounds contain the given point.
     * Nodes are added in tree order (parent before children), so deeper nodes come later.
     */
    private void collectNodesAtPoint(AccessibilityNodeInfo node, int x, int y,
                                      List<AccessibilityNodeInfo> result) {
        if (node == null || !node.isVisibleToUser()) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (bounds.contains(x, y)) {
            // Create a copy so we can keep it after the parent is recycled
            AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(node);
            result.add(copy);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collectNodesAtPoint(child, x, y, result);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    /**
     * Cycle to the next deeper node at the current touch point.
     */
    private void cycleDeeper() {
        if (nodesAtPoint.isEmpty()) return;
        if (currentNodeIndex < nodesAtPoint.size() - 1) {
            currentNodeIndex++;
            highlightCurrentNode();
        } else {
            Toast.makeText(service, R.string.picker_deepest, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Cycle to the next shallower node at the current touch point.
     */
    private void cycleShallower() {
        if (nodesAtPoint.isEmpty()) return;
        if (currentNodeIndex > 0) {
            currentNodeIndex--;
            highlightCurrentNode();
        } else {
            Toast.makeText(service, R.string.picker_shallowest, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Highlight the currently selected node and show its info.
     */
    private void highlightCurrentNode() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            hideHighlight();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        // Update highlight position and size
        if (highlightView != null && !bounds.isEmpty()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) highlightView.getLayoutParams();
            params.x = bounds.left;
            params.y = bounds.top;
            params.width = bounds.width();
            params.height = bounds.height();

            try {
                windowManager.updateViewLayout(highlightView, params);
                highlightView.setVisibility(View.VISIBLE);
                highlightView.invalidate();
            } catch (Exception e) {
                Log.e(TAG, "Error updating highlight", e);
            }
        }

        // Update info text
        String description = ElementPickerRuleGenerator.describeNode(node);
        String depth = "(" + (currentNodeIndex + 1) + "/" + nodesAtPoint.size() + ")";
        updateInfo(depth + " " + description);
    }

    private void hideHighlight() {
        if (highlightView != null) {
            highlightView.setVisibility(View.GONE);
        }
    }

    private void updateInfo(String text) {
        if (infoText != null) {
            infoText.setText(text);
        }
    }

    /**
     * Show a confirmation dialog for blocking the currently selected element.
     */
    private void confirmBlock() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            Toast.makeText(service, R.string.picker_no_element, Toast.LENGTH_SHORT).show();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        String selectorDesc = ElementPickerRuleGenerator.getSelectorDescription(node, currentRootNode);
        String generatedRule = ElementPickerRuleGenerator.generateRule(node, currentRootNode, currentPackageName, null);

        // Build confirmation UI as an overlay (can't use AlertDialog from a service easily)
        showConfirmationOverlay(node, selectorDesc, generatedRule);
    }

    /**
     * Show a confirmation overlay with rule preview and comment field.
     */
    private void showConfirmationOverlay(AccessibilityNodeInfo node, String selectorDesc, String generatedRule) {
        boolean isDarkMode = (service.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int bgColor = isDarkMode ? Color.argb(245, 30, 30, 30) : Color.argb(245, 255, 255, 255);
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;
        int secondaryTextColor = isDarkMode ? Color.argb(180, 255, 255, 255) : Color.argb(180, 0, 0, 0);

        // Container
        FrameLayout container = new FrameLayout(service);
        container.setBackgroundColor(Color.argb(120, 0, 0, 0));

        // Dialog card
        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(bgColor);
        int pad = dpToPx(20);
        card.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        cardParams.leftMargin = dpToPx(24);
        cardParams.rightMargin = dpToPx(24);

        // Title
        TextView title = new TextView(service);
        title.setText(R.string.picker_confirm_title);
        title.setTextColor(textColor);
        title.setTextSize(18f);
        title.setPadding(0, 0, 0, dpToPx(12));
        card.addView(title);

        // Selector description
        TextView selectorLabel = new TextView(service);
        selectorLabel.setText(service.getString(R.string.picker_selector_label, selectorDesc));
        selectorLabel.setTextColor(secondaryTextColor);
        selectorLabel.setTextSize(13f);
        selectorLabel.setPadding(0, 0, 0, dpToPx(8));
        card.addView(selectorLabel);

        // Generated rule preview
        TextView rulePreview = new TextView(service);
        rulePreview.setText(service.getString(R.string.picker_rule_preview, generatedRule));
        rulePreview.setTextColor(secondaryTextColor);
        rulePreview.setTextSize(12f);
        rulePreview.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        rulePreview.setBackgroundColor(isDarkMode ? Color.argb(60, 255, 255, 255) : Color.argb(30, 0, 0, 0));
        card.addView(rulePreview);

        // Comment field label
        TextView commentLabel = new TextView(service);
        commentLabel.setText(R.string.picker_comment_label);
        commentLabel.setTextColor(textColor);
        commentLabel.setTextSize(14f);
        commentLabel.setPadding(0, dpToPx(12), 0, dpToPx(4));
        card.addView(commentLabel);

        // Comment input
        EditText commentInput = new EditText(service);
        commentInput.setHint(R.string.picker_comment_hint);
        commentInput.setTextColor(textColor);
        commentInput.setHintTextColor(secondaryTextColor);
        commentInput.setTextSize(14f);
        commentInput.setSingleLine(true);
        card.addView(commentInput);

        // Button row
        LinearLayout btnRow = new LinearLayout(service);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dpToPx(16), 0, 0);

        Button cancelBtn = createButton(service.getString(R.string.picker_dialog_cancel),
                isDarkMode ? Color.argb(200, 80, 80, 80) : Color.argb(200, 200, 200, 200),
                isDarkMode ? Color.WHITE : Color.BLACK);
        cancelBtn.setOnClickListener(v -> {
            removeSafely(container);
        });

        Button confirmBtn = createButton(service.getString(R.string.picker_dialog_confirm),
                Color.argb(200, 200, 40, 40), Color.WHITE);
        confirmBtn.setOnClickListener(v -> {
            String comment = commentInput.getText().toString().trim();
            if (comment.isEmpty()) {
                comment = selectorDesc;
            }
            String finalRule = ElementPickerRuleGenerator.generateRule(node, currentRootNode, currentPackageName, comment);
            saveAndApplyRule(finalRule);
            removeSafely(container);
            hide();
            service.stopPickerMode();
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(dpToPx(8), 0, 0, 0);

        btnRow.addView(cancelBtn, btnParams);
        btnRow.addView(confirmBtn, btnParams);
        card.addView(btnRow);

        container.addView(card, cardParams);

        // Tapping outside the card cancels
        container.setOnClickListener(v -> removeSafely(container));
        card.setOnClickListener(v -> { /* consume click */ });

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(container, overlayParams);
    }

    /**
     * Save the rule and immediately apply it.
     */
    private void saveAndApplyRule(String ruleString) {
        ServiceConfig config = new ServiceConfig(service);
        config.addCustomRule(ruleString);

        // Enable the new rule
        FilterRuleParser parser = new FilterRuleParser();
        List<FilterRule> parsed = parser.parseRules(new String[]{ruleString});
        if (!parsed.isEmpty()) {
            FilterRule rule = parsed.get(0);
            config.setRuleEnabled(rule, true);

            // Also ensure the package is not disabled
            config.setPackageDisabled(rule.packageName, false);
        }

        // Refresh service rules
        service.updateRules();

        Toast.makeText(service, R.string.picker_rule_saved, Toast.LENGTH_SHORT).show();
    }

    private int dpToPx(int dp) {
        float density = service.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
