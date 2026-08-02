package net.kollnig.distractionlib;

import android.graphics.Rect;
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
    public void plainLanguageDescriptionUsesLabelBeforeRole() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getContentDescription()).thenReturn("Search");
        when(node.getText()).thenReturn("Ignored");
        when(node.getClassName()).thenReturn("android.widget.Button");

        assertEquals("Search", ElementPickerRuleGenerator.plainLanguageDescription(node));
    }

    @Test
    public void plainLanguageDescriptionMapsCommonRoles() {
        AccessibilityNodeInfo image = mock(AccessibilityNodeInfo.class);
        when(image.getClassName()).thenReturn("android.widget.ImageView");
        AccessibilityNodeInfo list = mock(AccessibilityNodeInfo.class);
        when(list.getClassName()).thenReturn("androidx.recyclerview.widget.RecyclerView");

        assertEquals("picture", ElementPickerRuleGenerator.plainLanguageDescription(image));
        assertEquals("list", ElementPickerRuleGenerator.plainLanguageDescription(list));
    }

    @Test
    public void refusesFullScreenSelectionsButNotOrdinaryElements() {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo fullScreenContent = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo storyTile = mock(AccessibilityNodeInfo.class);
        setBounds(root, 0, 0, 100, 200);
        setBounds(fullScreenContent, 0, 0, 100, 200);
        setBounds(storyTile, 0, 0, 25, 50);

        assertTrue(ElementPickerRuleGenerator.refusesFullScreenSelection(root, root));
        assertTrue(ElementPickerRuleGenerator.refusesFullScreenSelection(fullScreenContent, root));
        assertFalse(ElementPickerRuleGenerator.refusesFullScreenSelection(storyTile, root));
    }

    private static void setBounds(AccessibilityNodeInfo node, int left, int top,
                                  int right, int bottom) {
        doAnswer(invocation -> {
            ((Rect) invocation.getArgument(0)).set(left, top, right, bottom);
            return null;
        }).when(node).getBoundsInScreen(any(Rect.class));
    }

    @Test
    public void generalizedMatchDoesNotRefuseAMixedVisibleSiblingGroup() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo parent = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo firstMatch = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo secondMatch = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo other = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo invisibleMatch = mock(AccessibilityNodeInfo.class);
        when(node.getParent()).thenReturn(parent);
        when(node.getClassName()).thenReturn("android.widget.FrameLayout");
        when(parent.getChildCount()).thenReturn(4);
        when(parent.getChild(0)).thenReturn(firstMatch);
        when(parent.getChild(1)).thenReturn(secondMatch);
        when(parent.getChild(2)).thenReturn(other);
        when(parent.getChild(3)).thenReturn(invisibleMatch);
        when(firstMatch.getClassName()).thenReturn("android.widget.FrameLayout");
        when(secondMatch.getClassName()).thenReturn("android.widget.FrameLayout");
        when(other.getClassName()).thenReturn("android.widget.TextView");
        when(invisibleMatch.getClassName()).thenReturn("android.widget.FrameLayout");
        when(firstMatch.isVisibleToUser()).thenReturn(true);
        when(secondMatch.isVisibleToUser()).thenReturn(true);
        when(other.isVisibleToUser()).thenReturn(true);
        setBounds(root, 0, 0, 100, 200);
        setBounds(node, 0, 0, 25, 50);

        int matches = ElementPickerRuleGenerator.countGeneralizedSiblingMatches(node);

        assertEquals(2, matches);
        assertFalse(ElementPickerRuleGenerator.refusesFullScreenSelection(node, root));
    }

    @Test
    public void generalizedMatchAllowsEveryVisibleSiblingInItsScope() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo parent = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo first = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo second = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo third = mock(AccessibilityNodeInfo.class);
        when(node.getParent()).thenReturn(parent);
        when(node.getClassName()).thenReturn("android.widget.FrameLayout");
        when(parent.getChildCount()).thenReturn(3);
        when(parent.getChild(0)).thenReturn(first);
        when(parent.getChild(1)).thenReturn(second);
        when(parent.getChild(2)).thenReturn(third);
        for (AccessibilityNodeInfo child : new AccessibilityNodeInfo[]{first, second, third}) {
            when(child.getClassName()).thenReturn("android.widget.FrameLayout");
            when(child.isVisibleToUser()).thenReturn(true);
        }
        setBounds(root, 0, 0, 100, 200);
        setBounds(node, 0, 0, 25, 50);

        int matches = ElementPickerRuleGenerator.countGeneralizedSiblingMatches(node);

        assertEquals(3, matches);
        // A wildcard leaf deliberately names this sibling group, even when all of its
        // visible members share the class (for example, a row of story tiles).
        assertFalse(ElementPickerRuleGenerator.refusesFullScreenSelection(node, root));
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

        // Nothing appended, so nothing to drop, and exact matching stays the default.
        assertEquals("com.example##desc=Message", rule);
    }

    /**
     * "AI filter, 1, unselected" carries the badge count and the selection state. Stored whole
     * it would stop matching as soon as either changed, so the volatile tail is cut and the
     * remainder matched as a prefix.
     */
    @Test
    public void navigationRuleDropsStatusAppendedToADescription() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn("AI filter, 1, unselected");

        String rule = ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", null);

        assertEquals("com.example##desc=AI filter##descMatch=prefix", rule);
    }

    @Test
    public void navigationSelectorSaysWhenItIsMatchingLoosely() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn("Unread filter, 25, unselected");

        ElementPickerRuleGenerator.NavigationSelector selector =
                ElementPickerRuleGenerator.navigationSelector(node, rootNode);

        assertEquals("Description starting with: Unread filter", selector.description);
    }

    /** A description that is nothing but status has no label to keep, so it is left whole. */
    @Test
    public void navigationRuleKeepsADescriptionThatStartsWithAComma() {
        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
        when(node.getViewIdResourceName()).thenReturn(null);
        when(node.getContentDescription()).thenReturn(", 1, unselected");

        String rule = ElementPickerRuleGenerator.generateNavigationRule(
                node, rootNode, "com.example", null);

        assertEquals("com.example##desc=, 1, unselected", rule);
    }

    // --- Ambiguity check ---

    private static AccessibilityNodeInfo describedChild(AccessibilityNodeInfo parent, String desc) {
        AccessibilityNodeInfo node = child(parent, "android.widget.RadioButton");
        node.setContentDescription(desc);
        // Only what is on screen counts -- an off-screen namesake is not an ambiguity the user
        // could see, and Robolectric's nodes are not visible unless told so.
        node.setVisibleToUser(true);
        return node;
    }

    @Test
    public void countsEveryElementALooseSelectorWouldReach() {
        describedChild(rootNode, "All filter, , selected");
        describedChild(rootNode, "Unread filter, 25, unselected");
        describedChild(rootNode, "AI filter, 1, unselected");

        FilterRule tooLoose = new FilterRuleParser().parseRules(new String[]{
                "com.example##desc=filter##descMatch=substring"}).get(0);
        FilterRule justRight = new FilterRuleParser().parseRules(new String[]{
                "com.example##desc=AI filter##descMatch=prefix"}).get(0);

        // Every chip on the row is a "filter", so that selector names all of them and would
        // click whichever the tree walk reached first. The picker refuses it; the specific
        // one it does offer names exactly the element that was tapped.
        assertEquals(3, ElementPickerRuleGenerator.countDescriptionMatches(rootNode, tooLoose));
        assertEquals(1, ElementPickerRuleGenerator.countDescriptionMatches(rootNode, justRight));
    }

    @Test
    public void countsNothingWhenTheDescriptionIsAbsent() {
        describedChild(rootNode, "Groups filter, 5, unselected");

        FilterRule rule = new FilterRuleParser().parseRules(new String[]{
                "com.example##desc=Archived##descMatch=prefix"}).get(0);

        assertEquals(0, ElementPickerRuleGenerator.countDescriptionMatches(rootNode, rule));
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
