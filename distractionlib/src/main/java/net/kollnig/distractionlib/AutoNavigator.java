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
     * How long after entering an app its target screen is still worth reaching for. An app
     * needs a moment to lay out its tab bar, so the first attempt usually finds nothing. Past
     * this window the user has had time to navigate somewhere on purpose, and a late jump
     * would read as the app fighting them.
     */
    public static final long ATTEMPT_WINDOW_MS = 6000;

    private final List<FilterRule> rules = new ArrayList<>();

    private String foregroundPackage;
    private String armedPackage;
    private long armedAtMs;

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
     * Whether the current visit needs foreground-change observation beyond the target packages.
     *
     * <p>The service normally filters event delivery to enabled rule packages. While the user
     * remains inside a navigation target it temporarily listens to every package, so leaving for
     * an unrelated app is observed and a later return is recognised as a fresh visit.
     */
    public boolean isForegroundNavigationTarget() {
        return foregroundPackage != null && ruleFor(foregroundPackage) != null;
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
        return armedPackage != null;
    }

    /**
     * The rule to act on for the currently active window, or null when there is nothing to do
     * -- either because no visit is armed, the active window belongs to a different app, or
     * the attempt window has closed. Expiry is resolved here so a stale arming cannot survive
     * to be acted on later.
     */
    public FilterRule armedRuleFor(CharSequence activePackage, long nowMs) {
        if (armedPackage == null) {
            return null;
        }
        if (nowMs - armedAtMs > ATTEMPT_WINDOW_MS) {
            armedPackage = null;
            return null;
        }
        if (activePackage == null || !armedPackage.contentEquals(activePackage)) {
            return null;
        }
        return ruleFor(armedPackage);
    }

    /** Whether an attempt is still outstanding, i.e. worth scheduling another retry for. */
    public boolean isArmed(long nowMs) {
        if (armedPackage != null && nowMs - armedAtMs > ATTEMPT_WINDOW_MS) {
            armedPackage = null;
        }
        return armedPackage != null;
    }

    /** Ends the current visit's navigation, after a successful click or a deliberate stop. */
    public void disarm() {
        armedPackage = null;
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
    }
}
