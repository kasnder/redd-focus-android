package net.kollnig.distractionlib;

import android.accessibilityservice.AccessibilityService;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import java.util.EnumSet;
import java.util.List;

/**
 * Full-screen transparent overlay that intercepts touches and allows the user
 * to select accessibility nodes to block. Supports tap-to-cycle through
 * overlapping elements at the same touch point.
 */
public class ElementPickerOverlay {
    private static final String TAG = "ElementPickerOverlay";

    /** What the picked element should be turned into. */
    public enum Mode {
        /** Cover this one element. */
        BLOCK,
        /** Cover every element shaped like this one. */
        BLOCK_ALL,
        /** Click this element whenever the app is opened. */
        NAVIGATE
    }

    public interface Listener {
        void onRuleChosen(String ruleString);

        void onRuleUndone(String ruleString);

        /** A rule that opens a screen rather than hiding one; stored separately. */
        void onNavigationRuleChosen(String ruleString);

        void onNavigationRuleUndone(String ruleString);

        void onPickerDismissed();

        /** Completes a successful pick; unlike cancellation this may return to app UI. */
        void onPickerDone(String packageName);
    }

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View touchInterceptor;
    private View highlightView;
    private LinearLayout controlBar;
    private TextView infoText;
    private TextView technicalInfoText;
    private TextView broadMatchText;
    private Button blockAllButton;
    private Button moveButton;
    // This full-screen window must be owned so service teardown cannot strand it over the UI.
    private View confirmationOverlay;
    private final List<Button> selectionDependentButtons = new ArrayList<>();

