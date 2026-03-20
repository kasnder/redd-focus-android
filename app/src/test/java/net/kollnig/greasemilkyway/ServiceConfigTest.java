package net.kollnig.greasemilkyway;

import android.content.Context;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.HashSet;
import java.util.List;

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
}
