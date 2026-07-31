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
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
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
    // Widest an image may be relative to its height and still count as a thumbnail.
    private static final int MAX_THUMBNAIL_ASPECT_RATIO = 6;
    // Absorbs transient foreign-window events (notification shade, status bar
    // updates) that briefly take focus while the target app remains below.
    private static final long CLEAR_OVERLAYS_DELAY_MS = 150;
    // Floor between full traversals. Overlays track element bounds, so raising
    // this trades tracking smoothness during a scroll against CPU: too high and
    // blocked content visibly leaks through before the overlay catches up.
    private static final long MIN_PROCESS_INTERVAL_MS = 100;
    // Releasing the all-packages filter is deferred so that blocked elements
    // scrolling in and out of the viewport do not thrash setServiceInfo(),
    // which makes the system recompute event routing for every installed app.
    private static final long SENTINEL_RELEASE_DELAY_MS = 2000;
    // Gap between attempts to reach a navigation rule's target screen. An app that has just
    // been launched needs several frames before its tab bar exists, and events during startup
    // are bursty, so retries are driven on a timer rather than left to whatever arrives.
    private static final long NAVIGATION_RETRY_INTERVAL_MS = 250;
    // How far up from a matched element to look for something that actually handles a click.
    // Apps label the icon but attach the listener to a wrapper a level or two above; beyond
    // that the enclosing container is no longer the thing the user would have tapped.
    private static final int MAX_CLICK_ANCESTRY = 4;
    // The system UI owns the notification shade and the recents switcher. Both appear over an
    // app without ending the visit to it, so they never count as a foreground change.
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    // The event types the service needs while it has something to block. When
    // there is nothing to block the mask is set to zero instead, so the system
    // stops dispatching to this process altogether.
    private static final int ACTIVE_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;

    private final List<FilterRule> rules = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OverlayManager overlayManager = new OverlayManager();
    private final Map<String, BlockedElement> blockedElements = new HashMap<>();
    private final Set<String> activeRuleKeys = new HashSet<>();
    private final AutoNavigator autoNavigator = new AutoNavigator();
    private final Runnable pendingClear = this::clearOverlaysIfActiveWindowIsNonTarget;
    private final Runnable navigationAttempt = this::attemptNavigation;

    private WindowManager windowManager;
    private boolean isDarkMode;
    private boolean sentinelPackagesActive;
    private boolean screenOn = true;
    private boolean processEventScheduled;
    private long lastProcessUptimeMs;
    private String cachedLauncherPackage;
    private String pauseNotificationPackage;
    private BroadcastReceiver screenReceiver;

    private final Runnable releaseSentinels = () -> {
        if (sentinelPackagesActive && blockedElements.isEmpty()) {
            configureAccessibilityService(false);
        }
    };

    private final Choreographer.FrameCallback processEventFrame = frameTimeNanos -> {
        processEventScheduled = false;
        try {
            runProcessEvent();
        } finally {
            // Measured from the end of the pass, so a slow traversal cannot be
            // followed immediately by another one.
            lastProcessUptimeMs = SystemClock.uptimeMillis();
        }
    };

    // Runs once the inter-pass floor has elapsed, then aligns the actual pass
    // with the next frame so bounds are read after the app has settled.
    private final Runnable deferredProcessEvent =
            () -> Choreographer.getInstance().postFrameCallback(processEventFrame);

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
                    leaveTargetApp();
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
                        overlayManager.removeOverlay(element.overlay, windowManager);
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

    private void runProcessEvent() {
        processEvent.run();
    }

    protected abstract List<FilterRule> loadRules();

    /**
     * Rules describing a screen to open as soon as an app is entered, matched exactly like a
     * blocking rule but clicked rather than covered. Optional: an app that only hides content
     * can leave this at the default.
     *
     * @see AutoNavigator
     */
    protected List<FilterRule> loadNavigationRules() {
        return new ArrayList<>();
    }

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

    protected void onPauseNotificationShouldShow(String packageName) {
    }

    protected void onPauseNotificationShouldCancel() {
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
        cancelProcessEvent();
        ui.removeCallbacks(pendingClear);
        ui.removeCallbacks(navigationAttempt);
        rules.clear();
        List<FilterRule> loadedRules = loadRules();
        if (loadedRules != null) {
            rules.addAll(loadedRules);
        }
        autoNavigator.setRules(loadNavigationRules());
        clearAllOverlays();
        updatePauseNotificationForCurrentPackage();
        configureAccessibilityService(false);
        onRulesReloaded();
        Log.i(getLogTag(), "Accessibility service initialized with " + rules.size() + " rule(s)");
    }

    protected final void reevaluateBlockingState() {
        cancelProcessEvent();
        ui.removeCallbacks(pendingClear);
        if (!shouldProcessRules()) {
            forceClearAllOverlays();
            return;
        }
        scheduleProcessEvent();
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
            adoptCurrentForegroundPackage();
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
                    cancelProcessEvent();
                    ui.removeCallbacks(pendingClear);
                    ui.removeCallbacks(navigationAttempt);
                    // The visit ends with the screen. Unlocking back into the same app is a
                    // new visit and should navigate again, so the tracked package is dropped
                    // rather than merely disarmed.
                    autoNavigator.reset();
                    cancelPauseNotification();
                    forceClearAllOverlays();
                    // Stop delivery at the source rather than receiving events
                    // and discarding them in onAccessibilityEvent().
                    configureAccessibilityService(false);
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn = true;
                    Log.d(getLogTag(), "Screen on - resuming accessibility processing");
                    configureAccessibilityService(false);
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

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // Tracked before the non-target check below, because the packages that end a visit --
        // the launcher, another app -- are exactly the ones that check discards.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundPackageChanged(packageName);
        }

        boolean isNonTarget = packageName.equals(getPackageName())
                || packageName.equals("com.android.systemui")
                || isLauncherPackage(packageName)
                || !hasMatchingRule(packageName);

        if (isNonTarget) {
            cancelProcessEvent();
            ui.removeCallbacks(pendingClear);
            // Nothing is attached, so there is nothing to tear down. Skip the
            // delayed check entirely rather than paying for a root-window
            // lookup that would find no work to do.
            if (!hasStateToClear()) {
                return;
            }
            // SystemUI often reports notification-shade movement as content or
            // scroll changes rather than a clean window-state change. Delay,
            // then inspect the active root window before clearing so transient
            // notifications do not flicker target-app overlays.
            ui.postDelayed(pendingClear, CLEAR_OVERLAYS_DELAY_MS);
            return;
        }

        // Target package event — cancel any pending clear from a transient
        // foreign window, then re-evaluate overlays.
        ui.removeCallbacks(pendingClear);

        if (!shouldProcessRules()) {
            cancelPauseNotification();
            forceClearAllOverlays();
            return;
        }

        showPauseNotification(packageName);
        scheduleProcessEvent();
    }

    /**
     * Treats whatever is already on screen as a visit in progress, so a reconnection does not
     * read as the user opening that app. Best effort: the active window is often not available
     * this early, and the cost of missing it is one unwanted navigation rather than a fault.
     */
    private void adoptCurrentForegroundPackage() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                boolean observedAllBefore = observesAllPackages();
                autoNavigator.adoptForegroundPackage(root.getPackageName().toString());
                if (observedAllBefore != observesAllPackages()) {
                    configureAccessibilityService(sentinelPackagesActive);
                }
            }
        } catch (Exception e) {
            Log.e(getLogTag(), "Error reading the foreground app at startup", e);
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    /**
     * Notes the app now in front and starts trying to reach its target screen if it has a
     * navigation rule. Our own overlay windows and the system UI are ignored: neither ends the
     * user's visit to the app underneath, and treating them as if they did would re-navigate
     * every time a notification arrived.
     */
    private void onForegroundPackageChanged(String packageName) {
        if (packageName.isEmpty()
                || packageName.equals(getPackageName())
                || packageName.equals(SYSTEM_UI_PACKAGE)) {
            return;
        }
        boolean observedAllBefore = observesAllPackages();
        boolean armed = autoNavigator.onForegroundPackage(packageName, SystemClock.uptimeMillis());
        if (observedAllBefore != observesAllPackages()) {
            configureAccessibilityService(sentinelPackagesActive);
        }
        Log.d(getLogTag(), "Foreground package: " + packageName + (armed ? " (armed)" : ""));
        if (armed) {
            ui.removeCallbacks(navigationAttempt);
            ui.post(navigationAttempt);
        }
    }

    /**
     * Tries once to reach the armed rule's target screen, re-posting itself until it succeeds
     * or the attempt window closes. The target usually does not exist on the first pass --
     * the app is still laying out -- so failure here is expected rather than exceptional.
     */
    private void attemptNavigation() {
        if (!screenOn || !autoNavigator.isArmed(SystemClock.uptimeMillis())) {
            return;
        }
        // Blocking is suspended while the element picker is up, and a jump to another screen
        // mid-pick would be just as unwelcome as an overlay.
        if (shouldProcessRules() && performArmedNavigation()) {
            autoNavigator.disarm();
            return;
        }
        ui.postDelayed(navigationAttempt, NAVIGATION_RETRY_INTERVAL_MS);
    }

    /**
     * Resolves the armed rule against the active window and clicks its target.
     *
     * @return true once the click has been delivered, so no further attempt is needed
     */
    private boolean performArmedNavigation() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }
            FilterRule rule = autoNavigator.armedRuleFor(
                    root.getPackageName(), SystemClock.uptimeMillis());
            if (rule == null) {
                return false;
            }
            AccessibilityNodeInfo target = findNavigationTarget(root, rule);
            if (target == null) {
                return false;
            }
            try {
                boolean clicked = clickNodeOrAncestor(target);
                if (clicked) {
                    Log.i(getLogTag(), "Auto-navigated " + rule.packageName + ": "
                            + rule.description);
                }
                return clicked;
            } finally {
                if (target != root) {
                    target.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(getLogTag(), "Error auto-navigating", e);
            return false;
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    /**
     * Finds the element a navigation rule points at. View ID first, since it survives
     * translation and layout changes; a content description is the fallback for elements that
     * carry no ID, at the cost of being language-specific.
     *
     * @return a node the caller owns and must recycle, or null
     */
    private AccessibilityNodeInfo findNavigationTarget(AccessibilityNodeInfo root,
                                                       FilterRule rule) {
        if (rule.targetViewId != null && !rule.targetViewId.isEmpty()) {
            if (rule.targetChildPath != null && !rule.targetChildPath.isEmpty()) {
                return findAnchoredNavigationTarget(root, rule);
            }
            List<AccessibilityNodeInfo> matches =
                    root.findAccessibilityNodeInfosByViewId(rule.targetViewId);
            if (matches == null) {
                return null;
            }
            AccessibilityNodeInfo found = null;
            for (AccessibilityNodeInfo match : matches) {
                if (found == null && match.isVisibleToUser()) {
                    found = match;
                } else {
                    match.recycle();
                }
            }
            return found;
        }
        if (rule.contentDescriptions != null && !rule.contentDescriptions.isEmpty()) {
            return findByContentDescription(root, rule.contentDescriptions);
        }
        return null;
    }

    /**
     * Resolves a rule that names a path below an anchoring view ID. Without this the view-ID
     * branch would find the anchor and click <em>that</em> -- the tab bar rather than the tab.
     *
     * @return a node the caller owns and must recycle, or null
     */
    private AccessibilityNodeInfo findAnchoredNavigationTarget(AccessibilityNodeInfo root,
                                                               FilterRule rule) {
        List<AccessibilityNodeInfo> anchors =
                root.findAccessibilityNodeInfosByViewId(rule.targetViewId);
        if (anchors == null) {
            return null;
        }

        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo anchor : anchors) {
            try {
                if (found != null || !anchor.isVisibleToUser()) {
                    continue;
                }
                for (AccessibilityNodeInfo target : matchPaths(anchor, rule.targetChildPath)) {
                    if (found == null && target.isVisibleToUser()) {
                        found = target;
                    } else if (target != anchor) {
                        target.recycle();
                    }
                }
            } finally {
                if (anchor != found) {
                    anchor.recycle();
                }
            }
        }
        return found;
    }

    /** Depth-first search for a visible node carrying one of the given descriptions. */
    private AccessibilityNodeInfo findByContentDescription(AccessibilityNodeInfo node,
                                                          Set<String> targets) {
        CharSequence desc = node.getContentDescription();
        if (desc != null && node.isVisibleToUser() && targets.contains(desc.toString())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            AccessibilityNodeInfo found = findByContentDescription(child, targets);
            if (found != null) {
                // The match is on the returned path, so only unrelated children are recycled.
                if (found != child) {
                    child.recycle();
                }
                return found;
            }
            child.recycle();
        }
        return null;
    }

    /**
     * Clicks the node, or the nearest ancestor that handles clicks. Apps commonly describe the
     * icon for accessibility but attach the listener to a wrapper above it, so the described
     * node itself is often not the clickable one.
     */
    private boolean clickNodeOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        boolean ownsCurrent = false;
        try {
            for (int depth = 0; current != null && depth < MAX_CLICK_ANCESTRY; depth++) {
                if (current.isEnabled() && current.isClickable() && current.isVisibleToUser()) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                AccessibilityNodeInfo parent = current.getParent();
                if (ownsCurrent) {
                    current.recycle();
                }
                current = parent;
                ownsCurrent = true;
            }
            return false;
        } finally {
            if (ownsCurrent && current != null) {
                current.recycle();
            }
        }
    }

    private void scheduleProcessEvent() {
        if (processEventScheduled) {
            return;
        }
        processEventScheduled = true;
        // Coalescing onto a frame callback bounds passes to the refresh rate,
        // which is not a limit worth having for a full tree traversal. Hold a
        // floor between passes as well; the trailing edge still runs, so the
        // final state of a burst is always processed.
        long delay = lastProcessUptimeMs + MIN_PROCESS_INTERVAL_MS - SystemClock.uptimeMillis();
        if (delay <= 0) {
            Choreographer.getInstance().postFrameCallback(processEventFrame);
        } else {
            ui.postDelayed(deferredProcessEvent, delay);
        }
    }

    private void cancelProcessEvent() {
        if (processEventScheduled) {
            ui.removeCallbacks(deferredProcessEvent);
            Choreographer.getInstance().removeFrameCallback(processEventFrame);
            processEventScheduled = false;
        }
    }

    private void clearOverlaysIfActiveWindowIsNonTarget() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) {
                leaveTargetApp();
                return;
            }

            CharSequence rootPkg = root.getPackageName();
            if (rootPkg == null || !hasMatchingRule(rootPkg) || !shouldProcessRules()) {
                leaveTargetApp();
            } else {
                scheduleProcessEvent();
            }
        } catch (Exception e) {
            Log.e(getLogTag(), "Error checking active window before clearing overlays", e);
            leaveTargetApp();
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    /**
     * Tears down everything tied to being inside a target app. The pause
     * notification offers to pause blocking for a specific package, so it must
     * go away with the overlays — otherwise it lingers after the user has left
     * that app, and the same-package guard in showPauseNotification() prevents
     * it from ever being refreshed on return.
     */
    private void leaveTargetApp() {
        cancelPauseNotification();
        forceClearAllOverlays();
    }

    /**
     * Whether the service currently holds state that a foreground change would
     * need to tear down.
     */
    private boolean hasStateToClear() {
        return !blockedElements.isEmpty()
                || sentinelPackagesActive
                || pauseNotificationPackage != null;
    }

    private boolean hasMatchingRule(CharSequence packageName) {
        for (FilterRule rule : rules) {
            if (rule.enabled && rule.matchesPackage(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void updatePauseNotificationForCurrentPackage() {
        if (pauseNotificationPackage == null) {
            return;
        }
        if (hasMatchingRule(pauseNotificationPackage)) {
            onPauseNotificationShouldShow(pauseNotificationPackage);
        } else {
            cancelPauseNotification();
        }
    }

    private void showPauseNotification(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.equals(pauseNotificationPackage)) {
            return;
        }
        pauseNotificationPackage = packageName;
        onPauseNotificationShouldShow(packageName);
    }

    private void cancelPauseNotification() {
        if (pauseNotificationPackage == null) {
            return;
        }
        pauseNotificationPackage = null;
        onPauseNotificationShouldCancel();
    }

    private void configureAccessibilityService(boolean includeSentinels) {
        // This call settles the filter state, so any deferred release is moot.
        ui.removeCallbacks(releaseSentinels);
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                Log.e(getLogTag(), "Failed to get service info");
                return;
            }
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = getNotificationTimeout();

            Set<String> packages = new HashSet<>();
            boolean needsAllViews = false;
            for (FilterRule rule : rules) {
                if (rule.enabled) {
                    packages.add(rule.packageName);
                    needsAllViews |= rule.minThumbnailWidthDp > 0;
                }
            }

            // A navigation rule fires on entering its app, so the service has to hear about
            // that app even when nothing in it is being blocked. The launcher joins the list
            // too: leaving an app is what ends a visit, and going home is the usual way out,
            // so without it a second visit would look like a continuation of the first and
            // would not navigate.
            if (autoNavigator.hasRules()) {
                for (FilterRule rule : autoNavigator.getRules()) {
                    packages.add(rule.packageName);
                }
                String launcher = getLauncherPackage();
                if (launcher != null) {
                    packages.add(launcher);
                }
            }

            // Apps mark decorative views, thumbnails among them, as not important for
            // accessibility, so they stay out of the node tree. Shape matching needs to see
            // them, but the larger tree costs battery, so ask for it only while a rule that
            // matches by shape is enabled.
            if (needsAllViews) {
                info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            } else {
                info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            }

            sentinelPackagesActive = includeSentinels;
            // A null packageNames means "every package on the device", so an
            // empty target set must never reach it. With no rules enabled — the
            // default state, since rules are opt-in — there is nothing to block,
            // so silence the service instead of subscribing to the whole system.
            // The same applies while the screen is off. Queried directly rather
            // than trusting the cached screenOn field: a missed or reordered
            // ACTION_SCREEN_ON broadcast would otherwise leave eventTypes stuck
            // at 0 with no accessibility events left to reveal the problem.
            boolean suppressEvents = packages.isEmpty() || !isScreenInteractive();
            info.eventTypes = suppressEvents ? 0 : ACTIVE_EVENT_TYPES;
            // While overlays are attached, or while the user is inside a navigation target,
            // temporarily observe all packages. The former lets any foreground transition clear
            // overlays; the latter records departures to unrelated apps so returning to the
            // target counts as a new visit. The stricter target-app filter is restored as soon
            // as neither reason remains.
            info.packageNames = suppressEvents || observesAllPackages()
                    ? null
                    : packages.toArray(new String[0]);
            setServiceInfo(info);
            Log.i(getLogTag(), "Event filter updated: " + (suppressEvents
                    ? "suppressed (" + (packages.isEmpty() ? "no enabled rules" : "screen off") + ")"
                    : (info.packageNames == null ? "all packages" : packages.toString())));
        } catch (Exception e) {
            Log.e(getLogTag(), "Error configuring accessibility service", e);
        }
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private boolean observesAllPackages() {
        return sentinelPackagesActive || autoNavigator.isForegroundNavigationTarget();
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
        if (packageName == null || !root.isVisibleToUser()) {
            return;
        }

        // Rules addressed by path or view id resolve against an index or a
        // single branch, so they are handled directly. Everything else has to
        // be matched by inspecting nodes; those rules are collected and
        // evaluated during one shared walk rather than one walk each.
        List<FilterRule> scanRules = null;
        for (FilterRule rule : rules) {
            if (!rule.enabled || !rule.matchesPackage(packageName) || !matchesScreen(rule, root)) {
                continue;
            }
            boolean hasViewId = rule.targetViewId != null && !rule.targetViewId.isEmpty();
            if (hasViewId && rule.targetChildPath != null && !rule.targetChildPath.isEmpty()) {
                applyAnchoredRule(rule, root);
            } else if (rule.targetPath != null && !rule.targetPath.isEmpty()) {
                applyPathRule(rule, root);
            } else if (hasViewId) {
                applyViewIdRule(rule, root);
            } else {
                if (scanRules == null) {
                    scanRules = new ArrayList<>();
                }
                scanRules.add(rule);
            }
        }

        if (scanRules != null) {
            scanTree(root, scanRules);
        }
    }

    private void applyPathRule(FilterRule rule, AccessibilityNodeInfo root) {
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
    }

    private void applyViewIdRule(FilterRule rule, AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> matches =
                root.findAccessibilityNodeInfosByViewId(rule.targetViewId);
        if (matches == null) {
            return;
        }
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

    /**
     * Matches a short path below the node carrying the rule's view ID. Anchoring keeps the
     * fragile part of a path down to a couple of levels, so layout changes above the anchor
     * leave the rule intact.
     */
    private void applyAnchoredRule(FilterRule rule, AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> anchors =
                root.findAccessibilityNodeInfosByViewId(rule.targetViewId);
        if (anchors == null) return;

        int matchIndex = 0;
        for (AccessibilityNodeInfo anchor : anchors) {
            try {
                if (!anchor.isVisibleToUser()) continue;

                List<AccessibilityNodeInfo> targets = matchPaths(anchor, rule.targetChildPath);
                for (AccessibilityNodeInfo target : targets) {
                    int index = matchIndex++;
                    try {
                        if (target.isVisibleToUser()) {
                            processTargetView(target, rule, index);
                        }
                    } finally {
                        if (target != anchor) {
                            target.recycle();
                        }
                    }
                }
            } finally {
                anchor.recycle();
            }
        }
    }

    /**
     * Checks the rule's screen markers against the current window. Several screens of an app
     * often share one list view ID, so a rule meant for a single screen needs a way to tell
     * them apart; markers do that structurally, without depending on any translated label.
     */
    private boolean matchesScreen(FilterRule rule, AccessibilityNodeInfo root) {
        FilterRule.ScreenCondition condition = rule.screenCondition;
        if (condition == null) return true;

        if (condition.requiredViewId != null && !isViewPresent(root, condition.requiredViewId)) {
            return false;
        }
        return condition.selectedViewId == null || isMarkerSelected(root, condition);
    }

    private boolean isViewPresent(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
        if (matches == null) return false;

        boolean present = false;
        for (AccessibilityNodeInfo match : matches) {
            try {
                present |= match.isVisibleToUser();
            } finally {
                match.recycle();
            }
        }
        return present;
    }

    /**
     * Reports whether the marker node is selected, which is how apps expose the active tab of
     * a navigation bar.
     */
    private boolean isMarkerSelected(AccessibilityNodeInfo root,
                                     FilterRule.ScreenCondition condition) {
        List<AccessibilityNodeInfo> anchors =
                root.findAccessibilityNodeInfosByViewId(condition.selectedViewId);
        if (anchors == null) return false;

        boolean selected = false;
        for (AccessibilityNodeInfo anchor : anchors) {
            try {
                if (!anchor.isVisibleToUser()) continue;

                if (condition.selectedChildPath == null) {
                    selected |= anchor.isSelected();
                    continue;
                }
                for (AccessibilityNodeInfo target
                        : matchPaths(anchor, condition.selectedChildPath)) {
                    try {
                        selected |= target.isSelected();
                    } finally {
                        if (target != anchor) {
                            target.recycle();
                        }
                    }
                }
            } finally {
                anchor.recycle();
            }
        }
        return selected;
    }

    /**
     * Walks the tree once, testing every scan rule against each node. Each
     * getChild() call can cross back into the inspected app's process, so the
     * traversal is the dominant cost of a pass and must not be repeated per
     * rule.
     */
    private void scanTree(AccessibilityNodeInfo node, List<FilterRule> scanRules) {
        if (node == null || !node.isVisibleToUser()) return;

        for (int r = 0; r < scanRules.size(); r++) {
            FilterRule rule = scanRules.get(r);
            if (isTargetView(node, rule)) {
                processTargetView(node, rule);
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                scanTree(child, scanRules);
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
        if (!hasThumbnail(node, rule)) {
            return;
        }

        if (rule.targetViewId == null || rule.contentDescriptions == null
                || rule.contentDescriptions.isEmpty() || rule.targetViewId.isEmpty()
                || (rule.targetChildPath != null && !rule.targetChildPath.isEmpty())) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                addOverlay(bounds, rule, matchIndex);
            }
            return;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
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

    /**
     * Recognises media cards by shape rather than by label. Feed and recommendation cards are
     * drawn without view IDs and their labels are translated, but a card is built around a
     * large image, while title, channel, action and comment rows only carry small icons and
     * avatars. On a phone the two differ by roughly ten times in dp, and that gap holds on
     * larger screens, where apps switch to compact cards that keep a similar thumbnail size
     * in a much wider row.
     */
    private boolean hasThumbnail(AccessibilityNodeInfo node, FilterRule rule) {
        if (rule.minThumbnailWidthDp <= 0) {
            return true;
        }

        float density = getResources().getDisplayMetrics().density;
        return subtreeContainsWideImage(node, Math.round(rule.minThumbnailWidthDp * density));
    }

    private boolean subtreeContainsWideImage(AccessibilityNodeInfo node, int minWidth) {
        if (node == null) return false;

        CharSequence className = node.getClassName();
        if (className != null && isImageClass(className.toString())) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            // Height keeps dividers and progress bars, which are also full width, out. The
            // limit is generous because bounds of a partly scrolled card are clipped.
            if (bounds.width() >= minWidth
                    && bounds.height() * MAX_THUMBNAIL_ASPECT_RATIO >= bounds.width()) {
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (subtreeContainsWideImage(child, minWidth)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean isImageClass(String className) {
        return className.endsWith("ImageView")
                || className.endsWith("SurfaceView")
                || className.endsWith("TextureView");
    }

    private boolean subtreeContainsContentDescription(AccessibilityNodeInfo node, Set<String> targets) {
        if (node == null) return false;

        CharSequence desc = node.getContentDescription();
        if (desc != null && targets.contains(desc.toString())) return true;

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
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
                    getOverlayPixelFormat(rule));
            lp.gravity = Gravity.TOP | Gravity.START;
            overlayManager.updateOverlay(existing.overlay, lp, windowManager);
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
                getOverlayPixelFormat(rule));
        lp.gravity = Gravity.TOP | Gravity.START;

        overlayManager.addOverlay(blocker, lp, windowManager);
        blockedElements.put(ruleKey, new BlockedElement(blocker, new Rect(area)));

        if (!sentinelPackagesActive) {
            configureAccessibilityService(true);
        }
    }

    private void clearAllOverlays() {
        overlayManager.clearOverlays(windowManager);
        blockedElements.clear();
        removeSentinelsIfIdle();
    }

    private int getOverlayPixelFormat(FilterRule rule) {
        int color = rule.color;
        if (color == Color.WHITE && isDarkMode && !rule.ruleString.contains("color=")) {
            color = Color.BLACK;
        }
        return Color.alpha(color) == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }

    private void forceClearAllOverlays() {
        clearAllOverlays();
    }

    private void removeSentinelsIfIdle() {
        if (!sentinelPackagesActive) {
            return;
        }
        ui.removeCallbacks(releaseSentinels);
        if (blockedElements.isEmpty()) {
            ui.postDelayed(releaseSentinels, SENTINEL_RELEASE_DELAY_MS);
        }
    }

    @Override
    public void onInterrupt() {
        ui.removeCallbacks(pendingClear);
        cancelPauseNotification();
        forceClearAllOverlays();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            cancelProcessEvent();
            ui.removeCallbacks(pendingClear);
            ui.removeCallbacks(navigationAttempt);
            cancelPauseNotification();
            onServiceTeardown();
            if (screenReceiver != null) {
                try {
                    unregisterReceiver(screenReceiver);
                } catch (Exception ignored) {
                }
            }
        } finally {
            forceClearAllOverlays();
            ui.removeCallbacks(releaseSentinels);
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
