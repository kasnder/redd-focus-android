package net.kollnig.distractionlib;

import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class ElementPickerRuleGeneratorTest {

    private AccessibilityNodeInfo rootNode;

    @Before
    public void setUp() {
        // Use a real AccessibilityNodeInfo instead of a mock so that
        // Robolectric's equals() works correctly inside generatePath/generatePathWithWildcard.
        rootNode = AccessibilityNodeInfo.obtain();
    }

    /** Appends a child of the given class to a node and returns it. */
    private static AccessibilityNodeInfo child(AccessibilityNodeInfo parent, String className) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setClassName(className);
        shadowOf(parent).addChild(node);
        return node;
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
        // Use real AccessibilityNodeInfo to avoid NPE in AccessibilityNodeInfo.obtain() within generatePath
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setText("Click me");
        node.setClassName("android.widget.TextView");

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", null);

        assertEquals("com.example##text=Click me", rule);
    }

    @Test
    public void generateRuleFallsBackToClassNameWhenNoViewIdPathOrText() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setClassName("android.widget.ImageView");

        String rule = ElementPickerRuleGenerator.generateRule(node, rootNode, "com.example", null);

        assertEquals("com.example##className=android.widget.ImageView", rule);
    }

    @Test
    public void generateRuleWithEmptyViewId() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setViewIdResourceName("");
        node.setText("Some text");
        node.setClassName("android.widget.TextView");

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
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setText("Click me");
        node.setClassName("android.widget.Button");

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("Text: Click me", desc);
    }

    @Test
    public void getSelectorDescriptionFallsBackToClassName() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setClassName("android.widget.ImageView");

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("Class: android.widget.ImageView", desc);
    }

    @Test
    public void getSelectorDescriptionUnknownElement() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();

        String desc = ElementPickerRuleGenerator.getSelectorDescription(node, rootNode);

        assertEquals("Unknown element", desc);
    }

    @Test
    public void generateRuleAnchorsPathOnNearestViewIdAncestor() {
        AccessibilityNodeInfo list = child(rootNode, "androidx.recyclerview.widget.RecyclerView");
        list.setViewIdResourceName("com.example:id/list");
        child(list, "android.view.ViewGroup");
        AccessibilityNodeInfo target = child(list, "android.view.ViewGroup");

        String rule = ElementPickerRuleGenerator.generateRule(target, rootNode, "com.example", null);

        assertEquals("com.example##viewId=com.example:id/list"
                + "##childPath=android.view.ViewGroup[1]", rule);
    }

    @Test
    public void generateRuleFallsBackToRootRelativePathWithoutViewIdAncestor() {
        AccessibilityNodeInfo container = child(rootNode, "android.widget.FrameLayout");
        AccessibilityNodeInfo target = child(container, "android.view.ViewGroup");

        String rule = ElementPickerRuleGenerator.generateRule(target, rootNode, "com.example", null);

        assertEquals("com.example##path=android.widget.FrameLayout[0]"
                + ">android.view.ViewGroup[0]", rule);
    }

    @Test
    public void generateRuleForAllAnchorsPathAndWildcardsTheLeaf() {
        AccessibilityNodeInfo list = child(rootNode, "androidx.recyclerview.widget.RecyclerView");
        list.setViewIdResourceName("com.example:id/list");
        AccessibilityNodeInfo target = child(list, "android.view.ViewGroup");

        String rule = ElementPickerRuleGenerator.generateRuleForAll(
                target, rootNode, "com.example", "Hide cards");

        assertEquals("com.example##viewId=com.example:id/list"
                + "##childPath=android.view.ViewGroup[*]##comment=Hide cards", rule);
    }

    @Test
    public void getSelectorDescriptionReportsAnchor() {
        AccessibilityNodeInfo list = child(rootNode, "androidx.recyclerview.widget.RecyclerView");
        list.setViewIdResourceName("com.example:id/list");
        AccessibilityNodeInfo target = child(list, "android.view.ViewGroup");

        String desc = ElementPickerRuleGenerator.getSelectorDescription(target, rootNode);

        assertEquals("Path under com.example:id/list: android.view.ViewGroup[0]", desc);
    }

    @Test
    public void generatePathReturnsNullForNullTarget() {
        String path = ElementPickerRuleGenerator.generatePath(null, rootNode);
        assertNull(path);
    }

    @Test
    public void generatePathReturnsNullForNullRoot() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
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
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        String path = ElementPickerRuleGenerator.generatePathWithWildcard(node, null);
        assertNull(path);
    }

    @Test
    public void generateRuleForAllWithViewId() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setViewIdResourceName("com.example:id/item");
        node.setClassName("android.widget.LinearLayout");

        String rule = ElementPickerRuleGenerator.generateRuleForAll(
                node, rootNode, "com.example", "Hide items");

        // generateRuleForAll prefers wildcard path, falls back to className, then viewId
        // With no parent, path will be null, so falls back to className
        assertEquals("com.example##className=android.widget.LinearLayout##comment=Hide items", rule);
    }

    @Test
    public void generateRuleForAllFallsBackToViewId() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setViewIdResourceName("com.example:id/item");

        String rule = ElementPickerRuleGenerator.generateRuleForAll(
                node, rootNode, "com.example", null);

        assertEquals("com.example##viewId=com.example:id/item", rule);
    }

    // --- Navigation selectors ---
    //
    // These are held to a stricter standard than blocking selectors: a blocking rule that
    // drifts covers the wrong box, a navigation rule that drifts taps it.

    @Test
    public void navigationRulePrefersViewId() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/direct_tab");
        when(node.getContentDescription()).thenReturn("Message");

        String rule = ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", "Open messages");

        assertEquals("com.example##viewId=com.example:id/direct_tab##comment=Open messages", rule);
    }

    @Test
    public void navigationRuleFallsBackToContentDescription() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn("Message");

        String rule = ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", null);

        assertEquals("com.example##desc=Message", rule);
    }

    /**
     * A pipe separates alternatives in a desc list, so emitting one inside a description would
     * produce two alternatives that each match nothing.
     */
    @Test
    public void navigationRuleRejectsDescriptionContainingPipe() {
        // A real node, not a mock: with no usable description the generator falls through to
        // the anchored-path rung, which walks the tree and cannot run against a mock.
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setContentDescription("Chats | Updates");

        assertNull(ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", null));
    }

    @Test
    public void navigationRuleUsesAnchoredPathWhenNothingElseIdentifiesTheElement() {
        AccessibilityNodeInfo anchor = child(rootNode, "android.widget.LinearLayout");
        anchor.setViewIdResourceName("com.example:id/tab_bar");
        AccessibilityNodeInfo tab = child(anchor, "android.widget.Button");

        String rule = ElementPickerRuleGenerator.generateNavigationRule(
                tab, rootNode, "com.example", null);

        assertEquals("com.example##viewId=com.example:id/tab_bar"
                + "##childPath=android.widget.Button[0]", rule);
    }

    /**
     * className would match the first container on screen and a root-relative path re-resolves
     * against whatever later occupies that position. Neither is safe to click, so the picker
     * refuses instead of generating a rule that will one day tap the wrong thing.
     */
    @Test
    public void navigationRuleRefusesWhenOnlyClassNameIsAvailable() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setClassName("android.widget.FrameLayout");

        assertNull(ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", null));
        assertNull(ElementPickerRuleGenerator.navigationSelector(node, rootNode));
    }

    @Test
    public void navigationRuleRefusesTextOnlyElements() {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setText("Favourites");

        // The navigation matcher never consults text, so a text rule would silently never fire.
        assertNull(ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", null));
    }

    @Test
    public void navigationRuleStripsSeparatorFromComment() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn("com.example:id/tab");

        String rule = ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", "a##b");

        assertEquals("com.example##viewId=com.example:id/tab##comment=ab", rule);
    }

    @Test
    public void navigationSelectorDescribesWhatMatched() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn("Message");

        ElementPickerRuleGenerator.NavigationSelector selector =
                ElementPickerRuleGenerator.navigationSelector(node, rootNode);

        assertNotNull(selector);
        assertEquals("Description: Message", selector.description);
    }
}
