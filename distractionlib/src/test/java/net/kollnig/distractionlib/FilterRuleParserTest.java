package net.kollnig.distractionlib;

import android.graphics.Color;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class FilterRuleParserTest {

    private FilterRuleParser parser;

    @Before
    public void setUp() {
        parser = new FilterRuleParser();
    }

    @Test
    public void parseEmptyInput() {
        List<FilterRule> rules = parser.parseRules(new String[]{});
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseBlankLines() {
        List<FilterRule> rules = parser.parseRules(new String[]{"", "  ", ""});
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseCommentOnlyLines() {
        List<FilterRule> rules = parser.parseRules(new String[]{"// this is a comment"});
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseSimpleViewIdRule() {
        String[] raw = {"com.example.app##viewId=com.example.app:id/button"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        FilterRule rule = rules.get(0);
        assertEquals("com.example.app", rule.packageName);
        assertEquals("com.example.app:id/button", rule.targetViewId);
        assertNull(rule.targetClassName);
        assertNull(rule.targetText);
        assertNull(rule.targetPath);
        assertTrue(rule.contentDescriptions.isEmpty());
        assertEquals(Color.WHITE, rule.color);
        assertTrue(rule.blockTouches);
        assertTrue(rule.enabled);
    }

    @Test
    public void parseDescriptionRule() {
        String[] raw = {"com.example.app##desc=reels tray container"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        FilterRule rule = rules.get(0);
        assertEquals(1, rule.contentDescriptions.size());
        assertTrue(rule.contentDescriptions.contains("reels tray container"));
    }

    @Test
    public void parsePipeSeparatedDescriptions() {
        String[] raw = {"com.example.app##desc=desc1|desc2|desc3"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        FilterRule rule = rules.get(0);
        assertEquals(3, rule.contentDescriptions.size());
        assertTrue(rule.contentDescriptions.contains("desc1"));
        assertTrue(rule.contentDescriptions.contains("desc2"));
        assertTrue(rule.contentDescriptions.contains("desc3"));
    }

    @Test
    public void parseClassNameRule() {
        String[] raw = {"com.example.app##className=android.widget.Button"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("android.widget.Button", rules.get(0).targetClassName);
    }

    @Test
    public void parseTextRule() {
        String[] raw = {"com.example.app##text=Click me"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("Click me", rules.get(0).targetText);
    }

    @Test
    public void parsePathRule() {
        String[] raw = {"com.example.app##path=LinearLayout[0]>FrameLayout[1]"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("LinearLayout[0]>FrameLayout[1]", rules.get(0).targetPath);
    }

    @Test
    public void parseAnchoredPathRule() {
        String[] raw = {"com.example.app##viewId=com.example.app:id/list"
                + "##childPath=android.view.ViewGroup[*]"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("com.example.app:id/list", rules.get(0).targetViewId);
        assertEquals("android.view.ViewGroup[*]", rules.get(0).targetChildPath);
        assertNull(rules.get(0).targetPath);
    }

    @Test
    public void parseThumbnailWidthInDp() {
        String[] raw = {
                "com.example.app##viewId=test##hasThumbnail=160",
                "com.example.app##viewId=test##hasThumbnail=160dp"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(160, rules.get(0).minThumbnailWidthDp);
        assertEquals(160, rules.get(1).minThumbnailWidthDp);
    }

    @Test
    public void parseThumbnailTrueUsesDefaultWidth() {
        String[] raw = {"com.example.app##viewId=test##hasThumbnail=true"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(FilterRuleParser.DEFAULT_MIN_THUMBNAIL_WIDTH_DP,
                rules.get(0).minThumbnailWidthDp);
    }

    @Test
    public void parseThumbnailFalseDisablesCheck() {
        String[] raw = {"com.example.app##viewId=test##hasThumbnail=false"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(0, rules.get(0).minThumbnailWidthDp);
    }

    @Test
    public void parseUnusableThumbnailWidthFallsBackToDefault() {
        // Falling back to the default keeps the rule narrow. Disabling the check would widen
        // it to every element the rule is paired with, which is the more damaging mistake.
        String[] raw = {
                "com.example.app##viewId=test##hasThumbnail=wide",
                "com.example.app##viewId=test##hasThumbnail=0.7",
                "com.example.app##viewId=test##hasThumbnail=12"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(3, rules.size());
        for (FilterRule rule : rules) {
            assertEquals(FilterRuleParser.DEFAULT_MIN_THUMBNAIL_WIDTH_DP,
                    rule.minThumbnailWidthDp);
        }
    }

    @Test
    public void parseRuleWithoutThumbnailKeyDisablesCheck() {
        String[] raw = {"com.example.app##viewId=test"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(0, rules.get(0).minThumbnailWidthDp);
    }

    @Test
    public void parseRuleWithoutScreenMarkersAppliesEverywhere() {
        String[] raw = {"com.example.app##viewId=test"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertNull(rules.get(0).screenCondition);
    }

    @Test
    public void parseRequiredViewIdMarker() {
        String[] raw = {"com.example.app##viewId=test##requiresViewId=com.example.app:id/logo"};
        List<FilterRule> rules = parser.parseRules(raw);

        FilterRule.ScreenCondition condition = rules.get(0).screenCondition;
        assertNotNull(condition);
        assertEquals("com.example.app:id/logo", condition.requiredViewId);
        assertNull(condition.selectedViewId);
    }

    @Test
    public void parseSelectedMarkerWithChildPath() {
        String[] raw = {"com.example.app##viewId=test##requiresSelected=com.example.app:id/nav"
                + ">android.widget.LinearLayout[0]>android.widget.Button[0]"};
        List<FilterRule> rules = parser.parseRules(raw);

        FilterRule.ScreenCondition condition = rules.get(0).screenCondition;
        assertNotNull(condition);
        assertEquals("com.example.app:id/nav", condition.selectedViewId);
        assertEquals("android.widget.LinearLayout[0]>android.widget.Button[0]",
                condition.selectedChildPath);
    }

    @Test
    public void parseSelectedMarkerWithoutChildPath() {
        String[] raw = {"com.example.app##viewId=test##requiresSelected=com.example.app:id/tab"};
        List<FilterRule> rules = parser.parseRules(raw);

        FilterRule.ScreenCondition condition = rules.get(0).screenCondition;
        assertNotNull(condition);
        assertEquals("com.example.app:id/tab", condition.selectedViewId);
        assertNull(condition.selectedChildPath);
    }

    @Test
    public void parseSelectedMarkerWithEmptyPathIsIgnored() {
        String[] raw = {"com.example.app##viewId=test##requiresSelected=com.example.app:id/nav>"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertNull(rules.get(0).screenCondition);
    }

    @Test
    public void parseBothScreenMarkers() {
        String[] raw = {"com.example.app##viewId=test##requiresViewId=com.example.app:id/logo"
                + "##requiresSelected=com.example.app:id/nav>android.widget.Button[0]"};
        List<FilterRule> rules = parser.parseRules(raw);

        FilterRule.ScreenCondition condition = rules.get(0).screenCondition;
        assertNotNull(condition);
        assertEquals("com.example.app:id/logo", condition.requiredViewId);
        assertEquals("com.example.app:id/nav", condition.selectedViewId);
        assertEquals("android.widget.Button[0]", condition.selectedChildPath);
    }

    @Test
    public void parseColorWithHash() {
        String[] raw = {"com.example.app##viewId=test##color=#FF0000"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals(Color.parseColor("#FF0000"), rules.get(0).color);
    }

    @Test
    public void parseColorWithoutHash() {
        String[] raw = {"com.example.app##viewId=test##color=00FF00"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals(Color.parseColor("#00FF00"), rules.get(0).color);
    }

    @Test
    public void parseInvalidColorFallsBackToWhite() {
        String[] raw = {"com.example.app##viewId=test##color=notacolor"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals(Color.WHITE, rules.get(0).color);
    }

    @Test
    public void parseBlockTouchesFalse() {
        String[] raw = {"com.example.app##viewId=test##blockTouches=false"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertFalse(rules.get(0).blockTouches);
    }

    @Test
    public void parseBlockTouchesTrue() {
        String[] raw = {"com.example.app##viewId=test##blockTouches=true"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertTrue(rules.get(0).blockTouches);
    }

    @Test
    public void parseInlineComment() {
        String[] raw = {"com.example.app##viewId=test##comment=Hide button"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("Hide button", rules.get(0).description);
    }

    @Test
    public void parseCategory() {
        String[] raw = {"com.example.app##viewId=test##category=Feed"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("Feed", rules.get(0).category);
    }

    @Test
    public void parsePrefixCommentAppliedToNextRule() {
        String[] raw = {
                "// Hide the stories bar",
                "com.instagram.android##desc=reels tray container"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("Hide the stories bar", rules.get(0).description);
    }

    @Test
    public void parseInlineCommentOverridesPrefixComment() {
        String[] raw = {
                "// Old comment",
                "com.example.app##viewId=test##comment=New comment"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("New comment", rules.get(0).description);
    }

    @Test
    public void parseMultipleRules() {
        String[] raw = {
                "com.app1##viewId=id1",
                "com.app2##viewId=id2",
                "com.app3##className=SomeClass"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(3, rules.size());
        assertEquals("com.app1", rules.get(0).packageName);
        assertEquals("com.app2", rules.get(1).packageName);
        assertEquals("com.app3", rules.get(2).packageName);
    }

    @Test
    public void parseRuleMissingPackageName() {
        String[] raw = {"##viewId=test"};
        List<FilterRule> rules = parser.parseRules(raw);
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseRuleWithOnlyPackageName() {
        // Only one part after split - should be skipped (< 2 parts)
        String[] raw = {"com.example.app"};
        List<FilterRule> rules = parser.parseRules(raw);
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseRuleSkipsPartsWithoutEquals() {
        String[] raw = {"com.example.app##invalidpart##viewId=test"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("test", rules.get(0).targetViewId);
    }

    @Test
    public void parseComplexRule() {
        String[] raw = {
                "com.whatsapp##viewId=com.whatsapp:id/fab##category=Navigation##color=#FF0000##blockTouches=false##comment=Hide FAB"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        FilterRule rule = rules.get(0);
        assertEquals("com.whatsapp", rule.packageName);
        assertEquals("com.whatsapp:id/fab", rule.targetViewId);
        assertEquals(Color.parseColor("#FF0000"), rule.color);
        assertFalse(rule.blockTouches);
        assertEquals("Hide FAB", rule.description);
        assertEquals("Navigation", rule.category);
    }

    @Test
    public void ruleStringPreservedVerbatim() {
        String line = "com.example.app##viewId=com.example.app:id/button##comment=Test";
        String[] raw = {line};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals(line, rules.get(0).ruleString);
    }

    @Test
    public void parseMixedBlankAndCommentLines() {
        String[] raw = {
                "",
                "// comment",
                "",
                "com.example.app##viewId=test",
                "",
                "// another comment",
                ""
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("comment", rules.get(0).description);
    }

    @Test
    public void parseDescriptionWithEmptyParts() {
        String[] raw = {"com.example.app##desc=a||b|"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals(2, rules.get(0).contentDescriptions.size());
        assertTrue(rules.get(0).contentDescriptions.contains("a"));
        assertTrue(rules.get(0).contentDescriptions.contains("b"));
    }

    @Test
    public void parseValueContainingEquals() {
        // The split("=", 2) should handle values that contain '='
        String[] raw = {"com.example.app##text=a=b"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertEquals("a=b", rules.get(0).targetText);
    }

    @Test
    public void defaultEnabledState() {
        String[] raw = {"com.example.app##viewId=test"};
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        assertTrue(rules.get(0).enabled);
        assertFalse(rules.get(0).isCustom);
        assertFalse(rules.get(0).isPaused);
        assertEquals(0, rules.get(0).pausedUntil);
    }
}
