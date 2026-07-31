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

    // --- Description comparison modes ---
    //
    // A content description is written to be read aloud, so it carries live status: badge
    // counts and selected states get appended to the label. Matching one exactly means the
    // rule dies when the count changes. That is a platform property, not an app's quirk.

    private static FilterRule descRule(String desc, FilterRule.DescriptionMatch match) {
        return new FilterRuleParser().parseRules(new String[]{
                "com.example.app##desc=" + desc
                        + (match == FilterRule.DescriptionMatch.EXACT
                        ? "" : "##descMatch=" + match.name().toLowerCase(java.util.Locale.ROOT))
        }).get(0);
    }

    @Test
    public void exactIsTheDefaultAndStaysStrict() {
        FilterRule rule = descRule("AI filter", FilterRule.DescriptionMatch.EXACT);

        assertEquals(FilterRule.DescriptionMatch.EXACT, rule.descriptionMatch);
        assertTrue(rule.matchesDescription("AI filter"));
        assertFalse(rule.matchesDescription("AI filter, 1, unselected"));
    }

    @Test
    public void prefixSurvivesAppendedStatus() {
        FilterRule rule = descRule("AI filter", FilterRule.DescriptionMatch.PREFIX);

        assertTrue(rule.matchesDescription("AI filter, 1, unselected"));
        // The whole point: the badge count changing must not break the rule.
        assertTrue(rule.matchesDescription("AI filter, 2, unselected"));
        assertTrue(rule.matchesDescription("AI filter, 2, selected"));
        assertFalse(rule.matchesDescription("Unread filter, 25, unselected"));
    }

    /**
     * A plain prefix, with no knowledge of how status is appended. Encoding a separator
     * convention in the matcher would be a rule the author cannot see in their own rule text;
     * the picker's ambiguity check is what catches the collision instead.
     */
    @Test
    public void prefixIsAPlainPrefixAndCanStillCollide() {
        FilterRule rule = descRule("Message", FilterRule.DescriptionMatch.PREFIX);

        assertTrue(rule.matchesDescription("Message"));
        assertTrue(rule.matchesDescription("Message your assistant"));
    }

    @Test
    public void substringMatchesAnywhere() {
        FilterRule rule = descRule("unread message", FilterRule.DescriptionMatch.SUBSTRING);

        assertTrue(rule.matchesDescription("3 unread messages"));
        assertFalse(rule.matchesDescription("Chats, 25 new notifications"));
    }

    @Test
    public void anyAlternativeMayMatchUnderTheChosenMode() {
        FilterRule rule = descRule("AI filter|Groups filter", FilterRule.DescriptionMatch.PREFIX);

        assertTrue(rule.matchesDescription("Groups filter, 5, unselected"));
        assertTrue(rule.matchesDescription("AI filter, 1, unselected"));
        assertFalse(rule.matchesDescription("Unread filter, 25, unselected"));
    }

    @Test
    public void nullDescriptionNeverMatches() {
        assertFalse(descRule("AI filter", FilterRule.DescriptionMatch.PREFIX)
                .matchesDescription(null));
    }

    /**
     * An unreadable mode would silently fall back to exact matching, which is a different rule
     * from the one that was written -- the same fail-closed choice made for screen markers.
     */
    @Test
    public void unknownDescriptionMatchModeDropsTheRule() {
        assertTrue(new FilterRuleParser().parseRules(new String[]{
                "com.example.app##desc=AI filter##descMatch=fuzzy"}).isEmpty());
    }

    /**
     * The mode is behavioural, so it has to separate identities -- but it is appended only
     * when it is not the default, so that every rule written before the feature existed keeps
     * the key its saved state is already stored under.
     */
    @Test
    public void defaultModeLeavesIdentityByteForByteUnchanged() {
        FilterRule plain = new FilterRuleParser().parseRules(new String[]{
                "com.example.app##desc=AI filter"}).get(0);

        // package \n viewId \n descs \n className \n text \n path \n childPath \n
        // minThumbnailWidthDp \n screenCondition \n blockTouches -- and nothing after it.
        assertEquals("com.example.app\n\nAI filter\n\n\n\n\n0\n\ntrue", plain.identity());
    }

    @Test
    public void looserModesGetTheirOwnIdentity() {
        String exact = descRule("AI filter", FilterRule.DescriptionMatch.EXACT).identity();
        String prefix = descRule("AI filter", FilterRule.DescriptionMatch.PREFIX).identity();
        String substring = descRule("AI filter", FilterRule.DescriptionMatch.SUBSTRING).identity();

        assertNotEquals(exact, prefix);
        assertNotEquals(prefix, substring);
        assertNotEquals(exact, substring);
    }
}
