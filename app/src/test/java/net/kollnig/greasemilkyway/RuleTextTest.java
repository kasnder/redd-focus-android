package net.kollnig.greasemilkyway;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuleTextTest {

    @Test
    public void withCommentAppendsCommentWhenMissing() {
        assertEquals("com.example.app##viewId=id##comment=New name",
                RuleText.withComment("com.example.app##viewId=id", "New name"));
    }

    @Test
    public void withCommentReplacesOnlyTheCommentFragment() {
        assertEquals("com.example.app##viewId=id##comment=New name##category=Feed",
                RuleText.withComment("com.example.app##viewId=id##comment=Old##category=Feed",
                        "New name"));
    }

    @Test
    public void withCommentPreservesUnknownFragments() {
        assertEquals("com.example.app##viewId=id##unknown=left##bare-fragment##comment=New",
                RuleText.withComment(
                        "com.example.app##viewId=id##unknown=left##bare-fragment", "New"));
    }

    @Test
    public void withCommentTreatsNavigationAndBlockingLinesTheSame() {
        String line = "com.example.app##desc=Inbox##comment=Old";
        assertEquals("com.example.app##desc=Inbox##comment=New",
                RuleText.withComment(line, "New"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void withCommentRejectsRuleDelimiterInjection() {
        RuleText.withComment("com.example.app##viewId=id##comment=Old",
                "New##viewId=other");
    }
}
