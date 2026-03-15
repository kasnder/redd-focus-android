package net.kollnig.greasemilkyway;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * An accessibility service that helps control distractions by blocking specific
 * content in Android apps
 * using an ad-blocker style filter syntax.
 */
public class DistractionControlService extends AccessibilityService {
    private static final String TAG = "DistractionControlService";
    private static final int MAX_OVERLAY_COUNT = 100; // Prevent memory issues

    // Singleton instance
    private static DistractionControlService instance;
    private final List<FilterRule> rules = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OverlayManager overlayManager = new OverlayManager();
    private final Map<String, BlockedElement> blockedElements = new HashMap<>();
    private WindowManager windowManager;
    private ElementPickerNotification pickerNotification;
    private ElementPickerOverlay pickerOverlay;
    private BroadcastReceiver pickerReceiver;
    private final Set<String> activeRuleKeys = new java.util.HashSet<>();
    private final Runnable processEvent = () -> {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.w(TAG, "No root window available");
                return;
            }
            try {
                // Track which rule keys are matched in this pass
                activeRuleKeys.clear();

                processRootNode(root);

                // Remove overlays for rules that were NOT matched this pass
                List<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, BlockedElement> entry : blockedElements.entrySet()) {
                    if (!activeRuleKeys.contains(entry.getKey())) {
                        toRemove.add(entry.getKey());
                    }
                }
                for (String key : toRemove) {
                    BlockedElement element = blockedElements.remove(key);
                    if (element != null) {
                        overlayManager.removeOverlay(element.overlay, windowManager, ui);
                    }
                }
            } finally {
                root.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing accessibility event", e);
        }
    };
    private ServiceConfig config;
    private LayoutDumper layoutDumper;
    private boolean isDarkMode;
    private String cachedLauncherPackage;

    /**
     * Get the current instance of the service.
     *
     * @return The current service instance, or null if the service is not running
     */
    public static DistractionControlService getInstance() {
        return instance;
    }

    /**
     * Update the rules in the service and clear any existing overlays.
     * This should be called whenever rules are modified in the UI.
     */
    public void updateRules() {
        if (instance == null)
            return;
        rules.clear();
        rules.addAll(config.getRules());
        clearAllOverlays();
        configureAccessibilityService();
        Log.i(TAG, "Rules updated, now have " + rules.size() + " rule(s)");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "Failed to get WindowManager service");
                return;
            }
            config = new ServiceConfig(this);
            rules.clear();
            rules.addAll(config.getRules());
            updateDarkMode();
            configureAccessibilityService();
            Log.i(TAG, "Accessibility service initialized with " + rules.size() + " rule(s)");

            layoutDumper = new LayoutDumper();
            layoutDumper.start();

            // Initialize picker notification (don't show it automatically;
            // it will be shown on demand when user taps the FAB)
            pickerNotification = new ElementPickerNotification(this);

            // Initialize picker overlay
            pickerOverlay = new ElementPickerOverlay(this, windowManager);

            // Register broadcast receiver for notification actions
            pickerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ElementPickerNotification.ACTION_START_PICKER.equals(action)) {
                        startPickerMode();
                    } else if (ElementPickerNotification.ACTION_STOP_PICKER.equals(action)) {
                        stopPickerMode();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ElementPickerNotification.ACTION_START_PICKER);
            filter.addAction(ElementPickerNotification.ACTION_STOP_PICKER);
            registerReceiver(pickerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing service", e);
        }
    }

    private void configureAccessibilityService() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                Log.e(TAG, "Failed to get service info");
                return;
            }
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;

            // Dynamically set packages from enabled rules
            Set<String> packages = new java.util.HashSet<>();
            for (FilterRule rule : rules) {
                if (rule.enabled) {
                    packages.add(rule.packageName);
                }
            }

            if (!packages.isEmpty()) {
                // Always include launcher and systemui so we can detect
                // home-screen / lock-screen transitions and clear overlays
                packages.add("com.android.systemui");
                String launcher = getLauncherPackage();
                if (launcher != null) {
                    packages.add(launcher);
                }
                info.packageNames = packages.toArray(new String[0]);
            } else {
                info.packageNames = null;
            }

            setServiceInfo(info);
            Log.i(TAG, "Package filter updated: " + packages);
        } catch (Exception e) {
            Log.e(TAG, "Error configuring accessibility service", e);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDarkMode();
    }

    private void updateDarkMode() {
        isDarkMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (instance == null)
            return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // Our own app, launcher, and systemui never host rule targets.
        // On WINDOW_STATE_CHANGED, cancel any queued processing and immediately
        // destroy all overlays. All other event types are dropped outright.
        if (packageName.equals(getPackageName())
                || packageName.equals("com.android.systemui")
                || isLauncherPackage(packageName)) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "Clearing overlays due to " + packageName);
                ui.removeCallbacks(processEvent);
                forceClearAllOverlays();
            }
            return;
        }

        if (!shouldProcessEvent(event))
            return;

        // Skip normal rule processing when picker is active
        if (pickerOverlay != null && pickerOverlay.isActive())
            return;

        ui.removeCallbacks(processEvent);
        ui.post(processEvent);
    }

    /**
     * Resolve and cache the default launcher package name.
     */
    private String getLauncherPackage() {
        if (cachedLauncherPackage == null) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                android.content.pm.ResolveInfo resolveInfo =
                        getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolveInfo != null && resolveInfo.activityInfo != null) {
                    cachedLauncherPackage = resolveInfo.activityInfo.packageName;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error resolving launcher package", e);
            }
        }
        return cachedLauncherPackage;
    }

    private boolean isLauncherPackage(String packageName) {
        String launcher = getLauncherPackage();
        return launcher != null && launcher.equals(packageName);
    }

    private boolean shouldProcessEvent(AccessibilityEvent event) {
        if (event == null)
            return false;
        CharSequence pkg = event.getPackageName();
        if (pkg == null)
            return false;

        int eventType = event.getEventType();
        return (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)
                && hasMatchingRule(pkg);
    }

    private boolean hasMatchingRule(CharSequence packageName) {
        for (FilterRule rule : rules) {
            if (rule.enabled && rule.matchesPackage(packageName))
                return true;
        }
        return false;
    }

    private void processRootNode(AccessibilityNodeInfo root) {
        CharSequence packageName = root.getPackageName();
        if (packageName == null) {
            Log.w(TAG, "Root node has no package name");
            return;
        }

        for (FilterRule rule : rules) {
            if (rule.enabled && rule.matchesPackage(packageName)) {
                applyRule(rule, root);
            }
        }
    }

    private void applyRule(FilterRule rule, AccessibilityNodeInfo root) {
        if (root == null || !root.isVisibleToUser())
            return;

        // Handle path-based rules at the root level
        if (rule.targetPath != null && !rule.targetPath.isEmpty()) {
            List<AccessibilityNodeInfo> targets = matchPaths(root, rule.targetPath);
            for (int mi = 0; mi < targets.size(); mi++) {
                AccessibilityNodeInfo target = targets.get(mi);
                try {
                    processTargetView(target, rule, mi);
                } finally {
                    if (target != root) {
                        target.recycle();
                    }
                }
            }
            return;
        }

        // Fast path: use framework index lookup for viewId-based rules
        if (rule.targetViewId != null && !rule.targetViewId.isEmpty()) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(rule.targetViewId);
            if (matches != null) {
                for (AccessibilityNodeInfo match : matches) {
                    try {
                        if (match.isVisibleToUser()) {
                            processTargetView(match, rule);
                        }
                    } finally {
                        match.recycle();
                    }
                }
            }
            return;
        }

        // Fallback: recursive tree walk for other rule types
        applyRuleRecursive(rule, root);
    }

    private void applyRuleRecursive(FilterRule rule, AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser())
            return;

        if (isTargetView(node, rule)) {
            processTargetView(node, rule);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null)
                continue;
            try {
                applyRuleRecursive(rule, child);
            } finally {
                child.recycle();
            }
        }
    }

    private boolean isTargetView(AccessibilityNodeInfo node, FilterRule rule) {
        // Match by viewId if specified
        if (rule.targetViewId != null && !rule.targetViewId.isEmpty()) {
            String viewId = node.getViewIdResourceName();
            return viewId != null && viewId.equals(rule.targetViewId);
        }

        // Match by path if specified (preferred over desc/text/className)
        if (rule.targetPath != null && !rule.targetPath.isEmpty()) {
            // Path matching is done at the root level via matchPath()
            // Individual node matching returns false — the path is matched separately
            return false;
        }

        // Match by contentDescription if specified (backward compat)
        if (rule.contentDescriptions != null && !rule.contentDescriptions.isEmpty()) {
            CharSequence desc = node.getContentDescription();
            if (desc != null && rule.contentDescriptions.contains(desc.toString())) {
                return true;
            }
        }

        // Match by className if specified
        if (rule.targetClassName != null && !rule.targetClassName.isEmpty()) {
            CharSequence className = node.getClassName();
            if (className == null || !className.toString().equals(rule.targetClassName)) {
                return false;
            }
            if (rule.targetText != null && !rule.targetText.isEmpty()) {
                CharSequence text = node.getText();
                return text != null && text.toString().equals(rule.targetText);
            }
            return true;
        }

        // Match by text alone if specified
        if (rule.targetText != null && !rule.targetText.isEmpty()) {
            CharSequence text = node.getText();
            return text != null && text.toString().equals(rule.targetText);
        }

        return false;
    }

    /**
     * Match a path selector against the accessibility tree starting from root.
     * Path format: "ClassName[index]>ClassName[index]>..."
     * A segment with [*] matches ALL children with that className (wildcard).
     * Returns a list of matched nodes (may be empty).
     */
    private List<AccessibilityNodeInfo> matchPaths(AccessibilityNodeInfo root, String path) {
        List<AccessibilityNodeInfo> empty = new ArrayList<>();
        if (root == null || path == null || path.isEmpty())
            return empty;

        String[] segments = path.split(">");

        // Seed the walk with the root
        List<AccessibilityNodeInfo> currentNodes = new ArrayList<>();
        currentNodes.add(root);

        for (String segment : segments) {
            String className;
            boolean isWildcard = false;
            int index = 0;

            // Parse segment: "ClassName[index]", "ClassName[*]", or just "ClassName"
            int bracketStart = segment.indexOf('[');
            if (bracketStart >= 0) {
                className = segment.substring(0, bracketStart);
                String indexStr = segment.substring(bracketStart + 1, segment.indexOf(']'));
                if ("*".equals(indexStr)) {
                    isWildcard = true;
                } else {
                    try {
                        index = Integer.parseInt(indexStr);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid path index: " + indexStr);
                        // Recycle non-root nodes before returning
                        for (AccessibilityNodeInfo n : currentNodes) {
                            if (n != root) n.recycle();
                        }
                        return empty;
                    }
                }
            } else {
                className = segment;
            }

            List<AccessibilityNodeInfo> nextNodes = new ArrayList<>();

            for (AccessibilityNodeInfo current : currentNodes) {
                if (isWildcard) {
                    // Collect ALL children with matching className
                    for (int i = 0; i < current.getChildCount(); i++) {
                        AccessibilityNodeInfo child = current.getChild(i);
                        if (child == null) continue;
                        CharSequence childClass = child.getClassName();
                        if (childClass != null && childClass.toString().equals(className)) {
                            nextNodes.add(child);
                        } else {
                            child.recycle();
                        }
                    }
                } else {
                    // Find the nth child with matching className
                    AccessibilityNodeInfo match = null;
                    int matchCount = 0;
                    for (int i = 0; i < current.getChildCount(); i++) {
                        AccessibilityNodeInfo child = current.getChild(i);
                        if (child == null) continue;
                        CharSequence childClass = child.getClassName();
                        if (childClass != null && childClass.toString().equals(className)) {
                            if (matchCount == index) {
                                match = child;
                                break;
                            }
                            matchCount++;
                        }
                        child.recycle();
                    }
                    if (match != null) {
                        nextNodes.add(match);
                    }
                }

                // Recycle intermediate nodes (but never the root)
                if (current != root) {
                    current.recycle();
                }
            }

            currentNodes = nextNodes;
            if (currentNodes.isEmpty()) {
                return empty;
            }
        }

        return currentNodes;
    }

    private void processTargetView(AccessibilityNodeInfo node, FilterRule rule) {
        processTargetView(node, rule, -1);
    }

    private void processTargetView(AccessibilityNodeInfo node, FilterRule rule, int matchIndex) {
        if (rule.targetViewId == null || rule.contentDescriptions == null || rule.contentDescriptions.isEmpty()
                || rule.targetViewId.isEmpty()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                addOverlay(bounds, rule, matchIndex);
            }
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null)
                continue;
            try {
                if (subtreeContainsContentDescription(child, rule.contentDescriptions)) {
                    Rect bounds = new Rect();
                    child.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) {
                        addOverlay(bounds, rule);
                    }
                }
            } finally {
                child.recycle();
            }
        }
    }

    private boolean subtreeContainsContentDescription(AccessibilityNodeInfo node, Set<String> targets) {
        if (node == null)
            return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null && targets.contains(desc.toString()))
            return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null)
                continue;
            try {
                if (subtreeContainsContentDescription(child, targets))
                    return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    /**
     * Build a unique key for a rule to identify its overlay.
     * matchIndex distinguishes multiple matches of the same wildcard rule.
     */
    private String getRuleKey(FilterRule rule, int matchIndex) {
        String base = rule.packageName + "::" + rule.ruleString;
        return matchIndex >= 0 ? base + "::" + matchIndex : base;
    }

    private void addOverlay(Rect area, FilterRule rule) {
        addOverlay(area, rule, -1);
    }

    private void addOverlay(Rect area, FilterRule rule, int matchIndex) {
        String ruleKey = getRuleKey(rule, matchIndex);
        activeRuleKeys.add(ruleKey);

        // Check if an overlay already exists for this rule
        BlockedElement existing = blockedElements.get(ruleKey);
        if (existing != null) {
            // Bounds unchanged — skip entirely
            if (existing.bounds.equals(area)) {
                return;
            }
            // Bounds changed — reuse the existing overlay view
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (!rule.blockTouches) {
                flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    area.width(),
                    area.height(),
                    area.left,
                    area.top,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            overlayManager.updateOverlay(existing.overlay, lp, windowManager, ui);
            existing.bounds.set(area);
            return;
        }

        if (overlayManager.getOverlayCount() >= MAX_OVERLAY_COUNT) {
            Log.w(TAG, "Maximum overlay count reached, clearing old overlays");
            clearAllOverlays();
        }

        View blocker = new View(this);

        int color = rule.color;

        // Only change the default white color to black in dark mode
        // If a color was explicitly specified in the rule (including white), keep it as
        // is
        if (color == Color.WHITE && isDarkMode && !rule.ruleString.contains("color=")) {
            color = Color.BLACK;
        }

        blocker.setBackgroundColor(color);
        blocker.setAlpha(1f);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!rule.blockTouches) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                area.width(),
                area.height(),
                area.left,
                area.top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        overlayManager.addOverlay(blocker, lp, windowManager, ui);
        blockedElements.put(ruleKey, new BlockedElement(blocker, new Rect(area)));
    }

    private void clearAllOverlays() {
        overlayManager.clearOverlays(windowManager, ui);
        blockedElements.clear();
    }

    private void forceClearAllOverlays() {
        overlayManager.forceClearOverlays(windowManager);
        blockedElements.clear();
    }

    @Override
    public void onInterrupt() {
        if (layoutDumper != null) {
            layoutDumper.stop();
        }
        overlayManager.forceClearOverlays(windowManager);
        blockedElements.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (layoutDumper != null) {
            layoutDumper.stop();
        }
        if (pickerOverlay != null) {
            pickerOverlay.hide();
        }
        if (pickerNotification != null) {
            pickerNotification.cancelNotification();
        }
        if (pickerReceiver != null) {
            try {
                unregisterReceiver(pickerReceiver);
            } catch (Exception ignored) {
            }
        }
        overlayManager.forceClearOverlays(windowManager);
        blockedElements.clear();
    }

    /**
     * Start picker mode: show the picker overlay and update the notification.
     */
    public void startPickerMode() {
        if (pickerOverlay == null || pickerNotification == null)
            return;
        Log.i(TAG, "Starting picker mode");
        clearAllOverlays();
        pickerOverlay.show();
        pickerNotification.showPickerActiveNotification();
    }

    /**
     * Stop picker mode: hide the picker overlay and restore normal notification.
     */
    public void stopPickerMode() {
        if (pickerOverlay == null || pickerNotification == null)
            return;
        Log.i(TAG, "Stopping picker mode");
        pickerOverlay.hide();
        pickerNotification.showNotification();
        // Re-apply rules
        updateRules();
    }

    private static class BlockedElement {
        final View overlay;
        final Rect bounds;

        BlockedElement(View overlay, Rect bounds) {
            this.overlay = overlay;
            this.bounds = bounds;
        }
    }
}
