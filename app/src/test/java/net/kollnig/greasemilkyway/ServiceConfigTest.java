package net.kollnig.greasemilkyway;

import android.content.Context;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ServiceConfigTest {

    private ServiceConfig config;
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        config = new ServiceConfig(context);
    }

    private FilterRule createRule(String ruleString) {
        FilterRuleParser parser = new FilterRuleParser();
        List<FilterRule> rules = parser.parseRules(new String[]{ruleString});
        return rules.isEmpty() ? null : rules.get(0);
    }

    // --- Rule enabled/disabled ---

    @Test
    public void ruleDisabledByDefault() {
        FilterRule rule = createRule("com.example.app##viewId=test");
        assertFalse(config.isRuleEnabled(rule));
    }

    @Test
    public void setAndGetRuleEnabled() {
        FilterRule rule = createRule("com.example.app##viewId=test");
        config.setRuleEnabled(rule, true);
        assertTrue(config.isRuleEnabled(rule));
    }

    @Test
    public void setRuleDisabledAfterEnabled() {
        FilterRule rule = createRule("com.example.app##viewId=test");
        config.setRuleEnabled(rule, true);
        config.setRuleEnabled(rule, false);
        assertFalse(config.isRuleEnabled(rule));
    }

    // --- Package disabled ---

    @Test
    public void packageDisabledByDefault() {
        assertTrue(config.isPackageDisabled("com.example.app"));
    }

    @Test
    public void setAndGetPackageDisabled() {
        config.setPackageDisabled("com.example.app", false);
        assertFalse(config.isPackageDisabled("com.example.app"));
    }

    @Test
    public void setPackageEnabled() {
        config.setPackageDisabled("com.example.app", false);
        assertFalse(config.isPackageDisabled("com.example.app"));
        config.setPackageDisabled("com.example.app", true);
        assertTrue(config.isPackageDisabled("com.example.app"));
    }

    // --- Rule pausing ---

    @Test
    public void rulePausedUntilDefaultsToZero() {
        FilterRule rule = createRule("com.example.app##viewId=test");
        assertEquals(0, config.getRulePausedUntil(rule));
    }

    @Test
    public void setAndGetRulePausedUntil() {
        FilterRule rule = createRule("com.example.app##viewId=test");
        long future = System.currentTimeMillis() + 60000;
        config.setRulePausedUntil(rule, future);
        assertEquals(future, config.getRulePausedUntil(rule));
    }

    // --- Package pausing ---

    @Test
    public void packagePausedUntilDefaultsToZero() {
        assertEquals(0, config.getPackagePausedUntil("com.example.app"));
    }

    @Test
    public void setAndGetPackagePausedUntil() {
        long future = System.currentTimeMillis() + 60000;
        config.setPackagePausedUntil("com.example.app", future);
        assertEquals(future, config.getPackagePausedUntil("com.example.app"));
    }

    // --- Friction word count ---

    @Test
    public void frictionWordCountDefaultsToZero() {
        assertEquals(0, config.getFrictionWordCount());
    }

    @Test
    public void setAndGetFrictionWordCount() {
        config.setFrictionWordCount(5);
        assertEquals(5, config.getFrictionWordCount());
    }

    // --- Pause duration ---

    @Test
    public void pauseDurationDefaultsToTen() {
        assertEquals(10, config.getPauseDurationMins());
    }

    @Test
    public void setAndGetPauseDurationMins() {
        config.setPauseDurationMins(30);
        assertEquals(30, config.getPauseDurationMins());
    }

    // --- Notification timeout ---

    @Test
    public void notificationTimeoutDefaultsTo100() {
        assertEquals(100, config.getNotificationTimeoutMs());
    }

    @Test
    public void setAndGetNotificationTimeoutMs() {
        config.setNotificationTimeoutMs(300);
        assertEquals(300, config.getNotificationTimeoutMs());
    }

    // --- Custom rules ---

    @Test
    public void customRulesDefaultsToNull() {
        assertNull(config.getCustomRules());
    }

    @Test
    public void addCustomRule() {
        config.addCustomRule("com.example.app##viewId=test");
        String[] rules = config.getCustomRules();
        assertNotNull(rules);
        assertEquals(1, rules.length);
        assertEquals("com.example.app##viewId=test", rules[0]);
    }

    @Test
    public void addMultipleCustomRules() {
        config.addCustomRule("com.app1##viewId=id1");
        config.addCustomRule("com.app2##viewId=id2");
        String[] rules = config.getCustomRules();
        assertNotNull(rules);
        assertEquals(2, rules.length);
        assertEquals("com.app1##viewId=id1", rules[0]);
        assertEquals("com.app2##viewId=id2", rules[1]);
    }

    @Test
    public void saveCustomRules() {
        config.saveCustomRules(new String[]{"rule1", "rule2", "rule3"});
        String[] rules = config.getCustomRules();
        assertNotNull(rules);
        assertEquals(3, rules.length);
        assertEquals("rule1", rules[0]);
        assertEquals("rule2", rules[1]);
        assertEquals("rule3", rules[2]);
    }

    @Test
    public void removeCustomRule() {
        config.addCustomRule("com.app1##viewId=id1");
        config.addCustomRule("com.app2##viewId=id2");
        config.addCustomRule("com.app3##viewId=id3");

        config.removeCustomRule("com.app2##viewId=id2");

        String[] rules = config.getCustomRules();
        assertNotNull(rules);
        assertEquals(2, rules.length);
        assertEquals("com.app1##viewId=id1", rules[0]);
        assertEquals("com.app3##viewId=id3", rules[1]);
    }

    @Test
    public void removeCustomRuleOnlyRemovesFirstMatch() {
        config.addCustomRule("com.app1##viewId=dup");
        config.addCustomRule("com.app1##viewId=dup");

        config.removeCustomRule("com.app1##viewId=dup");

        String[] rules = config.getCustomRules();
        assertNotNull(rules);
        assertEquals(1, rules.length);
        assertEquals("com.app1##viewId=dup", rules[0]);
    }

    @Test
    public void removeNonexistentCustomRuleDoesNothing() {
        config.addCustomRule("com.app1##viewId=id1");
        config.removeCustomRule("com.app2##viewId=nonexistent");

        String[] rules = config.getCustomRules();
        assertNotNull(rules);
        assertEquals(1, rules.length);
    }

    @Test
    public void removeCustomRuleFromNullDoesNothing() {
        // No custom rules set yet
        config.removeCustomRule("com.app1##viewId=test");
        assertNull(config.getCustomRules());
    }

    @Test
    public void saveEmptyCustomRulesClears() {
        config.addCustomRule("com.app1##viewId=id1");
        config.saveCustomRules(new String[]{});
        // Saving empty array results in empty string, getCustomRules returns null for empty
        assertNull(config.getCustomRules());
    }

    // --- getRules loads built-in rules ---

    @Test
    public void getRulesReturnsNonNull() {
        // getRules() should always return a list (never null), even if the asset is missing
        List<FilterRule> rules = config.getRules();
        assertNotNull(rules);
    }

    @Test
    public void getRulesIncludesCustomRules() {
        String customRule = "com.custom.app##viewId=com.custom.app:id/test##comment=Custom";
        config.addCustomRule(customRule);

        // Enable the custom rule
        FilterRule parsedRule = createRule(customRule);
        config.setRuleEnabled(parsedRule, true);
        config.setPackageDisabled("com.custom.app", false);

        List<FilterRule> rules = config.getRules();

        boolean found = false;
        for (FilterRule rule : rules) {
            if (rule.ruleString.equals(customRule)) {
                found = true;
                assertTrue(rule.isCustom);
                assertTrue(rule.enabled);
                break;
            }
        }
        assertTrue("Custom rule should be present in getRules()", found);
    }

    @Test
    public void getRulesDisabledPackageDisablesRules() {
        List<FilterRule> rules = config.getRules();
        // By default packages are disabled, so all rules should be disabled
        for (FilterRule rule : rules) {
            assertFalse(rule.enabled);
        }
    }

    // --- State persistence across instances ---

    @Test
    public void settingsPersistAcrossInstances() {
        config.setFrictionWordCount(7);
        config.setPauseDurationMins(20);
        config.setNotificationTimeoutMs(300);

        ServiceConfig config2 = new ServiceConfig(context);
        assertEquals(7, config2.getFrictionWordCount());
        assertEquals(20, config2.getPauseDurationMins());
        assertEquals(300, config2.getNotificationTimeoutMs());
    }

    @Test
    public void ruleEnabledStatePersistsAcrossInstances() {
        FilterRule rule = createRule("com.example.app##viewId=test");
        config.setRuleEnabled(rule, true);

        ServiceConfig config2 = new ServiceConfig(context);
        assertTrue(config2.isRuleEnabled(rule));
    }

    // --- Rule preference keys are derived from rule identity ---

    @Test
    public void editingPresentationFieldsPreservesRuleState() {
        // Comment, category and colour do not change what the rule matches, so
        // editing them must not orphan the user's saved state.
        FilterRule original = createRule(
                "com.example.app##viewId=test##category=Feed##comment=Old wording");
        config.setRuleEnabled(original, true);

        FilterRule edited = createRule(
                "com.example.app##viewId=test##category=Timeline##comment=New wording##color=00FF00");
        assertTrue(config.isRuleEnabled(edited));
    }

    @Test
    public void editingMatchingFieldsDoesNotReuseRuleState() {
        FilterRule original = createRule("com.example.app##viewId=test");
        config.setRuleEnabled(original, true);

        FilterRule different = createRule("com.example.app##viewId=other");
        assertFalse(config.isRuleEnabled(different));
    }

    @Test
    public void contentDescriptionOrderDoesNotAffectKey() {
        FilterRule oneOrder = createRule("com.example.app##desc=alpha|beta|gamma");
        config.setRuleEnabled(oneOrder, true);

        FilterRule otherOrder = createRule("com.example.app##desc=gamma|alpha|beta");
        assertTrue(config.isRuleEnabled(otherOrder));
    }

    @Test
    public void rulesDifferingOnlyByPackageHaveDistinctKeys() {
        FilterRule first = createRule("com.app1##viewId=test");
        FilterRule second = createRule("com.app2##viewId=test");
        config.setRuleEnabled(first, true);
        assertFalse(config.isRuleEnabled(second));
    }

    // --- Migration from legacy ruleString.hashCode() keys ---

    @Test
    public void migrationCarriesOverLegacyRuleState() {
        String ruleString = "com.custom.app##viewId=com.custom.app:id/x##comment=Custom";
        FilterRule rule = createRule(ruleString);
        long pausedUntil = System.currentTimeMillis() + 60000;

        // Simulate an install predating the key change.
        config.getPrefs().edit()
                .putBoolean(ServiceConfig.KEY_RULE_ENABLED + ruleString.hashCode(), true)
                .putLong("pause_until_rule_" + ruleString.hashCode(), pausedUntil)
                .apply();
        config.addCustomRule(ruleString);

        assertFalse("state should not be readable before migration", config.isRuleEnabled(rule));

        config.getRules();

        assertTrue("enabled state should survive the key change", config.isRuleEnabled(rule));
        assertEquals(pausedUntil, config.getRulePausedUntil(rule));
    }

    @Test
    public void migrationClearsLegacyKeys() {
        String ruleString = "com.custom.app##viewId=com.custom.app:id/x##comment=Custom";
        String legacyKey = ServiceConfig.KEY_RULE_ENABLED + ruleString.hashCode();

        config.getPrefs().edit().putBoolean(legacyKey, true).apply();
        config.addCustomRule(ruleString);
        config.getRules();

        assertFalse(config.getPrefs().contains(legacyKey));
    }

    @Test
    public void migrationDoesNotOverwriteNewerState() {
        String ruleString = "com.custom.app##viewId=com.custom.app:id/x##comment=Custom";
        FilterRule rule = createRule(ruleString);

        config.getPrefs().edit()
                .putBoolean(ServiceConfig.KEY_RULE_ENABLED + ruleString.hashCode(), true)
                .apply();
        config.addCustomRule(ruleString);
        // State already written under the new scheme wins over the legacy value.
        config.setRuleEnabled(rule, false);

        config.getRules();

        assertFalse(config.isRuleEnabled(rule));
    }

    @Test
    public void migrationRunsOnlyOnce() {
        String ruleString = "com.custom.app##viewId=com.custom.app:id/x##comment=Custom";
        FilterRule rule = createRule(ruleString);

        config.getPrefs().edit()
                .putBoolean(ServiceConfig.KEY_RULE_ENABLED + ruleString.hashCode(), true)
                .apply();
        config.addCustomRule(ruleString);
        config.getRules();
        assertTrue(config.isRuleEnabled(rule));

        // A later opt-out must not be undone by the legacy value reappearing.
        config.setRuleEnabled(rule, false);
        config.getPrefs().edit()
                .putBoolean(ServiceConfig.KEY_RULE_ENABLED + ruleString.hashCode(), true)
                .apply();
        config.getRules();

        assertFalse(config.isRuleEnabled(rule));
    }

    // --- Migration of bundled rules via the frozen 0.9.1 snapshot ---

    private List<FilterRule> parseAsset(String assetName) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(assetName), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read " + assetName, e);
        }
        return new FilterRuleParser().parseRules(lines.toArray(new String[0]));
    }

    /**
     * The snapshot only recovers a rule's state if that rule still exists with
     * the same matching fields, since identity is what the state is re-filed
     * under. Editing a bundled rule's viewId/desc/path/blockTouches therefore
     * silently drops the saved state of everyone upgrading from 0.9.1 -- which
     * is defensible for a rule that genuinely now targets something else, but
     * must be a deliberate choice rather than a slip. Adding new rules is fine.
     */
    @Test
    public void everySnapshotRuleStillExistsInTheBundledRules() {
        Set<String> current = new HashSet<>();
        for (FilterRule rule : parseAsset("distraction_rules.txt")) {
            current.add(rule.identity());
        }

        for (FilterRule legacy : parseAsset("legacy_rules_v0.txt")) {
            assertTrue("no bundled rule matches the 0.9.1 rule: " + legacy.ruleString,
                    current.contains(legacy.identity()));
        }
    }

    @Test
    public void migrationCarriesOverBuiltInStateAcrossEditedRuleText() {
        List<FilterRule> legacyRules = parseAsset("legacy_rules_v0.txt");
        long pausedUntil = System.currentTimeMillis() + 60000;
        for (FilterRule legacy : legacyRules) {
            config.getPrefs().edit()
                    .putBoolean(ServiceConfig.KEY_RULE_ENABLED + legacy.ruleString.hashCode(), true)
                    .putLong("pause_until_rule_" + legacy.ruleString.hashCode(), pausedUntil)
                    .apply();
        }

        List<FilterRule> rules = config.getRules();

        for (FilterRule legacy : legacyRules) {
            FilterRule current = null;
            for (FilterRule rule : rules) {
                if (rule.identity().equals(legacy.identity())) {
                    current = rule;
                    break;
                }
            }
            assertNotNull("bundled rule missing for " + legacy.ruleString, current);
            assertTrue("enabled state lost for " + legacy.ruleString,
                    config.isRuleEnabled(current));
            assertEquals("pause lost for " + legacy.ruleString,
                    pausedUntil, config.getRulePausedUntil(current));
        }
    }

    /**
     * The case the snapshot exists for: the bundled feed rules gained a
     * category and had their comments merged after 0.9.1, so their text-derived
     * legacy keys no longer match what users have stored.
     */
    @Test
    public void migrationHandlesBundledRulesWhoseTextChangedSinceRelease() {
        boolean sawEditedRule = false;
        Set<String> currentText = new HashSet<>();
        for (FilterRule rule : parseAsset("distraction_rules.txt")) {
            currentText.add(rule.ruleString);
        }
        for (FilterRule legacy : parseAsset("legacy_rules_v0.txt")) {
            if (!currentText.contains(legacy.ruleString)) {
                sawEditedRule = true;
                break;
            }
        }
        assertTrue("expected at least one bundled rule to have been edited since 0.9.1; "
                + "if none remain, migrationCarriesOverBuiltInStateAcrossEditedRuleText "
                + "no longer proves the snapshot is doing anything", sawEditedRule);
    }

    // --- Navigation rules ---

    @Test
    public void navigationRulesAreOffByDefault() {
        List<FilterRule> rules = config.getNavigationRules();
        assertFalse("expected bundled navigation rules", rules.isEmpty());
        for (FilterRule rule : rules) {
            assertFalse("navigation must be opt-in: " + rule.ruleString, rule.enabled);
        }
    }

    @Test
    public void setAndGetNavigationRuleEnabled() {
        FilterRule rule = config.getNavigationRules().get(0);
        config.setNavigationRuleEnabled(rule, true);
        config.setPackageDisabled(rule.packageName, false);

        assertTrue(config.isNavigationRuleEnabled(rule));
        for (FilterRule reloaded : config.getNavigationRules()) {
            if (reloaded.identity().equals(rule.identity())) {
                assertTrue("saved state must survive a reload", reloaded.enabled);
                assertTrue("navigation rules must be flagged for the UI", reloaded.isNavigation);
                return;
            }
        }
        fail("rule disappeared from the bundled navigation rules after being enabled");
    }

    /**
     * The app switch in the rules list is the master switch for everything ReDD Focus does
     * inside that app, and navigation rules are now shown underneath it. An app switched off
     * that still moved the user around would make that switch a lie.
     */
    @Test
    public void navigationRuleObeysThePackageSwitch() {
        FilterRule rule = config.getNavigationRules().get(0);
        config.setNavigationRuleEnabled(rule, true);
        config.setPackageDisabled(rule.packageName, true);

        for (FilterRule reloaded : config.getNavigationRules()) {
            if (reloaded.identity().equals(rule.identity())) {
                assertFalse("a disabled package must silence its navigation rules too",
                        reloaded.enabled);
                // The user's own choice is remembered, so re-enabling the app restores it.
                assertTrue(config.isNavigationRuleEnabled(reloaded));
                return;
            }
        }
        fail("rule disappeared from the bundled navigation rules");
    }

    @Test
    public void duplicateNavigationRuleIsDetectedByIdentityNotText() {
        String ruleString = "com.example.app##viewId=com.example.app:id/inbox##comment=Inbox";
        config.addCustomNavigationRule(ruleString);

        FilterRule sameTargetDifferentComment = createRule(
                "com.example.app##viewId=com.example.app:id/inbox##comment=Messages");

        // Comment is not part of identity, so these two would share one preference key and
        // appear as two rows driven by a single switch.
        assertTrue(config.hasNavigationRuleLike(sameTargetDifferentComment));
    }

    @Test
    public void unrelatedNavigationRuleIsNotTreatedAsDuplicate() {
        config.addCustomNavigationRule("com.example.app##viewId=com.example.app:id/inbox");

        assertFalse(config.hasNavigationRuleLike(
                createRule("com.example.app##viewId=com.example.app:id/other")));
    }

    /**
     * Navigation state is keyed separately from blocking state. The two rule sets can name the
     * same element -- hiding Instagram's inbox tab and opening it are both plausible -- and
     * sharing a key would make enabling one silently enable the other.
     */
    @Test
    public void navigationStateIsIndependentOfBlockingState() {
        FilterRule navRule = config.getNavigationRules().get(0);
        FilterRule blockingRule = createRule(navRule.ruleString);

        config.setNavigationRuleEnabled(navRule, true);

        assertTrue(config.isNavigationRuleEnabled(navRule));
        assertFalse(config.isRuleEnabled(blockingRule));
    }

    /**
     * A navigation rule must reach the service's own pipeline and no other. Landing in
     * {@link ServiceConfig#getRules()} would paint an overlay across the control it needs to
     * click, which is the one outcome that would make the feature block itself.
     */
    @Test
    public void navigationRulesStayOutOfTheBlockingRuleSet() {
        Set<String> blocking = new HashSet<>();
        for (FilterRule rule : config.getRules()) {
            blocking.add(rule.ruleString);
        }
        for (FilterRule rule : config.getNavigationRules()) {
            assertFalse("navigation rule leaked into the blocking rules: " + rule.ruleString,
                    blocking.contains(rule.ruleString));
        }
    }

    @Test
    public void enablingAPackageRestoresNavigationRulesFromTheirOwnPreferenceSpace() {
        String packageName = "com.example.navigation";
        String ruleString = packageName + "##viewId=" + packageName + ":id/inbox";
        config.addCustomNavigationRule(ruleString);

        FilterRule navigationRule = null;
        for (FilterRule rule : config.getNavigationRules()) {
            if (ruleString.equals(rule.ruleString)) {
                navigationRule = rule;
                break;
            }
        }
        assertNotNull(navigationRule);
        FilterRule blockingRule = createRule(ruleString);

        config.setNavigationRuleEnabled(navigationRule, true);
        config.setRuleEnabled(blockingRule, false);
        config.setPackageDisabled(packageName, true);

        List<FilterRule> rules = new ArrayList<>();
        rules.add(blockingRule);
        rules.add(navigationRule);
        config.enablePackageRules(packageName, rules);

        assertFalse("the blocking preference remains separate", blockingRule.enabled);
        assertTrue("the navigation preference is restored", navigationRule.enabled);
    }

    @Test
    public void enablingAPackageForTheFirstTimePersistsNavigationRules() {
        String packageName = "com.example.firstnavigation";
        String ruleString = packageName + "##viewId=" + packageName + ":id/inbox";
        config.addCustomNavigationRule(ruleString);

        FilterRule navigationRule = null;
        for (FilterRule rule : config.getNavigationRules()) {
            if (ruleString.equals(rule.ruleString)) {
                navigationRule = rule;
                break;
            }
        }
        assertNotNull(navigationRule);

        config.enablePackageRules(packageName, java.util.Collections.singletonList(navigationRule));

        assertTrue(navigationRule.enabled);
        assertTrue("a reload must keep the rule enabled",
                config.isNavigationRuleEnabled(navigationRule));
    }

    // --- Custom navigation rules (element picker) ---

    @Test
    public void customNavigationRuleIsAddedAndMarkedCustom() {
        String ruleString = "com.example.app##viewId=com.example.app:id/inbox##comment=Inbox";
        config.addCustomNavigationRule(ruleString);

        for (FilterRule rule : config.getNavigationRules()) {
            if (ruleString.equals(rule.ruleString)) {
                assertTrue("picker rules must be marked custom", rule.isCustom);
                return;
            }
        }
        fail("custom navigation rule did not come back from getNavigationRules()");
    }

    @Test
    public void customNavigationRuleCanBeRemoved() {
        String ruleString = "com.example.app##viewId=com.example.app:id/inbox";
        config.addCustomNavigationRule(ruleString);
        config.removeCustomNavigationRule(ruleString);

        assertNull(config.getCustomNavigationRules());
        for (FilterRule rule : config.getNavigationRules()) {
            assertNotEquals(ruleString, rule.ruleString);
        }
    }

    /**
     * The picker writes navigation rules to their own store. Sharing one with blocking rules
     * would make "open this on launch" also hide the element it needs to click.
     */
    @Test
    public void customNavigationRulesAreSeparateFromCustomBlockingRules() {
        String navRule = "com.example.app##viewId=com.example.app:id/inbox";
        config.addCustomNavigationRule(navRule);

        assertNull("navigation rules must not leak into the blocking store",
                config.getCustomRules());
        for (FilterRule rule : config.getRules()) {
            assertNotEquals(navRule, rule.ruleString);
        }
    }

    @Test
    public void bundledNavigationRulesSurviveAddingACustomOne() {
        int bundled = config.getNavigationRules().size();
        config.addCustomNavigationRule("com.example.app##viewId=com.example.app:id/inbox");

        assertEquals(bundled + 1, config.getNavigationRules().size());
    }

    @Test
    public void everyNavigationRuleHasATargetAndALabel() {
        for (FilterRule rule : config.getNavigationRules()) {
            boolean hasTarget = (rule.targetViewId != null && !rule.targetViewId.isEmpty())
                    || !rule.contentDescriptions.isEmpty();
            assertTrue("no viewId or desc to click: " + rule.ruleString, hasTarget);
            // The settings dialog lists rules by app and comment, so a blank one is unusable.
            assertNotNull("no comment: " + rule.ruleString, rule.description);
            assertFalse("blank comment: " + rule.ruleString, rule.description.trim().isEmpty());
        }
    }
}
