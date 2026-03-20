package net.kollnig.distractionlib;

import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class ElementPickerRuleGeneratorTest {

    private AccessibilityNodeInfo rootNode;

    @Before
    public void setUp() {
        rootNode = mock(AccessibilityNodeInfo.class);
    }

    @Test
    public void generateRuleWithViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/button");
        when(node.getText()).thenReturn(null);
        when(node.getClassName()).thenReturn("android.widget.Button");

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", null);

        assertEquals("com.example##viewId=com.example:id/button", rule);
    }

    @Test
    public void generateRuleWithViewIdAndComment() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/fab");
        when(node.getText()).thenReturn(null);
        when(node.getClassName()).thenReturn("android.widget.Button");

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", "Hide FAB");

        assertEquals("com.example##viewId=com.example:id/fab##comment=Hide FAB", rule);
    }

    @Test
    public void generateRuleFallsBackToTextWhenNoViewIdOrPath() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getText()).thenReturn("Click me");
        when(node.getClassName()).thenReturn("android.widget.TextView");
        when(node.getParent()).thenReturn(null);

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", null);

        assertEquals("com.example##text=Click me", rule);
    }

    @Test
    public void generateRuleFallsBackToClassNameWhenNoViewIdPathOrText() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getText()).thenReturn(null);
        when(node.getClassName()).thenReturn("android.widget.ImageView");
        when(node.getParent()).thenReturn(null);

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", null);

        assertEquals("com.example##className=android.widget.ImageView", rule);
    }

    @Test
    public void generateRuleWithEmptyViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("");
        when(node.getText()).thenReturn("Some text");
        when(node.getClassName()).thenReturn("android.widget.TextView");
        when(node.getParent()).thenReturn(null);

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", null);

        assertEquals("com.example##text=Some text", rule);
    }

    @Test
    public void generateRuleWithEmptyComment() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/btn");
        when(node.getText()).thenReturn(null);
        when(node.getClassName()).thenReturn(null);

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", "");

        assertEquals("com.example##viewId=com.example:id/btn", rule);
    }

    @Test
    public void describeNodeWithClassNameOnly() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.Button");
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn(null);
        when(node.getText()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.describeNode(node);

        assertEquals("Button", desc);
    }

    @Test
    public void describeNodeWithViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.Button");
        when(node.getViewIdResourceName()).thenReturn("com.example:id/submit_btn");
        when(node.getContentDescription()).thenReturn(null);
        when(node.getText()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.describeNode(node);

        assertEquals("Button #submit_btn", desc);
    }

    @Test
    public void describeNodeWithContentDescription() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.ImageView");
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn("Profile picture");
        when(node.getText()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.describeNode(node);

        assertEquals("ImageView \"Profile picture\"", desc);
    }

    @Test
    public void describeNodeWithText() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.TextView");
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn(null);
        when(node.getText()).thenReturn("Hello World");

        String desc = ElementPickerRuleGenerator.describeNode(node);

        assertEquals("TextView [Hello World]", desc);
    }

    @Test
    public void describeNodeWithAllFields() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.Button");
        when(node.getViewIdResourceName()).thenReturn("com.app:id/ok_btn");
        when(node.getContentDescription()).thenReturn("OK");
        when(node.getText()).thenReturn("OK");

        String desc = ElementPickerRuleGenerator.describeNode(node);

        assertEquals("Button #ok_btn \"OK\" [OK]", desc);
    }

    @Test
    public void describeNodeTruncatesLongContentDescription() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.TextView");
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn(
                "This is a very long content description that should be truncated");
        when(node.getText()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.describeNode(node);

        assertTrue(desc.contains("..."));
    }

    @Test
    public void getSelectorDescriptionPrefersViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/button");
        when(node.getText()).thenReturn("Click");
        when(node.getClassName()).thenReturn("android.widget.Button");

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("View ID: com.example:id/button", desc);
    }

    @Test
    public void getSelectorDescriptionFallsBackToText() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getText()).thenReturn("Click me");
        when(node.getClassName()).thenReturn("android.widget.Button");
        when(node.getParent()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("Text: Click me", desc);
    }

    @Test
    public void getSelectorDescriptionFallsBackToClassName() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getText()).thenReturn(null);
        when(node.getClassName()).thenReturn("android.widget.ImageView");
        when(node.getParent()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("Class: android.widget.ImageView", desc);
    }

    @Test
    public void getSelectorDescriptionUnknownElement() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getText()).thenReturn(null);
        when(node.getClassName()).thenReturn(null);
        when(node.getParent()).thenReturn(null);

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("Unknown element", desc);
    }

    @Test
    public void generatePathReturnsNullForNullTarget() {
        String path = ElementPickerRuleGenerator.generatePath(null, rootNode);
        assertNull(path);
    }

    @Test
    public void generatePathReturnsNullForNullRoot() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        String path = ElementPickerRuleGenerator.generatePath(node, null);
        assertNull(path);
    }

    @Test
    public void generatePathWithWildcardReturnsNullForNullTarget() {
        String path = ElementPickerRuleGenerator.generatePathWithWildcard(null, rootNode);
        assertNull(path);
    }

    @Test
    public void generatePathWithWildcardReturnsNullForNullRoot() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        String path = ElementPickerRuleGenerator.generatePathWithWildcard(node, null);
        assertNull(path);
    }

    @Test
    public void generateRuleForAllWithViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/item");
        when(node.getClassName()).thenReturn("android.widget.LinearLayout");
        when(node.getParent()).thenReturn(null);

        String rule = ElementPickerRuleGenerator.generateRuleForAll(
                node, rootNode, "com.example", "Hide items");

        // generateRuleForAll prefers wildcard path, falls back to className, then viewId
        // With no parent, path will be null, so falls back to className
        assertEquals("com.example##className=android.widget.LinearLayout##comment=Hide items", rule);
    }

    @Test
    public void generateRuleForAllFallsBackToViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/item");
        when(node.getClassName()).thenReturn(null);
        when(node.getParent()).thenReturn(null);

        String rule = ElementPickerRuleGenerator.generateRuleForAll(
                node, rootNode, "com.example", null);

        assertEquals("com.example##viewId=com.example:id/item", rule);
    }
}
