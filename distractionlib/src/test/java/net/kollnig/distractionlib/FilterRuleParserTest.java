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
                "com.whatsapp##viewId=com.whatsapp:id/fab##color=#FF0000##blockTouches=false##comment=Hide FAB"
        };
        List<FilterRule> rules = parser.parseRules(raw);

        assertEquals(1, rules.size());
        FilterRule rule = rules.get(0);
        assertEquals("com.whatsapp", rule.packageName);
        assertEquals("com.whatsapp:id/fab", rule.targetViewId);
        assertEquals(Color.parseColor("#FF0000"), rule.color);
        assertFalse(rule.blockTouches);
        assertEquals("Hide FAB", rule.description);
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
