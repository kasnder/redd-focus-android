package net.kollnig.distractionlib;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashSet;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class FilterRuleTest {

    private FilterRule createRule(String pkg, String ruleString) {
        return new FilterRule(pkg, null, new HashSet<>(), null, null, null,
                0xFFFFFFFF, null, ruleString, true);
    }

    @Test
    public void matchesPackageWithSamePackage() {
        FilterRule rule = createRule("com.example.app", "com.example.app##viewId=test");
        assertTrue(rule.matchesPackage("com.example.app"));
    }

    @Test
    public void matchesPackageWithDifferentPackage() {
        FilterRule rule = createRule("com.example.app", "com.example.app##viewId=test");
        assertFalse(rule.matchesPackage("com.other.app"));
    }

    @Test
    public void matchesPackageWithNull() {
        FilterRule rule = createRule("com.example.app", "com.example.app##viewId=test");
        assertFalse(rule.matchesPackage(null));
    }

    @Test
    public void equalsBasedOnRuleString() {
        FilterRule rule1 = createRule("com.example.app", "com.example.app##viewId=test");
        FilterRule rule2 = createRule("com.example.app", "com.example.app##viewId=test");
        assertEquals(rule1, rule2);
    }

    @Test
    public void notEqualWithDifferentRuleString() {
        FilterRule rule1 = createRule("com.example.app", "com.example.app##viewId=test1");
        FilterRule rule2 = createRule("com.example.app", "com.example.app##viewId=test2");
        assertNotEquals(rule1, rule2);
    }

    @Test
    public void hashCodeConsistentWithEquals() {
        FilterRule rule1 = createRule("com.example.app", "com.example.app##viewId=test");
        FilterRule rule2 = createRule("com.example.app", "com.example.app##viewId=test");
        assertEquals(rule1.hashCode(), rule2.hashCode());
    }

    @Test
    public void notEqualToNull() {
        FilterRule rule = createRule("com.example.app", "com.example.app##viewId=test");
        assertNotEquals(null, rule);
    }

    @Test
    public void notEqualToDifferentType() {
        FilterRule rule = createRule("com.example.app", "com.example.app##viewId=test");
        assertNotEquals("a string", rule);
    }

    @Test
    public void equalToItself() {
        FilterRule rule = createRule("com.example.app", "com.example.app##viewId=test");
        assertEquals(rule, rule);
    }
}
