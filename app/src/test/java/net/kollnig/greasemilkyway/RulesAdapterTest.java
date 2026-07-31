package net.kollnig.greasemilkyway;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Covers the rule merging that lets several rules appear as one switch.
 */
@RunWith(RobolectricTestRunner.class)
public class RulesAdapterTest {

    private List<FilterRule> parse(String... ruleStrings) {
        return new FilterRuleParser().parseRules(ruleStrings);
    }

    @Test
    public void rulesSharingACommentBecomeOneRow() {
        List<List<FilterRule>> rows = RulesAdapter.mergeRules(parse(
                "com.example.app##path=A[*]##comment=Hide feed",
                "com.example.app##path=B[*]##comment=Hide feed"));

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).size());
    }

    @Test
    public void rulesWithDifferentCommentsStaySeparate() {
        List<List<FilterRule>> rows = RulesAdapter.mergeRules(parse(
                "com.example.app##path=A[*]##comment=Hide feed",
                "com.example.app##path=B[*]##comment=Hide stories"));

        assertEquals(2, rows.size());
    }

    /**
     * Categories only decide which section a row is filed under. If two parts
     * of one thing were given different categories, merging on category too
     * would split them back into two half-rows.
     */
    @Test
    public void differingCategoriesDoNotPreventMerging() {
        List<List<FilterRule>> rows = RulesAdapter.mergeRules(parse(
                "com.example.app##category=Feed##path=A[*]##comment=Hide feed",
                "com.example.app##category=Main screen##path=B[*]##comment=Hide feed"));

        assertEquals(1, rows.size());
    }

    /**
     * The element picker omits the comment when the user leaves the label
     * blank, and those rules all fall back to the same placeholder name.
     */
    @Test
    public void rulesWithoutCommentsNeverMerge() {
        List<List<FilterRule>> rows = RulesAdapter.mergeRules(parse(
                "com.example.app##path=A[*]",
                "com.example.app##path=B[*]"));

        assertEquals(2, rows.size());
    }

    @Test
    public void mergedRowKeepsThePositionOfItsFirstPart() {
        List<List<FilterRule>> rows = RulesAdapter.mergeRules(parse(
                "com.example.app##path=A[*]##comment=Hide ads",
                "com.example.app##path=B[*]##comment=Hide feed",
                "com.example.app##path=C[*]##comment=Hide ads"));

        assertEquals(2, rows.size());
        assertEquals("Hide ads", rows.get(0).get(0).description);
        assertEquals(2, rows.get(0).size());
        assertEquals("Hide feed", rows.get(1).get(0).description);
    }

    @Test
    public void rowIsOnlyEnabledWhenEveryPartIs() {
        List<FilterRule> row = parse(
                "com.example.app##path=A[*]##comment=Hide feed",
                "com.example.app##path=B[*]##comment=Hide feed");

        row.get(0).enabled = true;
        row.get(1).enabled = false;
        assertFalse(RulesAdapter.isRowEnabled(row));

        row.get(1).enabled = true;
        assertTrue(RulesAdapter.isRowEnabled(row));
    }

    /**
     * The bundled Instagram feed needs two rules -- sibling containers with
     * different classes -- and is the case this merging exists for.
     */
    @Test
    public void bundledFeedRulesMergeIntoOneRow() {
        List<FilterRule> feedRules = parse(
                "com.instagram.android##category=Feed##path=androidx.viewpager.widget.ViewPager[0]>android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[*]##comment=Hide feed",
                "com.instagram.android##category=Feed##path=androidx.viewpager.widget.ViewPager[0]>android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.widget.FrameLayout[*]##comment=Hide feed");

        List<List<FilterRule>> rows = RulesAdapter.mergeRules(feedRules);

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).size());
    }

    /**
     * A mixed row can't be deleted (deleting requires every part to be
     * custom) and toggling it would silently flip the user's own rule along
     * with the built-in one, so custom and built-in rules must never share a
     * row even when their comments match.
     */
    @Test
    public void customRuleNeverMergesWithABuiltInRuleSharingItsComment() {
        List<FilterRule> rules = parse(
                "com.example.app##path=A[*]##comment=Hide feed",
                "com.example.app##path=B[*]##comment=Hide feed");
        rules.get(1).isCustom = true;

        List<List<FilterRule>> rows = RulesAdapter.mergeRules(rules);

        assertEquals(2, rows.size());
    }

    /**
     * Navigation and blocking rules are stored under different preference keys, so a mixed
     * row's switch would have to write to both stores at once and deleting it would have to
     * remove from both. They are also the two things most likely to share a comment, since
     * hiding a tab and opening it describe the same element.
     */
    @Test
    public void navigationRuleNeverMergesWithABlockingRuleSharingItsComment() {
        List<FilterRule> rules = parse(
                "com.example.app##viewId=com.example.app:id/inbox##comment=Inbox",
                "com.example.app##viewId=com.example.app:id/inbox##comment=Inbox");
        rules.get(1).isNavigation = true;

        List<List<FilterRule>> rows = RulesAdapter.mergeRules(rules);

        assertEquals(2, rows.size());
        assertFalse(RulesAdapter.isNavigationRow(rows.get(0)));
        assertTrue(RulesAdapter.isNavigationRow(rows.get(1)));
    }

    /**
     * Merging exists so one switch can back the several rules it takes to hide one thing.
     * Opening a screen is a single click, and only one navigation rule per app runs, so a
     * merged navigation row would offer to enable rules that cannot all be on at once -- and
     * its own parts would switch each other off.
     */
    @Test
    public void navigationRulesNeverMergeEvenWhenTheyShareAComment() {
        List<FilterRule> rules = parse(
                "com.example.app##viewId=com.example.app:id/a##comment=Open inbox",
                "com.example.app##viewId=com.example.app:id/b##comment=Open inbox");
        for (FilterRule rule : rules) {
            rule.isNavigation = true;
        }

        assertEquals(2, RulesAdapter.mergeRules(rules).size());
    }

    /**
     * The list renders from the rule objects and carries their state forward rather than
     * re-reading preferences, so a rule switched off only in storage would keep its switch
     * visibly on and keep being counted as active -- the exact claim the one-per-app limit
     * exists to stop. Caught on device: prefs said off, the list said "2 active".
     */
    @Test
    public void displacedNavigationRuleIsMarkedDisabledInMemory() {
        List<FilterRule> rules = parse(
                "com.example.app##viewId=com.example.app:id/a##comment=Open A",
                "com.example.app##viewId=com.example.app:id/b##comment=Open B");
        for (FilterRule rule : rules) {
            rule.isNavigation = true;
            rule.enabled = true;
        }

        RulesAdapter.markOtherNavigationRulesDisabled(
                rules, Collections.singletonList(rules.get(1)));

        assertFalse(rules.get(0).enabled);
        assertTrue("the rule just switched on must stay on", rules.get(1).enabled);
    }

    @Test
    public void displacingLeavesOtherAppsAndBlockingRulesAlone() {
        List<FilterRule> rules = parse(
                "com.other.app##viewId=com.other.app:id/a##comment=Other app",
                "com.example.app##path=A[*]##comment=Hide something",
                "com.example.app##viewId=com.example.app:id/b##comment=Open B");
        rules.get(0).isNavigation = true;
        rules.get(2).isNavigation = true;
        for (FilterRule rule : rules) {
            rule.enabled = true;
        }

        RulesAdapter.markOtherNavigationRulesDisabled(
                rules, Collections.singletonList(rules.get(2)));

        assertTrue("a different app keeps its navigation rule", rules.get(0).enabled);
        assertTrue("blocking rules are a separate concern", rules.get(1).enabled);
    }
}
