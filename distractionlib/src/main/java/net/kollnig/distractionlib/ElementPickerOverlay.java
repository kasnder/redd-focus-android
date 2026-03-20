package net.kollnig.distractionlib;

import android.accessibilityservice.AccessibilityService;
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

    public interface Listener {
        void onRuleChosen(String ruleString);

        void onRuleUndone(String ruleString);

        void onPickerDismissed();
    }

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View touchInterceptor;
    private View highlightView;
    private LinearLayout controlBar;
    private TextView infoText;

    private final List<AccessibilityNodeInfo> nodesAtPoint = new ArrayList<>();
    private int currentNodeIndex = 0;
    private String currentPackageName = "";
    private AccessibilityNodeInfo currentRootNode = null;
    private boolean isActive = false;
    private boolean isAtBottom = true;
    private String lastAppliedRule = null;
    private LinearLayout undoBar = null;
    private Runnable undoAutoHideRunnable = null;
    private static final long UNDO_TIMEOUT_MS = 8000;

    public ElementPickerOverlay(AccessibilityService service, WindowManager windowManager,
                                Listener listener) {
        this.service = service;
        this.windowManager = windowManager;
        this.listener = listener;
    }

    public boolean isActive() {
        return isActive;
    }

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

    public void hide() {
        isActive = false;
        ui.post(() -> {
            removeUndoBar();
            removeSafely(touchInterceptor);
            removeSafely(highlightView);
            removeSafely(controlBar);
            touchInterceptor = null;
            highlightView = null;
            controlBar = null;
            infoText = null;
            lastAppliedRule = null;
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

    private void createTouchInterceptor() {
        touchInterceptor = new View(service) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    handleTap(event.getRawX(), event.getRawY());
                }
                return true;
            }
        };

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
                0, 0, 0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(highlightView, params);
    }

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

        infoText = new TextView(service);
        infoText.setTextColor(textColor);
        infoText.setTextSize(13f);
        infoText.setText(service.getString(R.string.picker_hint));
        infoText.setPadding(0, 0, 0, dpToPx(8));
        controlBar.addView(infoText);

        LinearLayout buttonRow = new LinearLayout(service);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);

        Button deeperBtn = createButton(service.getString(R.string.picker_deeper),
                Color.argb(200, 60, 60, 60), btnTextColor);
        deeperBtn.setOnClickListener(v -> cycleDeeper());
        buttonRow.addView(deeperBtn, createButtonParams());

        Button shallowerBtn = createButton(service.getString(R.string.picker_shallower),
                Color.argb(200, 60, 60, 60), btnTextColor);
        shallowerBtn.setOnClickListener(v -> cycleShallower());
        buttonRow.addView(shallowerBtn, createButtonParams());

        Button blockBtn = createButton(service.getString(R.string.picker_block),
                Color.argb(200, 200, 40, 40), btnTextColor);
        blockBtn.setOnClickListener(v -> confirmBlock());
        buttonRow.addView(blockBtn, createButtonParams());

        Button blockAllBtn = createButton(service.getString(R.string.picker_block_all),
                Color.argb(200, 200, 80, 40), btnTextColor);
        blockAllBtn.setOnClickListener(v -> confirmBlockAll());
        buttonRow.addView(blockAllBtn, createButtonParams());

        Button moveBtn = createButton(service.getString(R.string.picker_move),
                Color.argb(200, 100, 100, 100), btnTextColor);
        moveBtn.setOnClickListener(v -> toggleControlBarPosition());
        buttonRow.addView(moveBtn, createButtonParams());

        Button cancelBtn = createButton(service.getString(R.string.picker_cancel),
                Color.argb(200, 100, 100, 100), btnTextColor);
        cancelBtn.setOnClickListener(v -> dismissPicker());
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

    private void dismissPicker() {
        hide();
        listener.onPickerDismissed();
    }

    private void toggleControlBarPosition() {
        if (controlBar != null) {
            isAtBottom = !isAtBottom;
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) controlBar.getLayoutParams();
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

    private void handleTap(float x, float y) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "No root window available");
            return;
        }

        try {
            currentPackageName = root.getPackageName() != null ? root.getPackageName().toString() : "";
            recycleNodes();
            currentRootNode = AccessibilityNodeInfo.obtain(root);
            collectNodesAtPoint(root, (int) x, (int) y, nodesAtPoint);

            if (nodesAtPoint.isEmpty()) {
                updateInfo(service.getString(R.string.picker_no_element));
                hideHighlight();
                return;
            }

            currentNodeIndex = nodesAtPoint.size() - 1;
            highlightCurrentNode();
        } catch (Exception e) {
            Log.e(TAG, "Error handling tap", e);
        } finally {
            root.recycle();
        }
    }

    private void collectNodesAtPoint(AccessibilityNodeInfo node, int x, int y,
                                     List<AccessibilityNodeInfo> result) {
        if (node == null || !node.isVisibleToUser()) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (bounds.contains(x, y)) {
            result.add(AccessibilityNodeInfo.obtain(node));
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

    private void cycleDeeper() {
        if (nodesAtPoint.isEmpty()) return;
        if (currentNodeIndex < nodesAtPoint.size() - 1) {
            currentNodeIndex++;
            highlightCurrentNode();
        } else {
            Toast.makeText(service, R.string.picker_deepest, Toast.LENGTH_SHORT).show();
        }
    }

    private void cycleShallower() {
        if (nodesAtPoint.isEmpty()) return;
        if (currentNodeIndex > 0) {
            currentNodeIndex--;
            highlightCurrentNode();
        } else {
            Toast.makeText(service, R.string.picker_shallowest, Toast.LENGTH_SHORT).show();
        }
    }

    private void highlightCurrentNode() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            hideHighlight();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (highlightView != null && !bounds.isEmpty()) {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) highlightView.getLayoutParams();
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

    private void confirmBlock() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            Toast.makeText(service, R.string.picker_no_element, Toast.LENGTH_SHORT).show();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        String selectorDesc =
                ElementPickerRuleGenerator.getSelectorDescription(node, currentRootNode);
        String generatedRule =
                ElementPickerRuleGenerator.generateRule(node, currentRootNode, currentPackageName,
                        null);
        showConfirmationOverlay(node, selectorDesc, generatedRule, false);
    }

    private void confirmBlockAll() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            Toast.makeText(service, R.string.picker_no_element, Toast.LENGTH_SHORT).show();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        String selectorDesc = "All similar elements";
        String generatedRule = ElementPickerRuleGenerator.generateRuleForAll(node, currentRootNode,
                currentPackageName, null);
        showConfirmationOverlay(node, selectorDesc, generatedRule, true);
    }

    private void showConfirmationOverlay(AccessibilityNodeInfo node, String selectorDesc,
                                         String generatedRule, boolean isBlockAll) {
        boolean isDarkMode = (service.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int bgColor = isDarkMode ? Color.argb(245, 30, 30, 30) : Color.argb(245, 255, 255, 255);
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;
        int secondaryTextColor =
                isDarkMode ? Color.argb(180, 255, 255, 255) : Color.argb(180, 0, 0, 0);

        FrameLayout container = new FrameLayout(service);
        container.setBackgroundColor(Color.argb(120, 0, 0, 0));

        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(bgColor);
        int pad = dpToPx(20);
        card.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        cardParams.leftMargin = dpToPx(24);
        cardParams.rightMargin = dpToPx(24);

        TextView title = new TextView(service);
        title.setText(isBlockAll ? R.string.picker_confirm_all_title : R.string.picker_confirm_title);
        title.setTextColor(textColor);
        title.setTextSize(18f);
        title.setPadding(0, 0, 0, dpToPx(12));
        card.addView(title);

        TextView selectorLabel = new TextView(service);
        selectorLabel.setText(service.getString(R.string.picker_selector_label, selectorDesc));
        selectorLabel.setTextColor(secondaryTextColor);
        selectorLabel.setTextSize(13f);
        selectorLabel.setPadding(0, 0, 0, dpToPx(8));
        card.addView(selectorLabel);

        TextView rulePreview = new TextView(service);
        rulePreview.setText(service.getString(R.string.picker_rule_preview, generatedRule));
        rulePreview.setTextColor(secondaryTextColor);
        rulePreview.setTextSize(12f);
        rulePreview.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        rulePreview.setBackgroundColor(
                isDarkMode ? Color.argb(60, 255, 255, 255) : Color.argb(30, 0, 0, 0));
        card.addView(rulePreview);

        TextView commentLabel = new TextView(service);
        commentLabel.setText(R.string.picker_comment_label);
        commentLabel.setTextColor(textColor);
        commentLabel.setTextSize(14f);
        commentLabel.setPadding(0, dpToPx(12), 0, dpToPx(4));
        card.addView(commentLabel);

        EditText commentInput = new EditText(service);
        commentInput.setHint(R.string.picker_comment_hint);
        commentInput.setTextColor(textColor);
        commentInput.setHintTextColor(secondaryTextColor);
        commentInput.setTextSize(14f);
        commentInput.setSingleLine(true);
        card.addView(commentInput);

        LinearLayout btnRow = new LinearLayout(service);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dpToPx(16), 0, 0);

        Button cancelBtn = createButton(service.getString(R.string.picker_dialog_cancel),
                isDarkMode ? Color.argb(200, 80, 80, 80) : Color.argb(200, 200, 200, 200),
                isDarkMode ? Color.WHITE : Color.BLACK);
        cancelBtn.setOnClickListener(v -> removeSafely(container));

        Button confirmBtn = createButton(service.getString(R.string.picker_dialog_confirm),
                Color.argb(200, 200, 40, 40), Color.WHITE);
        confirmBtn.setOnClickListener(v -> {
            String comment = commentInput.getText().toString().trim();
            if (comment.isEmpty()) {
                comment = selectorDesc;
            }

            String finalRule = isBlockAll
                    ? ElementPickerRuleGenerator.generateRuleForAll(node, currentRootNode,
                    currentPackageName, comment)
                    : ElementPickerRuleGenerator.generateRule(node, currentRootNode,
                    currentPackageName, comment);
            listener.onRuleChosen(finalRule);
            lastAppliedRule = finalRule;
            removeSafely(container);
            hideHighlight();
            recycleNodes();
            showUndoBar(comment);
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(dpToPx(8), 0, 0, 0);

        btnRow.addView(cancelBtn, btnParams);
        btnRow.addView(confirmBtn, btnParams);
        card.addView(btnRow);
        container.addView(card, cardParams);

        container.setOnClickListener(v -> removeSafely(container));
        card.setOnClickListener(v -> { });

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(container, overlayParams);
    }

    private void showUndoBar(String ruleDescription) {
        removeUndoBar();

        boolean isDarkMode = (service.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int bgColor = isDarkMode ? Color.argb(240, 50, 50, 50) : Color.argb(240, 40, 40, 40);

        undoBar = new LinearLayout(service);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setBackgroundColor(bgColor);
        int hPad = dpToPx(16);
        int vPad = dpToPx(12);
        undoBar.setPadding(hPad, vPad, hPad, vPad);

        TextView message = new TextView(service);
        String text = service.getString(R.string.picker_rule_applied, ruleDescription);
        message.setText(text);
        message.setTextColor(Color.WHITE);
        message.setTextSize(13f);
        message.setSingleLine(true);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        undoBar.addView(message, msgParams);

        Button undoBtn = new Button(service);
        undoBtn.setText(R.string.picker_undo);
        undoBtn.setTextSize(13f);
        undoBtn.setAllCaps(true);
        undoBtn.setBackgroundColor(Color.TRANSPARENT);
        undoBtn.setTextColor(Color.argb(255, 100, 180, 255));
        undoBtn.setPadding(dpToPx(12), 0, dpToPx(4), 0);
        undoBtn.setOnClickListener(v -> {
            if (lastAppliedRule != null) {
                listener.onRuleUndone(lastAppliedRule);
                lastAppliedRule = null;
            }
            removeUndoBar();
            updateInfo(service.getString(R.string.picker_hint));
        });
        undoBar.addView(undoBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Show undo bar on opposite side of control bar to avoid overlap
        params.gravity = (isAtBottom ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START;

        windowManager.addView(undoBar, params);

        undoAutoHideRunnable = () -> {
            removeUndoBar();
            lastAppliedRule = null;
        };
        ui.postDelayed(undoAutoHideRunnable, UNDO_TIMEOUT_MS);
    }

    private void removeUndoBar() {
        if (undoAutoHideRunnable != null) {
            ui.removeCallbacks(undoAutoHideRunnable);
            undoAutoHideRunnable = null;
        }
        if (undoBar != null) {
            removeSafely(undoBar);
            undoBar = null;
        }
    }

    private int dpToPx(int dp) {
        float density = service.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
