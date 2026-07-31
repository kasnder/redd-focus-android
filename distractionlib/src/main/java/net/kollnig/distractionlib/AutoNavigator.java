package net.kollnig.distractionlib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides when an app that has just been opened should be sent straight to a chosen screen --
 * Instagram to its inbox, WhatsApp to a chat list -- so the feed is never the first thing on
 * screen.
 *
 * <p>Overlays hide what an app shows; this instead moves the app somewhere else, which is the
 * only option when the distraction <em>is</em> the landing screen. A navigation rule reuses
 * {@link FilterRule} purely as a node matcher: the matched element is clicked rather than
 * covered, so nothing here ever creates an overlay.
 *
 * <p>The one behaviour that matters is firing <em>once per visit</em>. Navigating on every
 * pass would make the app impossible to use -- a user who deliberately taps back to the feed
 * would be thrown out of it again immediately. So a visit is armed when the foreground app
 * changes, and disarmed the moment navigation succeeds or the attempt window closes.
 *
 * <p>Deliberately free of Android types: the entry logic carries the awkward cases and is
 * worth testing directly. The service owns the node lookup and the click itself.
 */
public class AutoNavigator {

    /**
     * How long to wait for the app's own window to actually reach the foreground.
     *
     * <p>Launching an app and being able to read its screen are far apart, and unpredictably
     * so: measured cold starts have taken anywhere from 65ms to several seconds. Nothing can be
     * found during that time because the active window still belongs to whatever came before,
     * so waiting is not a sign that anything is wrong. Generous on purpose -- while this runs,
     * the app is not yet on screen, so there is no user action to interrupt.
     */
    public static final long APPEARANCE_WINDOW_MS = 20000;

    /**
     * How long to keep looking for the target once the app <em>is</em> on screen.
     *
     * <p>This is the window that has to stay short, because it is the only one during which a
     * late jump could yank the user off a screen they chose. Timed from the app appearing
     * rather than from the launch, so a slow start cannot eat it.
     */
    public static final long SEARCH_WINDOW_MS = 4000;

    private final List<FilterRule> rules = new ArrayList<>();

    private String foregroundPackage;
    private String armedPackage;
    private long armedAtMs;
    /** When the armed app's window first became readable, or 0 while still waiting. */
    private long appearedAtMs;

    /**
     * Replaces the rule set, keeping only enabled rules. Any pending navigation whose rule has
     * just been switched off is dropped, so turning a rule off takes effect immediately rather
     * than after one last jump.
     */
    public void setRules(List<FilterRule> newRules) {
        rules.clear();
        if (newRules != null) {
            for (FilterRule rule : newRules) {
                if (rule != null && rule.enabled && rule.packageName != null) {
                    rules.add(rule);
                }
            }
        }
        if (armedPackage != null && ruleFor(armedPackage) == null) {
            armedPackage = null;
        }
    }

    public List<FilterRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /**
     * Packages holding more than one enabled rule, which callers are expected to prevent.
     *
     * <p>{@link #ruleFor} resolves a package to a single rule, so any extras are dead weight
     * that nonetheless appear switched on wherever rules are listed. Exposed so the service can
     * say so out loud rather than leaving a rule that will never fire looking identical to one
     * that will.
     */
    public List<String> packagesWithSurplusRules() {
        List<String> surplus = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (FilterRule rule : rules) {
            if (seen.contains(rule.packageName)) {
                if (!surplus.contains(rule.packageName)) {
                    surplus.add(rule.packageName);
                }
            } else {
                seen.add(rule.packageName);
            }
        }
        return surplus;
    }

    /** The navigation rule for a package, or null if that package has none. */
    public FilterRule ruleFor(CharSequence packageName) {
        if (packageName == null) {
            return null;
        }
        for (FilterRule rule : rules) {
            if (rule.matchesPackage(packageName)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Records the app now in the foreground and arms navigation if entering it.
     *
     * <p>Callers must filter out their own package and the system UI first: an overlay window
     * or the notification shade appearing over an app does not end the visit, and treating it
     * as if it did would send the user back to the target screen every time they glanced at a
     * notification.
     *
     * @return true if this transition armed a navigation
     */
    public boolean onForegroundPackage(String packageName, long nowMs) {
        if (packageName == null || packageName.isEmpty()
                || packageName.equals(foregroundPackage)) {
            return false;
        }
        foregroundPackage = packageName;
        armedPackage = ruleFor(packageName) != null ? packageName : null;
        armedAtMs = nowMs;
        appearedAtMs = 0;
        return armedPackage != null;
    }

    /**
     * Records that the armed app's window is now readable, starting the search window. Only
     * the first such call counts, so an app that redraws repeatedly cannot keep extending it.
     */
    public void noteAppeared(long nowMs) {
        if (armedPackage != null && appearedAtMs == 0) {
            appearedAtMs = nowMs;
        }
    }

    /** Whether the armed app has been seen on screen yet, as opposed to still starting up. */
    public boolean hasAppeared() {
        return appearedAtMs != 0;
    }

    /**
     * The rule to act on for the currently active window, or null when there is nothing to do
     * -- either because no visit is armed, the active window belongs to a different app, or
     * the attempt has timed out. Expiry is resolved here so a stale arming cannot survive to
     * be acted on later.
     */
    public FilterRule armedRuleFor(CharSequence activePackage, long nowMs) {
        if (!isArmed(nowMs)) {
            return null;
        }
        if (activePackage == null || !armedPackage.contentEquals(activePackage)) {
            return null;
        }
        return ruleFor(armedPackage);
    }

    /** Whether an attempt is still outstanding, i.e. worth scheduling another retry for. */
    public boolean isArmed(long nowMs) {
        if (armedPackage != null && hasTimedOut(nowMs)) {
            if (appearedAtMs == 0) {
                // The app announced itself but never actually came to the front -- a window
                // event from a card in the app switcher, say. Treating that as a visit would
                // be worse than useless: the next genuine open would find the app already
                // recorded as current, decide nothing had changed, and do nothing. A visit
                // that never materialised is forgotten instead.
                foregroundPackage = null;
            }
            armedPackage = null;
        }
        return armedPackage != null;
    }

    /**
     * Two deadlines rather than one, because the two waits mean different things. Before the
     * app is on screen nothing could have been found anyway and the user is looking at a
     * launch animation; after it, every extra second is one in which the user may have started
     * doing something the jump would interrupt.
     */
    private boolean hasTimedOut(long nowMs) {
        return appearedAtMs == 0
                ? nowMs - armedAtMs > APPEARANCE_WINDOW_MS
                : nowMs - appearedAtMs > SEARCH_WINDOW_MS;
    }

    /** Ends the current visit's navigation, after a successful click or a deliberate stop. */
    public void disarm() {
        armedPackage = null;
        appearedAtMs = 0;
    }

    /**
     * Adopts the app already in the foreground as the current visit, without arming it.
     *
     * <p>The service is reconnected whenever it is re-enabled, updated, or displaced by another
     * accessibility client, and each reconnection starts from a blank slate. Without this, the
     * first event after a restart looks like the user opening the app and would jump them to
     * the target screen mid-session -- the service coming back is not a visit beginning.
     */
    public void adoptForegroundPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        foregroundPackage = packageName;
        armedPackage = null;
    }

    /**
     * Forgets the foreground app entirely. Used when the screen goes off: the next unlock is a
     * fresh visit and should navigate again, even if it lands back in the same app.
     */
    public void reset() {
        foregroundPackage = null;
        armedPackage = null;
        appearedAtMs = 0;
    }
}
