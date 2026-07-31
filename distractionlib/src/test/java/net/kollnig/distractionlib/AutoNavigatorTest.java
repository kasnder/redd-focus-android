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

    @Test
    public void armingExpiresAfterTheAttemptWindow() {
        navigator.onForegroundPackage(IG, 0);

        long tooLate = AutoNavigator.ATTEMPT_WINDOW_MS + 1;
        assertNull(navigator.armedRuleFor(IG, tooLate));
        assertFalse(navigator.isArmed(tooLate));
    }

    @Test
    public void armingSurvivesUntilTheWindowCloses() {
        navigator.onForegroundPackage(IG, 0);

        assertTrue(navigator.isArmed(AutoNavigator.ATTEMPT_WINDOW_MS));
        assertEquals(IG, navigator.armedRuleFor(IG, AutoNavigator.ATTEMPT_WINDOW_MS).packageName);
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
}
