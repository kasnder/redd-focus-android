package net.kollnig.greasemilkyway;

import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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
}
