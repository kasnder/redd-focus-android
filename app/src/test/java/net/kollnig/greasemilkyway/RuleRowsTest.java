package net.kollnig.greasemilkyway;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class RuleRowsTest {
    @Test public void mergesBlockingPartsButNeverNavigationRows() {
        List<FilterRule> blocking = new FilterRuleParser().parseRules(new String[] {
                "com.example##path=A[*]##comment=Hide feed", "com.example##path=B[*]##comment=Hide feed" });
        assertEquals(1, RuleRows.mergeRules(blocking).size());
        blocking.get(0).isNavigation = true;
        assertEquals(2, RuleRows.mergeRules(blocking).size());
    }
    @Test public void compactDestinationDoesNotRepeatNavigationVerb() {
        FilterRule rule = new FilterRuleParser().parseRules(new String[] {
                "com.example##viewId=com.example:id/a##comment=Open the Favourites list instead of all chats" }).get(0);
        assertEquals("Favourites list", RuleRows.compactNavigationDestination(rule));
    }
    @Test public void aRowIsActiveOnlyWhenAllPartsAreActive() {
        List<FilterRule> rules = new FilterRuleParser().parseRules(new String[] {
                "com.example##path=A[*]##comment=Hide feed", "com.example##path=B[*]##comment=Hide feed" });
        rules.get(0).enabled = rules.get(1).enabled = true;
        assertTrue(RuleRows.isRowEnabled(rules)); rules.get(1).enabled = false;
        assertFalse(RuleRows.isRowEnabled(rules));
    }
}
