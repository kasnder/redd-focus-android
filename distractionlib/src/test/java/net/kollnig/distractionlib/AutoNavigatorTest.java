package net.kollnig.distractionlib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AutoNavigatorTest {

    private static final String IG = "com.instagram.android";
    private static final String WA = "com.whatsapp";
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";

    private AutoNavigator navigator;

    @Before
    public void setUp() {
        navigator = new AutoNavigator();
        navigator.setRules(Arrays.asList(rule(IG, "id/direct_tab"), rule(WA, "id/favourites")));
    }

    private static FilterRule rule(String pkg, String viewId) {
        FilterRule rule = new FilterRule(pkg, viewId, Collections.emptySet(), null, null, null,
                0, "open messages", pkg + "##viewId=" + viewId, true);
        rule.enabled = true;
        return rule;
    }

    @Test
    public void entersAppAndArmsNavigation() {
        assertTrue(navigator.onForegroundPackage(IG, 0));
        assertEquals(IG, navigator.armedRuleFor(IG, 0).packageName);
    }

    @Test
    public void appWithoutRuleDoesNotArm() {
        assertFalse(navigator.onForegroundPackage("com.android.chrome", 0));
        assertNull(navigator.armedRuleFor("com.android.chrome", 0));
    }

    @Test
    public void stayingInTheSameAppDoesNotRearm() {
        navigator.onForegroundPackage(IG, 0);
        navigator.disarm();

        // Further window changes inside the app must not send the user back to the target
        // screen; that is what lets them navigate away deliberately.
        assertFalse(navigator.onForegroundPackage(IG, 100));
        assertNull(navigator.armedRuleFor(IG, 100));
    }

    @Test
    public void leavingAndReturningArmsAgain() {
        navigator.onForegroundPackage(IG, 0);
        navigator.disarm();

        assertFalse(navigator.onForegroundPackage(LAUNCHER, 1000));
        assertTrue(navigator.onForegroundPackage(IG, 2000));
        assertEquals(IG, navigator.armedRuleFor(IG, 2000).packageName);
    }

    @Test
    public void switchingBetweenTwoTargetAppsArmsTheSecond() {
        navigator.onForegroundPackage(IG, 0);
        assertTrue(navigator.onForegroundPackage(WA, 500));

        assertNull(navigator.armedRuleFor(IG, 500));
        assertEquals(WA, navigator.armedRuleFor(WA, 500).packageName);
    }

    /**
     * Launching an app and being able to read its screen are far apart and unpredictably so --
     * measured cold starts ranged from 65ms to several seconds. Nothing can be found in that
     * time, so it must not be charged against the time allowed for finding the element.
     */
    @Test
    public void slowStartupDoesNotConsumeTheSearchWindow() {
        navigator.onForegroundPackage(IG, 0);

        long slowStart = AutoNavigator.SEARCH_WINDOW_MS + 5000;
        assertTrue("still waiting for the app, not timed out", navigator.isArmed(slowStart));

        navigator.noteAppeared(slowStart);

        assertEquals(IG, navigator.armedRuleFor(IG, slowStart + AutoNavigator.SEARCH_WINDOW_MS)
                .packageName);
    }

    @Test
    public void givesUpWhenTheAppNeverReachesTheForeground() {
        navigator.onForegroundPackage(IG, 0);

        long tooLate = AutoNavigator.APPEARANCE_WINDOW_MS + 1;
        assertFalse(navigator.isArmed(tooLate));
        assertFalse("never seen, so the failure is a missing app, not a missing element",
                navigator.hasAppeared());
    }

    /**
     * The window that has to stay short: once the app is on screen, every extra second is one
     * in which the user may have started doing something a jump would interrupt.
     */
    /**
     * An app can announce itself without coming to the front -- a window event from a card in
     * the app switcher, for instance. Recording that as a visit is worse than useless: the
     * next genuine open finds the app already current, decides nothing changed, and does
     * nothing. This is the shape of "I closed it and opened it again and it stopped working".
     */
    @Test
    public void aVisitThatNeverAppearedDoesNotBlockTheNextOne() {
        navigator.onForegroundPackage(IG, 0);
        navigator.isArmed(AutoNavigator.APPEARANCE_WINDOW_MS + 1);

        assertTrue("the genuine open must still count as a new visit",
                navigator.onForegroundPackage(IG, AutoNavigator.APPEARANCE_WINDOW_MS + 2));
    }

    /** A visit that did appear is a real one, so returning to it is not a fresh arrival. */
    @Test
    public void aVisitThatAppearedStillSuppressesRearming() {
        navigator.onForegroundPackage(IG, 0);
        navigator.noteAppeared(100);
        navigator.isArmed(100 + AutoNavigator.SEARCH_WINDOW_MS + 1);

        assertFalse(navigator.onForegroundPackage(IG, 100 + AutoNavigator.SEARCH_WINDOW_MS + 2));
    }

    @Test
    public void searchWindowRunsFromTheAppAppearing() {
        navigator.onForegroundPackage(IG, 0);
        navigator.noteAppeared(1000);

        assertTrue(navigator.isArmed(1000 + AutoNavigator.SEARCH_WINDOW_MS));
        assertFalse(navigator.isArmed(1000 + AutoNavigator.SEARCH_WINDOW_MS + 1));
        assertTrue("the app was seen, so this is a missing element", navigator.hasAppeared());
    }

    @Test
    public void onlyTheFirstAppearanceStartsTheClock() {
        navigator.onForegroundPackage(IG, 0);
        navigator.noteAppeared(1000);
        // An app that keeps redrawing must not be able to extend its own deadline.
        navigator.noteAppeared(5000);

        assertFalse(navigator.isArmed(1000 + AutoNavigator.SEARCH_WINDOW_MS + 1));
    }

    @Test
    public void rearmingClearsAPreviousAppearance() {
        navigator.onForegroundPackage(IG, 0);
        navigator.noteAppeared(1000);
        navigator.onForegroundPackage(LAUNCHER, 2000);
        navigator.onForegroundPackage(IG, 3000);

        assertFalse(navigator.hasAppeared());
        assertTrue("the new visit gets a full appearance window",
                navigator.isArmed(3000 + AutoNavigator.SEARCH_WINDOW_MS + 1));
    }

    @Test
    public void doesNotActOnAWindowBelongingToAnotherApp() {
        navigator.onForegroundPackage(IG, 0);

        // A dialog or overlay from elsewhere must not be mistaken for the armed app.
        assertNull(navigator.armedRuleFor("com.android.chrome", 100));
        // ...and the arming survives it, since the visit to Instagram has not ended.
        assertEquals(IG, navigator.armedRuleFor(IG, 100).packageName);
    }

    @Test
    public void resetMakesTheNextVisitCountAsFresh() {
        navigator.onForegroundPackage(IG, 0);
        navigator.reset();

        assertFalse(navigator.isArmed(0));
        assertTrue(navigator.onForegroundPackage(IG, 1000));
    }

    /**
     * The service is reconnected whenever it is re-enabled, updated, or displaced by another
     * accessibility client. Coming back while the user sits in a target app must not read as
     * them opening it, or a restart would jump them to the target screen mid-session.
     */
    @Test
    public void restartingWhileInsideATargetAppDoesNotNavigate() {
        AutoNavigator restarted = new AutoNavigator();
        restarted.setRules(Collections.singletonList(rule(IG, "id/direct_tab")));
        restarted.adoptForegroundPackage(IG);

        assertFalse(restarted.isArmed(0));
        assertFalse("a restart is not a visit", restarted.onForegroundPackage(IG, 100));
    }

    @Test
    public void leavingAfterARestartStillArmsOnReturn() {
        AutoNavigator restarted = new AutoNavigator();
        restarted.setRules(Collections.singletonList(rule(IG, "id/direct_tab")));
        restarted.adoptForegroundPackage(IG);

        restarted.onForegroundPackage(LAUNCHER, 1000);
        assertTrue(restarted.onForegroundPackage(IG, 2000));
    }

    @Test
    public void adoptingAnUnknownForegroundAppLeavesTargetsArmable() {
        navigator.adoptForegroundPackage("com.android.chrome");

        assertFalse(navigator.isArmed(0));
        assertTrue(navigator.onForegroundPackage(IG, 100));
    }

    @Test
    public void disabledRulesAreIgnored() {
        FilterRule disabled = rule(IG, "id/direct_tab");
        disabled.enabled = false;
        navigator.setRules(Collections.singletonList(disabled));

        assertFalse(navigator.hasRules());
        assertFalse(navigator.onForegroundPackage(IG, 0));
    }

    @Test
    public void turningARuleOffDropsAPendingNavigation() {
        navigator.onForegroundPackage(IG, 0);
        assertTrue(navigator.isArmed(0));

        navigator.setRules(Collections.singletonList(rule(WA, "id/favourites")));

        assertFalse(navigator.isArmed(0));
        assertNull(navigator.armedRuleFor(IG, 0));
    }

    @Test
    public void ruleForMatchesOnlyItsOwnPackage() {
        assertEquals(IG, navigator.ruleFor(IG).packageName);
        assertNull(navigator.ruleFor("com.instagram.android.other"));
        assertNull(navigator.ruleFor(null));
    }

    @Test
    public void ignoresEmptyAndNullPackages() {
        assertFalse(navigator.onForegroundPackage(null, 0));
        assertFalse(navigator.onForegroundPackage("", 0));
    }

    @Test
    public void rulesSnapshotIsNotWritableByCallers() {
        List<FilterRule> exposed = navigator.getRules();
        try {
            exposed.clear();
            org.junit.Assert.fail("expected the rule list to be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // The service hands this list straight to its event-filter setup; letting a
            // caller mutate it would silently change which packages are observed.
        }
    }

    /**
     * Storage enforces one rule per app, but the service has no way to check that itself. If
     * the invariant ever broke, the extras would be inert while still appearing switched on --
     * so it is reported rather than silently tolerated.
     */
    @Test
    public void surplusRulesForOneAppAreReported() {
        navigator.setRules(Arrays.asList(
                rule(IG, "id/direct_tab"), rule(IG, "id/other"), rule(WA, "id/favourites")));

        assertEquals(Collections.singletonList(IG), navigator.packagesWithSurplusRules());
    }

    @Test
    public void oneRulePerAppReportsNoSurplus() {
        assertTrue(navigator.packagesWithSurplusRules().isEmpty());
    }
}