    private final List<AccessibilityNodeInfo> nodesAtPoint = new ArrayList<>();
    private int currentNodeIndex = 0;
    private String currentPackageName = "";
    private AccessibilityNodeInfo currentRootNode = null;
    private boolean isActive = false;
    private boolean isAtBottom = true;
    private String lastAppliedRule = null;
    private Mode lastAppliedMode = Mode.BLOCK;
    private String targetPackageName;
    private EnumSet<Mode> allowedModes = EnumSet.allOf(Mode.class);
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
        show(null, EnumSet.allOf(Mode.class));
    }

    /**
     * Arms the picker for one app and one kind of action. A null package preserves the
     * notification entry point, which intentionally works in any foreground app.
     */
    public void show(String forPackage, EnumSet<Mode> actions) {
        if (isActive) return;
        targetPackageName = forPackage;
        allowedModes = actions == null || actions.isEmpty()
                ? EnumSet.noneOf(Mode.class) : EnumSet.copyOf(actions);
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
            removeSafely(confirmationOverlay);
            confirmationOverlay = null;
            removeSafely(touchInterceptor);
            removeSafely(highlightView);
            removeSafely(controlBar);
            touchInterceptor = null;
            highlightView = null;
            controlBar = null;
            infoText = null;
            technicalInfoText = null;
            broadMatchText = null;
            blockAllButton = null;
            moveButton = null;
            selectionDependentButtons.clear();
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

        int bgColor = isDarkMode ? Color.rgb(35, 35, 35) : Color.WHITE;
        int textColor = isDarkMode ? Color.WHITE : Color.BLACK;
        int secondaryTextColor = isDarkMode
                ? Color.rgb(190, 190, 190) : Color.rgb(106, 113, 131);
        int primaryColor = isDarkMode ? Color.rgb(142, 155, 232) : Color.rgb(43, 60, 168);

        controlBar = new LinearLayout(service);
        controlBar.setOrientation(LinearLayout.VERTICAL);
        setControlBarBackground(bgColor);
        controlBar.setElevation(dpToPx(12));
        int pad = dpToPx(16);
        controlBar.setPadding(pad, pad, pad, pad);

        infoText = new TextView(service);
        infoText.setTextColor(textColor);
        infoText.setTextSize(18f);
        infoText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        infoText.setText(R.string.picker_hint);
        infoText.setMaxLines(2);
        controlBar.addView(infoText);

        technicalInfoText = new TextView(service);
        technicalInfoText.setTextColor(secondaryTextColor);
        technicalInfoText.setTextSize(12f);
        technicalInfoText.setMaxLines(1);
        technicalInfoText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        technicalInfoText.setVisibility(View.GONE);
        LinearLayout.LayoutParams technicalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        technicalParams.topMargin = dpToPx(4);
        controlBar.addView(technicalInfoText, technicalParams);

        LinearLayout selectionRow = new LinearLayout(service);
        selectionRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionRow.setPadding(0, dpToPx(12), 0, 0);
        Button selectLess = createSecondaryButton(service.getString(R.string.picker_deeper),
                textColor, v -> cycleDeeper());
        Button selectMore = createSecondaryButton(service.getString(R.string.picker_shallower),
                textColor, v -> cycleShallower());
        selectLess.setEnabled(false);
        selectMore.setEnabled(false);
        selectLess.setAlpha(0.45f);
        selectMore.setAlpha(0.45f);
        selectionDependentButtons.add(selectLess);
        selectionDependentButtons.add(selectMore);
        selectionRow.addView(selectLess, createButtonParams());
        selectionRow.addView(selectMore, createButtonParams());
        controlBar.addView(selectionRow);

        LinearLayout panelRow = new LinearLayout(service);
        panelRow.setOrientation(LinearLayout.HORIZONTAL);
        panelRow.setPadding(0, dpToPx(4), 0, 0);
        // Always available: moving the sheet is how the user selects content initially under it.
        moveButton = createSecondaryButton("", textColor, v -> toggleControlBarPosition());
        updateMoveButtonLabel();
        panelRow.addView(moveButton, createButtonParams());
        Button closeButton = createSecondaryButton(service.getString(R.string.picker_close),
                textColor, v -> dismissPicker());
        closeButton.setContentDescription(service.getString(R.string.picker_close));
        panelRow.addView(closeButton, createButtonParams());
        controlBar.addView(panelRow);

        LinearLayout actions = new LinearLayout(service);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dpToPx(12), 0, 0);

        broadMatchText = new TextView(service);
        broadMatchText.setTextColor(secondaryTextColor);
        broadMatchText.setTextSize(12f);
        broadMatchText.setVisibility(View.GONE);
        LinearLayout.LayoutParams broadMatchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        broadMatchParams.bottomMargin = dpToPx(8);
        actions.addView(broadMatchText, broadMatchParams);

        if (allowedModes.contains(Mode.BLOCK_ALL)) {
            blockAllButton = createPrimaryButton(service.getString(R.string.picker_block_all),
                    primaryColor);
            blockAllButton.setEnabled(false);
            blockAllButton.setAlpha(0.45f);
            blockAllButton.setOnClickListener(v -> confirmBlockAll());
            selectionDependentButtons.add(blockAllButton);
            actions.addView(blockAllButton, fullWidthButtonParams(0));
        }
        if (allowedModes.contains(Mode.BLOCK)) {
            Button blockBtn = createSecondaryButton(service.getString(R.string.picker_block),
                    textColor, v -> confirmBlock());
            blockBtn.setEnabled(false);
            selectionDependentButtons.add(blockBtn);
            actions.addView(blockBtn, fullWidthButtonParams(
                    allowedModes.contains(Mode.BLOCK_ALL) ? dpToPx(8) : 0));
        }
        if (allowedModes.contains(Mode.NAVIGATE)) {
            Button openBtn = createPrimaryButton(service.getString(R.string.picker_open),
                    primaryColor);
            openBtn.setEnabled(false);
            openBtn.setOnClickListener(v -> confirmNavigate());
            selectionDependentButtons.add(openBtn);
            actions.addView(openBtn, fullWidthButtonParams(
                    allowedModes.contains(Mode.BLOCK) || allowedModes.contains(Mode.BLOCK_ALL)
                            ? dpToPx(8) : 0));
        }
        controlBar.addView(actions);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = controlBarGravity(isAtBottom);

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
            params.gravity = controlBarGravity(isAtBottom);
            windowManager.updateViewLayout(controlBar, params);
            boolean isDarkMode = (service.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            setControlBarBackground(isDarkMode ? Color.rgb(35, 35, 35) : Color.WHITE);
            updateMoveButtonLabel();
            repositionUndoBar();
        }
    }

    static int controlBarGravity(boolean atBottom) {
        return (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.START;
    }

    static int undoBarGravity(boolean controlBarAtBottom) {
        return (controlBarAtBottom ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START;
    }

    private void setControlBarBackground(int color) {
        if (controlBar == null) {
            return;
        }
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        float radius = dpToPx(24);
        surface.setCornerRadii(isAtBottom
                ? new float[]{radius, radius, radius, radius, 0, 0, 0, 0}
                : new float[]{0, 0, 0, 0, radius, radius, radius, radius});
        controlBar.setBackground(surface);
    }

    private void updateMoveButtonLabel() {
        if (moveButton != null) {
            moveButton.setText(isAtBottom
                    ? R.string.picker_move_to_top : R.string.picker_move_to_bottom);
        }
    }

    private Button createSecondaryButton(String label, int textColor, View.OnClickListener onClick) {
        Button btn = new Button(service);
        btn.setText(label);
        btn.setTextSize(14f);
        btn.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setStroke(dpToPx(1), Color.argb(70,
                Color.red(textColor), Color.green(textColor), Color.blue(textColor)));
        background.setCornerRadius(dpToPx(24));
        btn.setBackground(background);
        btn.setTextColor(textColor);
        btn.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        btn.setMinHeight(dpToPx(48));
        btn.setMinimumHeight(dpToPx(48));
        btn.setOnClickListener(onClick);
        return btn;
    }

    private Button createPrimaryButton(String text, int bgColor) {
        Button btn = new Button(service);
        btn.setText(text);
        btn.setTextSize(14f);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(dpToPx(24));
        btn.setBackground(background);
        btn.setTextColor(Color.WHITE);
        int hPad = dpToPx(12);
        int vPad = dpToPx(6);
        btn.setPadding(hPad, vPad, hPad, vPad);
        btn.setMinHeight(dpToPx(48));
        btn.setMinimumHeight(dpToPx(48));
        return btn;
    }

    private LinearLayout.LayoutParams createButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams fullWidthButtonParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
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
            if (targetPackageName != null && !targetPackageName.equals(currentPackageName)) {
                recycleNodes();
                updateInfo(service.getString(R.string.picker_wrong_app));
                clearSelectionDetails();
                hideHighlight();
                return;
            }
            recycleNodes();
            currentRootNode = AccessibilityNodeInfo.obtain(root);
            collectNodesAtPoint(root, (int) x, (int) y, nodesAtPoint);

            if (nodesAtPoint.isEmpty()) {
                updateInfo(service.getString(R.string.picker_no_element));
                clearSelectionDetails();
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

        String description = ElementPickerRuleGenerator.plainLanguageDescription(node);
        String depth = "(" + (currentNodeIndex + 1) + "/" + nodesAtPoint.size() + ")";
        updateInfo(description);
        updateTechnicalInfo(depth + " " + ElementPickerRuleGenerator.describeNode(node));
        setSelectionControlsEnabled(true);
        updateBlockAllButton(node);
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

    private void updateTechnicalInfo(String text) {
        if (technicalInfoText != null) {
            technicalInfoText.setText(text);
            technicalInfoText.setVisibility(View.VISIBLE);
        }
    }

    private void clearSelectionDetails() {
        if (technicalInfoText != null) {
            technicalInfoText.setText(null);
            technicalInfoText.setVisibility(View.GONE);
        }
        if (broadMatchText != null) {
            broadMatchText.setVisibility(View.GONE);
        }
        if (blockAllButton != null) {
            blockAllButton.setText(R.string.picker_block_all);
        }
        setSelectionControlsEnabled(false);
    }

    private void setSelectionControlsEnabled(boolean enabled) {
        for (Button button : selectionDependentButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void updateBlockAllButton(AccessibilityNodeInfo node) {
        if (blockAllButton == null || currentRootNode == null) return;
        int matches = ElementPickerRuleGenerator.countGeneralizedSiblingMatches(node);
        boolean tooBroad = ElementPickerRuleGenerator.refusesFullScreenSelection(node,
                currentRootNode);
        blockAllButton.setText(service.getString(R.string.picker_block_all_count, matches));
        blockAllButton.setEnabled(matches > 0 && !tooBroad);
        blockAllButton.setAlpha(matches > 0 && !tooBroad ? 1f : 0.45f);
        blockAllButton.setContentDescription(tooBroad
                ? service.getString(R.string.picker_block_all_too_broad)
                : service.getString(R.string.picker_block_all_count, matches));
        if (broadMatchText != null) {
            broadMatchText.setText(tooBroad
                    ? service.getString(R.string.picker_block_all_too_broad)
                    : service.getResources().getQuantityString(
                            R.plurals.picker_similar_matches, matches, matches));
            broadMatchText.setVisibility(matches > 0 ? View.VISIBLE : View.GONE);
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
        showConfirmationOverlay(node, selectorDesc, generatedRule, Mode.BLOCK);
    }

    private void confirmBlockAll() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            Toast.makeText(service, R.string.picker_no_element, Toast.LENGTH_SHORT).show();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        if (blockAllButton != null && !blockAllButton.isEnabled()) {
            Toast.makeText(service, R.string.picker_block_all_too_broad, Toast.LENGTH_LONG).show();
            return;
        }
        String selectorDesc = "All similar elements";
        String generatedRule = ElementPickerRuleGenerator.generateRuleForAll(node, currentRootNode,
                currentPackageName, null);
        showConfirmationOverlay(node, selectorDesc, generatedRule, Mode.BLOCK_ALL);
    }

    private void confirmNavigate() {
        if (nodesAtPoint.isEmpty() || currentNodeIndex >= nodesAtPoint.size()) {
            Toast.makeText(service, R.string.picker_no_element, Toast.LENGTH_SHORT).show();
            return;
        }

        AccessibilityNodeInfo node = nodesAtPoint.get(currentNodeIndex);
        ElementPickerRuleGenerator.NavigationSelector selector =
                ElementPickerRuleGenerator.navigationSelector(node, currentRootNode);
        if (selector == null) {
            // Refused rather than approximated: this element has nothing stable enough to
            // identify, and a rule built on a guess would eventually tap something else.
            Toast.makeText(service, R.string.picker_open_unsupported, Toast.LENGTH_LONG).show();
            return;
        }

        String generatedRule = ElementPickerRuleGenerator.generateNavigationRule(
                node, currentRootNode, currentPackageName, null);

        if (!isUnambiguous(generatedRule)) {
            // Matching a description loosely is only safe while it still names one element.
            Toast.makeText(service, R.string.picker_open_ambiguous, Toast.LENGTH_LONG).show();
            return;
        }

        showConfirmationOverlay(node, selector.description, generatedRule, Mode.NAVIGATE);
    }

    /**
     * Whether a description-based navigation rule picks out exactly one element on the screen
     * it was built from. View-ID and anchored-path rules address a node directly and are left
     * alone; only a description can be widened by the comparison mode into naming several.
     */
    private boolean isUnambiguous(String generatedRule) {
        if (generatedRule == null || currentRootNode == null) {
            return false;
        }
        List<FilterRule> parsed = new FilterRuleParser().parseRules(new String[]{generatedRule});
        if (parsed.isEmpty()) {
            return false;
        }
        FilterRule rule = parsed.get(0);
        if (rule.contentDescriptions == null || rule.contentDescriptions.isEmpty()) {
            return true;
        }
        return ElementPickerRuleGenerator.countDescriptionMatches(currentRootNode, rule) == 1;
    }

    private void showConfirmationOverlay(AccessibilityNodeInfo node, String selectorDesc,
                                         String generatedRule, Mode mode) {
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
        title.setText(mode == Mode.BLOCK_ALL
                ? R.string.picker_confirm_all_title
                : mode == Mode.NAVIGATE
                ? R.string.picker_confirm_open_title
                : R.string.picker_confirm_title);
        title.setTextColor(textColor);
        title.setTextSize(18f);
        title.setPadding(0, 0, 0, dpToPx(12));
        card.addView(title);

        // What will actually happen, before anything about how it is matched. The card used to
        // open with a selector and a raw rule string, which answers a question the user asking
        // "what does this button do?" was not the one asking.
        TextView effect = new TextView(service);
        effect.setText(mode == Mode.BLOCK_ALL
                ? R.string.picker_effect_block_all
                : mode == Mode.NAVIGATE
                ? R.string.picker_effect_navigate
                : R.string.picker_effect_block);
        effect.setTextColor(textColor);
        effect.setTextSize(14f);
        effect.setPadding(0, 0, 0, dpToPx(12));
        card.addView(effect);

        TextView selectorLabel = new TextView(service);
        selectorLabel.setText(service.getString(R.string.picker_selector_label, selectorDesc));
        selectorLabel.setTextColor(secondaryTextColor);
        selectorLabel.setTextSize(13f);
        selectorLabel.setPadding(0, 0, 0, dpToPx(8));
        card.addView(selectorLabel);

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

        // Kept, because it is what someone would paste into Custom Rules or a bug report, but
        // demoted below the decision: it is the answer to a question almost nobody is asking
        // at this moment, and it read as the main content when it sat at the top.
        TextView rulePreview = new TextView(service);
        rulePreview.setText(service.getString(R.string.picker_rule_preview, generatedRule));
        rulePreview.setTextColor(secondaryTextColor);
        rulePreview.setTextSize(11f);
        rulePreview.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        rulePreview.setBackgroundColor(
                isDarkMode ? Color.argb(60, 255, 255, 255) : Color.argb(30, 0, 0, 0));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.topMargin = dpToPx(16);
        card.addView(rulePreview, previewParams);

        LinearLayout btnRow = new LinearLayout(service);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dpToPx(16), 0, 0);

        Button cancelBtn = createSecondaryButton(service.getString(R.string.picker_dialog_cancel),
                isDarkMode ? Color.WHITE : Color.BLACK, v -> removeConfirmationOverlay(container));

        // The confirm button names the action it performs. It previously read "Block" in every
        // mode, so the Open flow ended on a red button offering to block.
        int confirmLabel = mode == Mode.BLOCK_ALL
                ? R.string.picker_dialog_confirm_all
                : mode == Mode.NAVIGATE
                ? R.string.picker_dialog_confirm_open
                : R.string.picker_dialog_confirm;
        Button confirmBtn = createPrimaryButton(service.getString(confirmLabel),
                mode == Mode.NAVIGATE
                        ? Color.argb(200, 40, 120, 200)
                        : Color.argb(200, 200, 40, 40));
        confirmBtn.setOnClickListener(v -> {
            String comment = commentInput.getText().toString().trim();
            if (comment.isEmpty()) {
                comment = selectorDesc;
            }

            String finalRule;
            switch (mode) {
                case BLOCK_ALL:
                    finalRule = ElementPickerRuleGenerator.generateRuleForAll(node,
                            currentRootNode, currentPackageName, comment);
                    break;
                case NAVIGATE:
                    finalRule = ElementPickerRuleGenerator.generateNavigationRule(node,
                            currentRootNode, currentPackageName, comment);
                    break;
                default:
                    finalRule = ElementPickerRuleGenerator.generateRule(node, currentRootNode,
                            currentPackageName, comment);
                    break;
            }

            if (mode == Mode.NAVIGATE) {
                listener.onNavigationRuleChosen(finalRule);
            } else {
                listener.onRuleChosen(finalRule);
            }
            lastAppliedRule = finalRule;
            lastAppliedMode = mode;
            removeConfirmationOverlay(container);
            hideHighlight();
            recycleNodes();
            updateInfo(service.getString(R.string.picker_hint));
            clearSelectionDetails();
            showUndoBar(comment);
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(dpToPx(8), 0, 0, 0);

        btnRow.addView(cancelBtn, btnParams);
        btnRow.addView(confirmBtn, btnParams);
        card.addView(btnRow);
        container.addView(card, cardParams);

        container.setOnClickListener(v -> removeConfirmationOverlay(container));
        card.setOnClickListener(v -> { });

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        removeSafely(confirmationOverlay);
        confirmationOverlay = container;
        windowManager.addView(container, overlayParams);
    }

    private void removeConfirmationOverlay(View overlay) {
        removeSafely(overlay);
        if (confirmationOverlay == overlay) {
            confirmationOverlay = null;
        }
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
        String text = service.getString(lastAppliedMode == Mode.NAVIGATE
                ? R.string.picker_rule_applied_open
                : R.string.picker_rule_applied, ruleDescription);
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
                // Navigation rules live in their own store, so undo has to go back to the same
                // one the rule was written to.
                if (lastAppliedMode == Mode.NAVIGATE) {
                    listener.onNavigationRuleUndone(lastAppliedRule);
                } else {
                    listener.onRuleUndone(lastAppliedRule);
                }
                lastAppliedRule = null;
            }
            removeUndoBar();
            updateInfo(service.getString(R.string.picker_hint));
        });
        undoBar.addView(undoBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button doneBtn = new Button(service);
        doneBtn.setText(R.string.picker_done);
        doneBtn.setTextSize(13f);
        doneBtn.setAllCaps(true);
        doneBtn.setBackgroundColor(Color.TRANSPARENT);
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setPadding(dpToPx(12), 0, dpToPx(4), 0);
        doneBtn.setOnClickListener(v -> {
            String packageName = currentPackageName;
            hide();
            listener.onPickerDone(packageName);
        });
        undoBar.addView(doneBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = undoBarGravity(isAtBottom);

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

    private void repositionUndoBar() {
        if (undoBar == null) {
            return;
        }
        try {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) undoBar.getLayoutParams();
            params.gravity = undoBarGravity(isAtBottom);
            windowManager.updateViewLayout(undoBar, params);
        } catch (Exception e) {
            Log.e(TAG, "Error moving undo bar", e);
        }
    }

    private int dpToPx(int dp) {
        float density = service.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
