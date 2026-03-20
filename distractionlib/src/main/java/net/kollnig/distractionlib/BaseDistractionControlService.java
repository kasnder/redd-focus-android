package net.kollnig.distractionlib;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared accessibility service logic for distraction blocking apps.
 */
@SuppressLint("AccessibilityPolicy")
public abstract class BaseDistractionControlService extends AccessibilityService {
    private static final int MAX_OVERLAY_COUNT = 100;

    private final List<FilterRule> rules = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OverlayManager overlayManager = new OverlayManager();
    private final Map<String, BlockedElement> blockedElements = new HashMap<>();
    private final Set<String> activeRuleKeys = new HashSet<>();

    private WindowManager windowManager;
    private boolean isDarkMode;
    private boolean sentinelPackagesActive;
    private boolean screenOn = true;
    private String cachedLauncherPackage;
    private BroadcastReceiver screenReceiver;

    private final Runnable processEvent = () -> {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.w(getLogTag(), "No root window available");
                return;
            }
            try {
                CharSequence rootPkg = root.getPackageName();
                if (rootPkg == null || !hasMatchingRule(rootPkg) || !shouldProcessRules()) {
                    forceClearAllOverlays();
                    return;
                }

                activeRuleKeys.clear();
                processRootNode(root);

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
                removeSentinelsIfIdle();
            } finally {
                root.recycle();
            }
        } catch (Exception e) {
            Log.e(getLogTag(), "Error processing accessibility event", e);
        }
    };

    protected abstract List<FilterRule> loadRules();

    protected abstract boolean shouldProcessRules();

    protected abstract void onServiceReady();

    protected abstract void onServiceTeardown();

    /**
     * Returns the notification timeout in milliseconds for the accessibility service.
     * Higher values reduce event frequency and save battery. Default is 100ms.
     */
    protected long getNotificationTimeout() {
        return 100;
    }

    protected void onRulesReloaded() {
    }

    protected final Handler getUiHandler() {
        return ui;
    }

    protected final WindowManager getOverlayWindowManager() {
        return windowManager;
    }

    protected final List<FilterRule> getRulesSnapshot() {
        return new ArrayList<>(rules);
    }

    protected final void reloadRulesFromSource() {
        ui.removeCallbacks(processEvent);
        rules.clear();
        List<FilterRule> loadedRules = loadRules();
        if (loadedRules != null) {
            rules.addAll(loadedRules);
        }
        clearAllOverlays();
        configureAccessibilityService(false);
        onRulesReloaded();
        Log.i(getLogTag(), "Accessibility service initialized with " + rules.size() + " rule(s)");
    }

    protected final void reevaluateBlockingState() {
        ui.removeCallbacks(processEvent);
        if (!shouldProcessRules()) {
            forceClearAllOverlays();
            return;
        }
        ui.post(processEvent);
    }

    protected final void clearCurrentOverlays() {
        clearAllOverlays();
    }

    protected final void forceClearCurrentOverlays() {
        forceClearAllOverlays();
    }

    private String getLogTag() {
        return getClass().getSimpleName();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(getLogTag(), "Failed to get WindowManager service");
                return;
            }
            updateDarkMode();
            registerScreenReceiver();
            onServiceReady();
            reloadRulesFromSource();
        } catch (Exception e) {
            Log.e(getLogTag(), "Error initializing service", e);
        }
    }

    private void registerScreenReceiver() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            screenOn = pm.isInteractive();
            Log.d(getLogTag(), "Initial screen state: " + (screenOn ? "on" : "off"));
        }

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    screenOn = false;
                    Log.d(getLogTag(), "Screen off - pausing accessibility processing");
                    ui.removeCallbacks(processEvent);
                    forceClearAllOverlays();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn = true;
                    Log.d(getLogTag(), "Screen on - resuming accessibility processing");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
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
        if (event == null || !screenOn) {
            return;
        }

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        if (packageName.equals(getPackageName())
                || packageName.equals("com.android.systemui")
                || isLauncherPackage(packageName)) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                ui.removeCallbacks(processEvent);
                forceClearAllOverlays();
            }
            return;
        }

        if (!hasMatchingRule(packageName)) {
            ui.removeCallbacks(processEvent);
            forceClearAllOverlays();
            return;
        }

        if (!shouldProcessRules()) {
            forceClearAllOverlays();
            return;
        }

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }

        ui.removeCallbacks(processEvent);
        ui.post(processEvent);
    }

    private boolean hasMatchingRule(CharSequence packageName) {
        for (FilterRule rule : rules) {
            if (rule.enabled && rule.matchesPackage(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void configureAccessibilityService(boolean includeSentinels) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                Log.e(getLogTag(), "Failed to get service info");
                return;
            }
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = getNotificationTimeout();

            Set<String> packages = new HashSet<>();
            for (FilterRule rule : rules) {
                if (rule.enabled) {
                    packages.add(rule.packageName);
                }
            }

            if (!packages.isEmpty() && includeSentinels) {
                packages.add("com.android.systemui");
                String launcher = getLauncherPackage();
                if (launcher != null) {
                    packages.add(launcher);
                }
            }

            sentinelPackagesActive = includeSentinels;
            info.packageNames = packages.isEmpty() ? null : packages.toArray(new String[0]);
            setServiceInfo(info);
            Log.i(getLogTag(), "Package filter updated: " + packages);
        } catch (Exception e) {
            Log.e(getLogTag(), "Error configuring accessibility service", e);
        }
    }

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
                Log.e(getLogTag(), "Error resolving launcher package", e);
            }
        }
        return cachedLauncherPackage;
    }

    private boolean isLauncherPackage(String packageName) {
        String launcher = getLauncherPackage();
        return launcher != null && launcher.equals(packageName);
    }

    private void processRootNode(AccessibilityNodeInfo root) {
        CharSequence packageName = root.getPackageName();
        if (packageName == null) {
            return;
        }

        for (FilterRule rule : rules) {
            if (rule.enabled && rule.matchesPackage(packageName)) {
                applyRule(rule, root);
            }
        }
    }

    private void applyRule(FilterRule rule, AccessibilityNodeInfo root) {
        if (root == null || !root.isVisibleToUser()) return;

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

        if (rule.targetViewId != null && !rule.targetViewId.isEmpty()) {
            List<AccessibilityNodeInfo> matches =
                    root.findAccessibilityNodeInfosByViewId(rule.targetViewId);
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

        applyRuleRecursive(rule, root);
    }

    private void applyRuleRecursive(FilterRule rule, AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return;

        if (isTargetView(node, rule)) {
            processTargetView(node, rule);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                applyRuleRecursive(rule, child);
            } finally {
                child.recycle();
            }
        }
    }

    private boolean isTargetView(AccessibilityNodeInfo node, FilterRule rule) {
        if (rule.targetViewId != null && !rule.targetViewId.isEmpty()) {
            String viewId = node.getViewIdResourceName();
            return viewId != null && viewId.equals(rule.targetViewId);
        }

        if (rule.targetPath != null && !rule.targetPath.isEmpty()) {
            return false;
        }

        if (rule.contentDescriptions != null && !rule.contentDescriptions.isEmpty()) {
            CharSequence desc = node.getContentDescription();
            if (desc != null && rule.contentDescriptions.contains(desc.toString())) {
                return true;
            }
        }

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

        if (rule.targetText != null && !rule.targetText.isEmpty()) {
            CharSequence text = node.getText();
            return text != null && text.toString().equals(rule.targetText);
        }

        return false;
    }

    private List<AccessibilityNodeInfo> matchPaths(AccessibilityNodeInfo root, String path) {
        List<AccessibilityNodeInfo> empty = new ArrayList<>();
        if (root == null || path == null || path.isEmpty()) return empty;

        String[] segments = path.split(">");
        List<AccessibilityNodeInfo> currentNodes = new ArrayList<>();
        currentNodes.add(root);

        for (String segment : segments) {
            String className;
            boolean isWildcard = false;
            int index = 0;

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
                        Log.w(getLogTag(), "Invalid path index: " + indexStr);
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
        if (rule.targetViewId == null || rule.contentDescriptions == null
                || rule.contentDescriptions.isEmpty() || rule.targetViewId.isEmpty()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                addOverlay(bounds, rule, matchIndex);
            }
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
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
        if (node == null) return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null && targets.contains(desc.toString())) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (subtreeContainsContentDescription(child, targets)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

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

        BlockedElement existing = blockedElements.get(ruleKey);
        if (existing != null) {
            if (existing.bounds.equals(area)) {
                return;
            }

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (!rule.blockTouches) {
                flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    area.width(), area.height(), area.left, area.top,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            overlayManager.updateOverlay(existing.overlay, lp, windowManager, ui);
            existing.bounds.set(area);
            return;
        }

        if (overlayManager.getOverlayCount() >= MAX_OVERLAY_COUNT) {
            clearAllOverlays();
        }

        View blocker = new View(this);
        int color = rule.color;
        if (color == Color.WHITE && isDarkMode && !rule.ruleString.contains("color=")) {
            color = Color.BLACK;
        }
        blocker.setBackgroundColor(color);
        blocker.setAlpha(1f);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!rule.blockTouches) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                area.width(), area.height(), area.left, area.top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        overlayManager.addOverlay(blocker, lp, windowManager, ui);
        blockedElements.put(ruleKey, new BlockedElement(blocker, new Rect(area)));

        if (!sentinelPackagesActive) {
            configureAccessibilityService(true);
        }
    }

    private void clearAllOverlays() {
        overlayManager.clearOverlays(windowManager, ui);
        blockedElements.clear();
        removeSentinelsIfIdle();
    }

    private void forceClearAllOverlays() {
        overlayManager.forceClearOverlays(windowManager);
        blockedElements.clear();
        removeSentinelsIfIdle();
    }

    private void removeSentinelsIfIdle() {
        if (sentinelPackagesActive && blockedElements.isEmpty()) {
            configureAccessibilityService(false);
        }
    }

    @Override
    public void onInterrupt() {
        forceClearAllOverlays();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            onServiceTeardown();
            if (screenReceiver != null) {
                try {
                    unregisterReceiver(screenReceiver);
                } catch (Exception ignored) {
                }
            }
        } finally {
            forceClearAllOverlays();
        }
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
